package com.campus.auth.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.auth.dto.req.LoginReqDTO;
import com.campus.auth.dto.resp.LoginRespDTO;
import com.campus.auth.dto.resp.UserInfoRespDTO;
import com.campus.auth.errorcode.AuthErrorCode;
import com.campus.auth.service.AuthService;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.common.exception.ServiceException;
import com.campus.framework.user.constant.UserConstant;
import com.campus.framework.user.core.UserContext;
import com.campus.framework.user.core.UserInfoDTO;
import com.campus.framework.user.toolkit.JWTUtil;
import com.campus.user.dao.entity.UserDO;
import com.campus.user.dao.entity.UserMailDO;
import com.campus.user.dao.entity.UserPhoneDO;
import com.campus.user.dao.entity.UserStudentNoDO;
import com.campus.user.dao.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 认证服务实现。
 *
 * <p>登录链路（对应 12306 UserLoginServiceImpl 的校园改造版）：</p>
 * <pre>
 *   账号自动识别（学号/手机/邮箱）
 *     → 查 t_user（三种唯一索引各自命中，见 docs/01 3.1）
 *     → 状态检查 → BCrypt 校验
 *     → JWT 签发（HS512，载荷 userId/studentNo/realName/role）
 *     → Redis 写登录态副本（TTL 30 分钟，"可主动失效"的载体）
 * </pre>
 *
 * <p>与 12306 的差异（阶段 14 起对齐）：分库分表后 t_user 按 user_id 分片，
 * 登录标识（学号/手机/邮箱）走"读扩散"索引表定位分片（见 {@link #queryByAccount}）——
 * 与 12306 的 邮箱表/手机表 解法同构，本平台多一张学号索引表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    /** 阶段 14：登录标识 → user_id 索引表（读扩散） */
    private final com.campus.user.dao.mapper.UserPhoneMapper userPhoneMapper;
    private final com.campus.user.dao.mapper.UserMailMapper userMailMapper;
    private final com.campus.user.dao.mapper.UserStudentNoMapper userStudentNoMapper;
    private final JWTUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    /** 阶段 12：测试数据重置改为服务间调用（user-service → campus-service 内部接口） */
    private final com.campus.user.client.TestDataResetClient testDataResetClient;

    /** 常驻测试账号学号（登录页"测试账号"按钮；每次登录自动重置预约与选课数据） */
    private static final String TEST_ACCOUNT = "test";

    /** 测试账号默认密码（登录重置时一并还原，自愈防锁死） */
    private static final String TEST_PASSWORD = "123456";

    @Value("${campus.jwt.session-ttl-seconds:1800}")
    private long sessionTtlSeconds;

    @Override
    public LoginRespDTO login(LoginReqDTO requestParam) {
        String account = requestParam.getAccount().trim();
        UserDO user = queryByAccount(account);
        if (user == null) {
            // 账号不存在与密码错误返回同一提示，不向调用方泄露账号是否存在
            log.info("登录失败（账号不存在）: account={}", account);
            throw new ClientException(AuthErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != UserConstant.STATUS_NORMAL) {
            log.info("登录失败（账号禁用）: studentNo={}", user.getStudentNo());
            throw new ClientException(AuthErrorCode.ACCOUNT_DISABLED);
        }
        if (!BCrypt.checkpw(requestParam.getPassword(), user.getPassword())) {
            log.info("登录失败（密码错误）: studentNo={}", user.getStudentNo());
            throw new ClientException(AuthErrorCode.LOGIN_FAILED);
        }

        // 常驻测试账号（student_no='test'）：每次登录自动重置预约与选课数据，便于反复体验完整流程
        if (TEST_ACCOUNT.equals(user.getStudentNo())) {
            // 阶段 12：预约/选课数据归属 campus-service，经由负载均衡的内部接口跨服务重置
            testDataResetClient.resetTestData(user.getId());
            // 安全绳：密码一并还原为默认 123456 —— 演示"修改密码"后，下次成功登录即自愈，
            // 避免演示者忘记改动后的密码而被锁在测试账号之外
            user.setPassword(BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt()));
            userMapper.updateById(user);
        }

        UserInfoDTO userInfo = UserInfoDTO.builder()
                .userId(user.getId())
                .studentNo(user.getStudentNo())
                .realName(user.getRealName())
                .role(user.getRole())
                .build();
        String accessToken = jwtUtil.generateAccessToken(userInfo);
        userInfo.setToken(accessToken);
        try {
            // Redis 登录态副本：value = 用户信息 JSON（与 12306 存登录响应体等价）
            stringRedisTemplate.opsForValue().set(
                    UserConstant.LOGIN_TOKEN_KEY + accessToken,
                    objectMapper.writeValueAsString(userInfo),
                    Duration.ofSeconds(sessionTtlSeconds));
        } catch (Exception ex) {
            throw new ServiceException("登录态写入 Redis 失败: " + ex.getMessage(), ex, BaseErrorCode.SERVICE_ERROR);
        }
        log.info("用户登录成功: {}（{}）", user.getRealName(), user.getStudentNo());
        return LoginRespDTO.builder()
                .accessToken(accessToken)
                .userId(user.getId())
                .studentNo(user.getStudentNo())
                .realName(user.getRealName())
                .role(user.getRole())
                .build();
    }

    @Override
    public UserInfoRespDTO currentUser() {
        Long userId = UserContext.getUserId();
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new ClientException(AuthErrorCode.SESSION_EXPIRED);
        }
        return UserInfoRespDTO.builder()
                .userId(user.getId())
                .studentNo(user.getStudentNo())
                .realName(user.getRealName())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .role(user.getRole())
                .build();
    }

    @Override
    public void logout() {
        String token = UserContext.getToken();
        if (token != null) {
            stringRedisTemplate.delete(UserConstant.LOGIN_TOKEN_KEY + token);
            log.info("用户登出: {}", UserContext.getStudentNo());
        }
    }

    /**
     * 账号类型自动识别（O(n) 单次扫描，同 12306 的字符判断风格）：
     * 含 '@' → 邮箱；11 位纯数字且以 1 开头 → 手机号；其余 → 学号。
     *
     * <p>阶段 14（分库分表）：t_user 按 user_id 分片，三种登录标识都不是分片键——
     * 走"读扩散"索引表（见 docs/04 §4.4）：先查 t_user_phone / t_user_mail /
     * t_user_student_no（各自按 CRC32(标识) 分片 → 单分片命中）拿到 user_id，
     * 再按分片键回查用户分片。一次额外查询，换来"永不广播扫描 8 个分片"。</p>
     */
    private UserDO queryByAccount(String account) {
        boolean mailFlag = false;
        for (char c : account.toCharArray()) {
            if (c == '@') {
                mailFlag = true;
                break;
            }
        }
        Long userId;
        if (mailFlag) {
            UserMailDO index = userMailMapper.selectById(account);
            userId = index == null ? null : index.getUserId();
        } else if (isPhone(account)) {
            UserPhoneDO index = userPhoneMapper.selectById(account);
            userId = index == null ? null : index.getUserId();
        } else {
            UserStudentNoDO index = userStudentNoMapper.selectById(account);
            userId = index == null ? null : index.getUserId();
        }
        return userId == null ? null : userMapper.selectById(userId);
    }

    private boolean isPhone(String account) {
        if (account.length() != 11 || account.charAt(0) != '1') {
            return false;
        }
        for (char c : account.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
