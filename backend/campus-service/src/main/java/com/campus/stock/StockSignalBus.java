package com.campus.stock;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 阶段 20 · 信号总线：发布 / 订阅 / 实例内合并。
 *
 * <pre>
 *   写落库（任何实例）→ PUBLISH campus:stock:signal {id,v,remain}
 *       → 每个实例都收到（Pub/Sub 扇出）→ 本实例 50ms 窗口合并（同班只留最新版本）
 *       → L1 失效该课程 + 向本机订阅了该教学班的 SSE 连接推送
 * </pre>
 *
 * <p>要点：① 多实例广播不依赖"写请求落在哪个实例"——谁写谁发布、所有实例都订阅；
 * ② 合并窗口把"连接数 × 信号数"的扇出放大削一个数量级；
 * ③ 订阅回调只做内存写入，扇出在独立线程池（见 {@link StockSseRegistry}），不阻塞 Redis 监听线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSignalBus {

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final StockL1Cache l1Cache;
    private final StockSseRegistry sseRegistry;
    private final StockMetrics metrics;

    @Value("${campus.stock.signal-coalesce-ms:50}")
    private long coalesceMs;

    private RedisMessageListenerContainer container;
    private ScheduledExecutorService flusher;

    /** 待发送信号（实例内合并）：classId -> 最新一条 */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private record Pending(String json, Long courseId) {
    }

    /** classId -> courseId 本地小缓存（c2c 反查的命中缓存） */
    private final Map<Long, Long> classToCourse = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 发布（写后推送调用；失败只记日志）
    // ------------------------------------------------------------------

    public void publish(String signalJson) {
        try {
            redis.convertAndSend(CourseStockKeys.SIGNAL_CHANNEL, signalJson);
            metrics.published();
        } catch (Exception e) {
            log.warn("[名额同步] 信号发布失败（客户端将由轮询/重连兜底）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 订阅 + 合并 + 扇出
    // ------------------------------------------------------------------

    @PostConstruct
    public void start() {
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) ->
                onSignal(new String(message.getBody(), StandardCharsets.UTF_8));
        container.addMessageListener(listener, new ChannelTopic(CourseStockKeys.SIGNAL_CHANNEL));
        try {
            container.afterPropertiesSet();
            container.start();
        } catch (Exception e) {
            // 订阅启动失败不阻断应用：实时信号缺失，客户端由查询/轮询兜底
            log.error("[名额同步] 信号订阅启动失败（客户端将由轮询兜底）: {}", e.getMessage());
        }

        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stock-signal-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, coalesceMs, coalesceMs, TimeUnit.MILLISECONDS);
        log.info("[名额同步] 信号总线已启动（订阅 {}，合并窗口 {}ms）", CourseStockKeys.SIGNAL_CHANNEL, coalesceMs);
    }

    @PreDestroy
    public void stop() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        if (container != null) {
            try {
                container.destroy();
            } catch (Exception e) {
                log.warn("[名额同步] 信号订阅关闭异常: {}", e.getMessage());
            }
        }
    }

    /** 订阅回调（Redis 监听线程）：只解析 + 入合并表，不做任何 IO */
    private void onSignal(String json) {
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            Long classId = obj.getLong("id");
            if (classId == null) {
                return;
            }
            metrics.signalIn();
            Long courseId = classToCourse.computeIfAbsent(classId, this::loadCourseOf);
            pending.put(classId, new Pending(json, courseId));
        } catch (Exception e) {
            log.warn("[名额同步] 信号解析失败: {} {}", json, e.getMessage());
        }
    }

    /** 合并窗口到期：逐班取最新信号 → L1 失效 + SSE 扇出 */
    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, Pending> batch = new HashMap<>(pending);
        pending.clear();
        batch.forEach((classId, p) -> {
            l1Cache.invalidate(p.courseId());
            sseRegistry.fanout(classId, p.json());
        });
    }

    private Long loadCourseOf(Long classId) {
        try {
            Object v = redis.opsForHash().get(CourseStockKeys.CLASS_COURSE, String.valueOf(classId));
            if (v != null) {
                return Long.parseLong(String.valueOf(v));
            }
        } catch (Exception e) {
            log.warn("[名额同步] c2c 反查失败: classId={} {}", classId, e.getMessage());
        }
        return null;   // 未知课程：不失效 L1（TTL 兜底），仅按 classId 扇出
    }
}
