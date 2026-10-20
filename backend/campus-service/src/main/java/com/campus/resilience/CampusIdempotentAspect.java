package com.campus.resilience;

import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.framework.idempotent.CampusIdempotent;
import com.campus.framework.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

/**
 * 接口幂等切面（阶段 18）：{@code @CampusIdempotent} 的实现。
 *
 * <p>与阶段 13 的 MQ 消费幂等同一个套路（<b>"消费中 / 已消费"两态 Lua</b>），只是换了个场景：
 * 那边挡"消息重复投递"，这边挡"用户重复提交"。</p>
 *
 * <p>Lua 原子判定三步（一次网络往返）：</p>
 * <pre>
 *   返回 0：已消费（窗口内已有成功请求）→ 拦截
 *   返回 2：消费中（并发重复请求正在处理）→ 拦截
 *   返回 1：首次 → 置"消费中"，放行执行业务
 * </pre>
 *
 * <p><b>业务失败时释放标记</b>：让用户能重试（如"手慢了"之后换教学班再点）。
 * 幂等只防"重复成功提交"，不防"失败后的正当重试"。</p>
 *
 * <p><b>降级</b>：Redis 异常时直接放行——幂等是"减负"，不该成为可用性单点；
 * 唯一的最终责任在数据库唯一索引/重复校验。</p>
 *
 * <p><b>阶段 19</b>：Lua 与 key 规则抽取到 {@link IdempotentSupport}——选课快路径把
 * "幂等 + 预扣"合并成一个脚本，与切面共用同一份状态语义。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CampusIdempotentAspect {

    private final IdempotentSupport idempotentSupport;
    private final IdempotentMetrics metrics;

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    @Around("@annotation(campusIdempotent)")
    public Object around(ProceedingJoinPoint joinPoint, CampusIdempotent campusIdempotent) throws Throwable {
        long ttl = campusIdempotent.ttlSeconds();
        String key = buildKey(joinPoint, campusIdempotent);

        long state;
        try {
            state = idempotentSupport.tryAcquire(key, ttl);
        } catch (Exception e) {
            metrics.onDegraded();
            log.warn("[幂等] Redis 异常，降级放行: key={} {}", key, e.getMessage());
            return joinPoint.proceed();
        }

        if (state == 1L) {
            metrics.onPassed();
            try {
                Object result = joinPoint.proceed();
                idempotentSupport.markConsumed(key, ttl);
                return result;
            } catch (Throwable t) {
                idempotentSupport.releaseQuietly(key);   // 业务失败：释放标记，允许用户重试
                metrics.onReleasedForRetry();
                throw t;
            }
        }

        metrics.onBlocked(campusIdempotent.prefix());
        log.info("[幂等] 拦截重复提交: key={}（{}）", key,
                state == 0L ? "窗口内已成功" : "并发处理中");
        throw new ClientException("幂等拦截重复提交: key=" + key,
                campusIdempotent.message(), BaseErrorCode.IDEMPOTENT_REJECTED);
    }

    /** 幂等键 = campus:idem:{prefix}:{userId}:{业务键}（规则见 {@link IdempotentSupport#key}） */
    private String buildKey(ProceedingJoinPoint joinPoint, CampusIdempotent ann) {
        return IdempotentSupport.key(ann.prefix(), UserContext.getUserId(), businessKey(joinPoint, ann));
    }

    /** 业务键：优先 SpEL；表达式为空或解析失败则退化为"入参 MD5"（参数级防重，同样能挡住重复提交） */
    private String businessKey(ProceedingJoinPoint joinPoint, CampusIdempotent ann) {
        if (ann.key() == null || ann.key().isBlank()) {
            return md5OfArgs(joinPoint.getArgs());
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            EvaluationContext context = new MethodBasedEvaluationContext(
                    null, method, joinPoint.getArgs(), PARAM_NAMES);
            Object value = PARSER.parseExpression(ann.key()).getValue(context);
            return value == null ? md5OfArgs(joinPoint.getArgs()) : String.valueOf(value);
        } catch (Exception e) {
            log.warn("[幂等] SpEL 解析失败（退化为入参摘要）: expr={} {}", ann.key(), e.getMessage());
            return md5OfArgs(joinPoint.getArgs());
        }
    }

    private String md5OfArgs(Object[] args) {
        return cn.hutool.crypto.digest.DigestUtil.md5Hex(Arrays.stream(args)
                .map(a -> a == null ? "null" : Objects.toString(a)).reduce("", (a, b) -> a + "|" + b));
    }
}
