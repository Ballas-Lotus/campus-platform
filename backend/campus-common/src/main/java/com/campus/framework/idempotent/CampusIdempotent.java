package com.campus.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等注解（阶段 18）。
 *
 * <p>与阶段 13 的 {@code @MQIdempotent}（消费侧两态幂等）互补：那个管"消息重复投递"，
 * 这个管"用户重复提交"——例如抢课/预约时用户狂点、前端重试、网关重发。</p>
 *
 * <p>幂等键构成：{@code campus:idem:{prefix}:{userId}:{业务键}}，
 * 其中业务键由 {@link #key()} 的 SpEL 表达式决定（留空则取方法入参的 MD5）。</p>
 *
 * <p><b>边界（重要）</b>：注解只是"快速挡重复"的第一道，<b>不承担唯一性的最终责任</b>——
 * 真正的唯一性仍由数据库唯一索引（{@code uk_reserved} / {@code uk_user_class_semester}）兜底。
 * 理由：Redis 可能丢数据、注解可能被绕过（新增入口忘了标注），而 DB 约束是不可绕过的。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CampusIdempotent {

    /** 业务前缀（如 reserve / course-select），构成幂等键的域 */
    String prefix();

    /**
     * 业务键 SpEL（可选）。可用变量：{@code #req}、{@code #classId} 等形参名。
     * 留空 → 自动取全部入参的 MD5（参数级防重）。
     */
    String key() default "";

    /** 幂等窗口（秒）：窗口内重复请求视为重复提交 */
    long ttlSeconds() default 10;

    /** 重复提交时的提示语（前端直接展示） */
    String message() default "请勿重复提交";
}
