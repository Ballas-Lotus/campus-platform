package com.campus.reservation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 预约惩罚服务（2026-10-08 业务规则）。
 *
 * <p>两种惩罚，统一用 Redis Key 承载（TTL 天然到期，免定时清理）：</p>
 * <ul>
 *   <li><b>爽约</b>：开始后未签到 → 判 NO_SHOW → 禁约 24 小时（"第二天无法预约"）</li>
 *   <li><b>临近取消</b>：距开始不足 30 分钟取消 → 禁约 10 分钟（提前 30 分钟以上取消无惩罚）</li>
 * </ul>
 *
 * <p>Key: {@code campus:penalty:{userId}}；Value: "{截止时间 ISO}|{原因}"（重复惩罚取更晚的截止时间）。</p>
 */
@Service
@RequiredArgsConstructor
public class PenaltyService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "campus:penalty:";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 惩罚信息（截止时间 + 原因） */
    public record Penalty(LocalDateTime until, String reason) {
    }

    /**
     * 施加惩罚（若已有更晚的惩罚则保留更晚者；TTL 到期自动解除）。
     */
    public void apply(Long userId, Duration duration, String reason) {
        LocalDateTime until = LocalDateTime.now().plus(duration);
        Penalty existing = get(userId);
        if (existing != null && existing.until().isAfter(until)) {
            return;   // 已有更重的惩罚，保持不动
        }
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + userId,
                until + "|" + reason, duration);
    }

    /**
     * 查询当前惩罚（无惩罚或已过期返回 null）。
     */
    public Penalty get(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.split("\\|", 2);
        try {
            LocalDateTime until = LocalDateTime.parse(parts[0]);
            if (until.isBefore(LocalDateTime.now())) {
                return null;   // 兜底：值已过期
            }
            return new Penalty(until, parts.length > 1 ? parts[1] : "违规");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成面向用户的提示文案（"失败原因说人话"）。
     */
    public String describe(Penalty penalty) {
        return String.format("你因「%s」被限制预约，解禁时间 %s", penalty.reason(), FMT.format(penalty.until()));
    }

    /**
     * 清除惩罚（测试账号重置用）。
     */
    public void clear(Long userId) {
        stringRedisTemplate.delete(KEY_PREFIX + userId);
    }
}
