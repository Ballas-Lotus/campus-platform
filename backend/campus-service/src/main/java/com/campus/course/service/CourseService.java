package com.campus.course.service;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.entity.CourseDO;
import com.campus.course.dao.entity.CourseSelectionDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dao.mapper.CourseMapper;
import com.campus.course.dao.mapper.CourseSelectionMapper;
import com.campus.course.dto.req.CourseSelectReqDTO;
import com.campus.course.dto.resp.CourseClassRespDTO;
import com.campus.course.dto.resp.MyCourseRespDTO;
import com.campus.course.errorcode.CourseErrorCode;
import com.campus.framework.cache.CacheEvictions;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.framework.user.core.UserContext;
import com.campus.user.dao.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 选课服务（阶段 8；2026-10-08 规则：<b>人数限额制，无候补队列</b>）。
 *
 * <p>选课三道闸门：</p>
 * <ol>
 *   <li><b>重复选课</b>：唯一索引 uk_user_class_semester 兜底（"选-退-选"复用同一行）</li>
 *   <li><b>时间冲突</b>：同一学期内，周几相同且节次区间重叠 → 拒绝</li>
 *   <li><b>名额已满</b>：条件更新 {@code UPDATE ... SET selected=selected+1 WHERE selected<capacity}
 *       —— 防超卖唯一防线（与预约引擎同一套路：把判断写进 UPDATE 的 WHERE）</li>
 * </ol>
 *
 * <p>并发细节：冲突检测是"读后判定"，两请求同时读旧课表会都通过——
 * 所以先用 {@code SELECT ... FOR UPDATE} 锁用户行，把同一用户的选课/退课串行化；
 * 跨用户抢最后一个名额由条件更新裁决（先到先得）。</p>
 *
 * <p><b>阶段 16</b>：教学班列表（含余量）缓存化，且<b>用户态不进缓存</b>
 * ——"我的已选标记"按用户不同，混进缓存会导致串号；详见 {@link #classes(Long)}。</p>
 *
 * <p><b>阶段 17</b>：选课入口前置 Redis 预扣闸门（见 {@code CourseSelectGate}）——
 * 本类的条件更新仍是最终裁决（真相），预扣只负责把绝大多数请求挡在数据库之前。</p>
 *
 * <p><b>阶段 19</b>：教学班缓存拆成"<b>静态信息 + 动态余量 Hash</b>"（吸收 12306 的读模型思路）：
 * 余量由写路径提交后 HINCRBY 增量维护（不再整键失效），读路径一次 pipeline 合并两者——
 * "高频变化字段不进静态缓存"，同款原则的推广。详见 {@link com.campus.cache.ClassStockCache}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseClassMapper courseClassMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final UserMapper userMapper;
    /** 阶段 13：选课成功 → 站内信（异步） */
    private final com.campus.mq.MqMessagePublisher mqMessagePublisher;
    /** 阶段 16：缓存门面（概览等缓存 + 失效） */
    private final CampusCache cache;
    /** 阶段 19：教学班读模型（静态信息 + 动态余量 Hash，pipeline 合并读） */
    private final com.campus.cache.ClassStockCache classStockCache;

    /** 阶段 20：名额实时同步（读侧）——写后推送信号 + 版本；语义不参与判定 */
    private final com.campus.stock.CourseStockService courseStockService;

    private static final String SELECTED = "SELECTED";
    private static final String DROPPED = "DROPPED";
    private static final String[] WEEK_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 教学班列表（含当前用户选课状态标记）。
     *
     * <p>缓存设计——<b>共享数据进缓存、用户态不进缓存</b>：</p>
     * <ul>
     *   <li>教学班的名称/教师/时间/容量：所有用户看到的一样 → 静态信息缓存（10 分钟）；</li>
     *   <li>余量（selected/available）：变化最快 → <b>动态余量 Hash</b>（写路径增量维护，阶段 19）；</li>
     *   <li>我的选课状态：每人不同 → 每次请求实时查（一笔按 (user_id, classIds) 的轻查询）。</li>
     * </ul>
     * <p>若把用户态混进缓存，就会出现 A 用户看到 B 用户"已选"标记的串号问题——
     * 这是列表类缓存最容易踩的坑。</p>
     */
    public List<CourseClassRespDTO> classes(Long courseId) {
        // 阶段 19：一次 pipeline 取回"静态信息 + 余量 Hash"（命中路径 1 次往返）
        com.campus.cache.ClassStockCache.ReadModel model = classStockCache.read(
                courseId, new TypeReference<>() {
                }, () -> loadClassesInfo(courseId));
        List<CourseClassRespDTO> base = model.info();

        Map<Long, String> myStatus = new HashMap<>();
        if (!base.isEmpty()) {
            List<Long> ids = base.stream().map(CourseClassRespDTO::getId).collect(Collectors.toList());
            for (Map<String, Object> row : courseSelectionMapper.selectMyStatusInClasses(UserContext.getUserId(), ids)) {
                myStatus.put(((Number) row.get("classId")).longValue(), (String) row.get("status"));
            }
        }
        // 从缓存反序列化出来的对象每次都是新实例，但仍显式拷贝一次：让"缓存对象不可被就地修改"成为默认约束
        return base.stream().map(c -> {
            CourseClassRespDTO dto = new CourseClassRespDTO();
            BeanUtil.copyProperties(c, dto);
            Integer available = model.stock().get(c.getId());
            if (available != null && c.getCapacity() != null) {
                int avail = Math.max(0, available);
                dto.setAvailable(avail);
                dto.setSelected(Math.max(0, c.getCapacity() - avail));
            }
            dto.setMyStatus(myStatus.get(c.getId()));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 回源：教学班<b>静态信息</b>（阶段 19 起不含 selected/available——余量由读模型 Hash 提供，
     * 避免"会变的值"挤进长 TTL 的静态缓存）。
     */
    public List<CourseClassRespDTO> loadClassesInfo(Long courseId) {
        CourseDO course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ClientException(BaseErrorCode.NOT_FOUND);
        }
        List<CourseClassDO> classes = courseClassMapper.selectList(Wrappers.lambdaQuery(CourseClassDO.class)
                .eq(CourseClassDO::getCourseId, courseId)
                .orderByAsc(CourseClassDO::getId));
        return classes.stream().map(c -> CourseClassRespDTO.builder()
                        .id(c.getId())
                        .courseId(c.getCourseId())
                        .className(c.getClassName())
                        .teacher(c.getTeacher())
                        .semester(c.getSemester())
                        .capacity(c.getCapacity())
                        .weekDay(c.getWeekDay())
                        .startSection(c.getStartSection())
                        .endSection(c.getEndSection())
                        .startWeek(c.getStartWeek())
                        .endWeek(c.getEndWeek())
                        .location(c.getLocation())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 选课（人数限额，满员直接拒绝）——同步老链路入口（包装，保持历史签名）。
     */
    public MyCourseRespDTO select(CourseSelectReqDTO req) {
        return select(UserContext.getUserId(), req.getClassId(), null);
    }

    /**
     * 选课（人数限额，满员直接拒绝）。
     *
     * <p><b>阶段 21（受理制）</b>：本方法成为"迟到的记录者"——受理模式下由 MQ 消费者调用
     * （显式传 userId：消费线程没有 UserContext），把受理单号写进行内 {@code op_id}
     * （消费端据此判定"重放是否已生效"）。四道防线原样保留：
     * 锁用户行 / 唯一索引 / 时间冲突 / 条件更新占名额——数据库仍是最终校验者。</p>
     *
     * @param opId 受理单号；null = 同步老链路（阶段 19 语义，写后自己推送名额信号）
     */
    @Transactional(rollbackFor = Exception.class)
    public MyCourseRespDTO select(Long userId, Long classId, String opId) {
        // ① 锁用户行：同一用户的选课/退课串行化（防"两个冲突班并发同时选"）
        userMapper.lockById(userId);

        CourseClassDO cls = courseClassMapper.selectById(classId);
        if (cls == null) {
            throw new ClientException(CourseErrorCode.CLASS_NOT_FOUND);
        }
        CourseDO course = courseMapper.selectById(cls.getCourseId());
        String semester = cls.getSemester();

        // ② 重复选课（唯一索引是终极兜底，此处先查给出友好提示）
        CourseSelectionDO existing = courseSelectionMapper.selectOne(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getUserId, userId)
                .eq(CourseSelectionDO::getClassId, cls.getId()));
        if (existing != null && SELECTED.equals(existing.getStatus())) {
            throw new ClientException(CourseErrorCode.ALREADY_SELECTED);
        }

        // ③ 时间冲突：同周几 + 节次区间重叠（与种子数据校验同一口径）
        for (Map<String, Object> m : courseSelectionMapper.selectMyClassSlots(userId, semester)) {
            int day = ((Number) m.get("weekDay")).intValue();
            int s1 = ((Number) m.get("startSection")).intValue();
            int s2 = ((Number) m.get("endSection")).intValue();
            if (day == cls.getWeekDay() && s1 <= cls.getEndSection() && cls.getStartSection() <= s2) {
                String tip = String.format("与已选课程《%s》%s 冲突（%s 第%d-%d节）",
                        m.get("courseName"), m.get("className"),
                        WEEK_NAMES[day], s1, s2);
                throw new ClientException("选课冲突: " + tip, tip, CourseErrorCode.TIME_CONFLICT);
            }
        }

        // ④ 占名额——防超卖唯一防线（0 行 = 已满，先到先得）
        if (courseClassMapper.occupySeat(cls.getId()) == 0) {
            String tip = String.format("手慢了，《%s》%s 名额已满（%d/%d）",
                    course.getName(), cls.getClassName(), cls.getSelected(), cls.getCapacity());
            throw new ClientException("名额已满: " + tip, tip, CourseErrorCode.COURSE_FULL);
        }

        // ⑤ 写选课记录："选-退-选"复用同一行（状态复活），首次则插入；落库受理单号（重放判定用）
        if (existing != null) {
            existing.setStatus(SELECTED);
            if (opId != null) {
                existing.setOpId(opId);
            }
            courseSelectionMapper.updateById(existing);
        } else {
            CourseSelectionDO row = new CourseSelectionDO();
            row.setUserId(userId);
            row.setClassId(cls.getId());
            row.setSemester(semester);
            row.setStatus(SELECTED);
            row.setOpId(opId);
            courseSelectionMapper.insert(row);
        }

        // 读回落库后的记录（拿自增 id 与 create_time）
        CourseSelectionDO saved = courseSelectionMapper.selectOne(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getUserId, userId)
                .eq(CourseSelectionDO::getClassId, cls.getId()));
        // ⑥ 异步侧（阶段 13 · MQ）：选课成功站内信——阶段 21 移到 afterCommit：
        //    受理制下这是"落库成立"的正式通知（消费端 settled 回执只是即时一瞥），
        //    且 syncSend 不再占用事务时间（事务瘦身同款原则）
        CacheEvictions.afterCommit(() -> mqMessagePublisher.sendNotification(
                com.campus.mq.message.NotificationMessage.of(
                        userId, "选课成功",
                        String.format("你已选中《%s》%s（教师 %s，周%s 第%d-%d节，地点 %s）。",
                                course.getName(), cls.getClassName(), cls.getTeacher(),
                                WEEK_NAMES[cls.getWeekDay()], cls.getStartSection(), cls.getEndSection(), cls.getLocation()),
                        "COURSE", "class-" + cls.getId() + "-" + System.currentTimeMillis())));

        // ⑦ 阶段 19/21：提交后——读模型余量增量 -1；
        //    同步老链路（opId=null）还要写受理结构并推送信号 + 维护"已选集合"；
        //    受理模式（opId≠null）这两件事在受理/消费环节完成，此处不重复
        afterStockDelta(cls.getCourseId(), cls.getId(), -1, opId);
        if (opId == null) {
            CacheEvictions.afterCommit(() -> courseStockService.selAdd(userId, cls.getId()));
        }

        log.info("[选课] user={} 选中 {}-{}（{}/{}）{}", userId, course.getName(), cls.getClassName(),
                cls.getSelected() + 1, cls.getCapacity(),
                opId == null ? "" : "op=" + opId);
        MyCourseRespDTO dto = toMyCourse(course, cls, saved == null ? null : saved.getCreateTime());
        if (saved != null) {
            dto.setId(saved.getId());
        }
        return dto;
    }

    /**
     * 退课：状态置 DROPPED + 释放名额（同一事务）。无候补队列——名额直接回到池子，先到先得。
     */
    @Transactional(rollbackFor = Exception.class)
    public void drop(Long classId) {
        Long userId = UserContext.getUserId();

        // 锁用户行：与"选课"互斥，防"退课的同时又选入同一时段"竞态
        userMapper.lockById(userId);

        CourseSelectionDO row = courseSelectionMapper.selectOne(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getUserId, userId)
                .eq(CourseSelectionDO::getClassId, classId));
        if (row == null || !SELECTED.equals(row.getStatus())) {
            throw new ClientException(CourseErrorCode.NOT_SELECTED);
        }
        row.setStatus(DROPPED);
        courseSelectionMapper.updateById(row);
        courseClassMapper.releaseSeat(classId);

        CourseClassDO cls = courseClassMapper.selectById(classId);
        // 阶段 19/21：读模型余量 +1 + 同步链路的名额结构与信号（退课保持同步落库——非热点，
        // 且"失败即未退"的语义对账最清晰）
        afterStockDelta(cls == null ? null : cls.getCourseId(), classId, 1, null);
        // 阶段 21：从"已选集合"移除（允许重新选入同一班）
        CacheEvictions.afterCommit(() -> courseStockService.selRemove(userId, classId));
        log.info("[退课] user={} 退掉教学班 {}（释放 1 个名额）", userId, classId);
    }

    /**
     * 我的课表（本学期已选，按周几 + 节次排序）。
     */
    public List<MyCourseRespDTO> my() {
        return courseSelectionMapper.selectMyCourses(UserContext.getUserId());
    }

    /**
     * 测试账号登录重置：退掉全部已选（释放名额）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetForTestUser(Long userId) {
        List<CourseSelectionDO> mine = courseSelectionMapper.selectList(Wrappers.lambdaQuery(CourseSelectionDO.class)
                .eq(CourseSelectionDO::getUserId, userId)
                .eq(CourseSelectionDO::getStatus, SELECTED));
        for (CourseSelectionDO row : mine) {
            row.setStatus(DROPPED);
            courseSelectionMapper.updateById(row);
            courseClassMapper.releaseSeat(row.getClassId());
        }
        if (!mine.isEmpty()) {
            // 阶段 19：批量回退不是热点路径——涉及的课程直接"失效余量 Hash"（下次读按 DB 真值重建），
            // 比逐班 HINCRBY 更简单可靠
            java.util.Set<Long> courseIds = new java.util.LinkedHashSet<>();
            for (CourseSelectionDO row : mine) {
                CourseClassDO cls = courseClassMapper.selectById(row.getClassId());
                if (cls != null) {
                    courseIds.add(cls.getCourseId());
                }
            }
            CacheEvictions.afterCommit(() -> {
                for (Long cid : courseIds) {
                    classStockCache.evict(cid);
                    courseStockService.evict(cid);   // 阶段 20：名额同步结构同步失效（下一位读者按 DB 真值重建）
                }
                cache.evict(CampusCacheKeys.overviewKey());
            });
            log.info("[测试重置] user={} 退掉 {} 门选课", userId, mine.size());
        }
        // 阶段 21：无论有无已选都清一次"已选集合"（下一轮惰性按 DB 重建）——
        // 它是受理闸门的加速器；登录重置的语义是"把这个账号的服务端状态抹平"，
        // 残留的集合条目（如历史失败受理的 SADD）会让后续选课被"快速已选检查"误拦
        CacheEvictions.afterCommit(() -> courseStockService.selClear(userId));
    }

    /**
     * 阶段 19：写后的读模型处理——余量 Hash 增量维护（选课 -1 / 退课 +1）+ 概览失效。
     *
     * <p>与阶段 16 的差别：<b>不再整键失效教学班缓存</b>（静态信息不含余量，不必动），
     * <b>也不再让写路径 SCAN 清课程列表</b>（列表聚合值随 60 秒 TTL 自然刷新——
     * 写热点路径上不该出现 SCAN）。提交后执行，见 {@link CacheEvictions}。</p>
     *
     * <p><b>阶段 21</b>：{@code opId != null} = 受理模式落库——名额结构已在 ACCEPT 原子扣减，
     * 这里<b>绝不能</b>再 push 一次（会双重扣减）；清账由消费端完成后单独执行。</p>
     */
    private void afterStockDelta(Long courseId, Long classId, int delta, String opId) {
        CacheEvictions.afterCommit(() -> {
            if (courseId != null && classId != null) {
                classStockCache.applyDelta(courseId, classId, delta);
                if (opId == null) {
                    // 同步老链路（阶段 20 语义）：落库后推送名额信号（版本+余量原子写入 + Pub/Sub 广播）
                    courseStockService.push(courseId, classId, delta);
                }
            }
            cache.evict(CampusCacheKeys.overviewKey());
        });
    }

    private MyCourseRespDTO toMyCourse(CourseDO course, CourseClassDO cls, java.time.LocalDateTime createTime) {
        MyCourseRespDTO dto = new MyCourseRespDTO();
        dto.setClassId(cls.getId());
        dto.setCourseId(cls.getCourseId());
        dto.setCourseCode(course.getCourseCode());
        dto.setCourseName(course.getName());
        dto.setCredits(course.getCredits());
        dto.setCourseType(course.getCourseType());
        dto.setCollege(course.getCollege());
        dto.setClassName(cls.getClassName());
        dto.setTeacher(cls.getTeacher());
        dto.setLocation(cls.getLocation());
        dto.setWeekDay(cls.getWeekDay());
        dto.setStartSection(cls.getStartSection());
        dto.setEndSection(cls.getEndSection());
        dto.setStartWeek(cls.getStartWeek());
        dto.setEndWeek(cls.getEndWeek());
        dto.setSemester(cls.getSemester());
        dto.setCreateTime(createTime);
        return dto;
    }
}
