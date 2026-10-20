package com.campus.stock;

import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 阶段 20 · SSE 连接注册表（本实例持有的长连接）。
 *
 * <p>职责：连接注册/摘除（完成/超时/异常回调 + 心跳探测）、按教学班扇出、
 * 上限保护（实例/用户/单连接班级数）与背压（慢连接写失败即摘除，不拖累其他连接）。</p>
 *
 * <p>多实例语义：每个实例只持有"连到自己"的连接；信号经 Redis Pub/Sub 广播
 * （见 {@link StockSignalBus}），各实例推各自的连接——不需要粘性会话。</p>
 */
@Slf4j
@Component
public class StockSseRegistry {

    public static final String EVT_READY = "ready";
    public static final String EVT_STOCK = "stock";
    /**
     * 阶段 21（受理制）：受理结果回执（按用户定向推送，不广播）——
     * {@code {"classId":X,"ok":true|false,"reason":"..."}}。
     * 成功 = 已落库（本地"已选"标记从此为准）；失败 = 已拒绝且名额已退回（客户端回滚渲染）。
     */
    public static final String EVT_SETTLED = "settled";

    private record Conn(String id, Long userId, Set<Long> classIds, SseEmitter emitter) {
    }

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byClass = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byUser = new ConcurrentHashMap<>();
    private final StockMetrics metrics;

    private final ExecutorService fanoutPool = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "stock-sse-fanout");
                t.setDaemon(true);
                return t;
            });

    @Value("${campus.stock.sse.emitter-timeout-ms:1800000}")
    private long emitterTimeoutMs;

    @Value("${campus.stock.sse.max-conns-per-instance:5000}")
    private int maxConnsPerInstance;

    @Value("${campus.stock.sse.max-conns-per-user:3}")
    private int maxConnsPerUser;

    @Value("${campus.stock.sse.max-classes-per-conn:200}")
    private int maxClassesPerConn;

    public StockSseRegistry(StockMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 注册一条 SSE 连接（订阅若干课程 → 展开为教学班集合）。
     * 超限抛业务异常（前端据此自动降级为轮询）。
     */
    public SseEmitter register(Long userId, Set<Long> courseIds, Set<Long> classIds) {
        if (conns.size() >= maxConnsPerInstance) {
            throw new ClientException("SSE 实例连接数超限: " + conns.size(),
                    "实时连接数已达上限，已自动切换为轮询模式", BaseErrorCode.SERVICE_ERROR);
        }
        Set<String> mine = byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        if (mine.size() >= maxConnsPerUser) {
            throw new ClientException("单用户 SSE 连接数超限: userId=" + userId,
                    "实时连接数已达上限，已自动切换为轮询模式", BaseErrorCode.SERVICE_ERROR);
        }
        if (classIds.size() > maxClassesPerConn) {
            throw new ClientException("单连接订阅班级数超限: " + classIds.size(),
                    "订阅的课程过多，请缩小范围后重试", BaseErrorCode.SERVICE_ERROR);
        }

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        String id = UUID.randomUUID().toString();
        Conn conn = new Conn(id, userId, classIds, emitter);
        conns.put(id, conn);
        mine.add(id);
        classIds.forEach(c -> byClass.computeIfAbsent(c, k -> ConcurrentHashMap.newKeySet()).add(id));

        Runnable cleanup = () -> unregister(conn);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name(EVT_READY)
                    .data("{\"courses\":" + courseIds + ",\"ts\":" + System.currentTimeMillis() + "}"));
        } catch (Exception e) {
            cleanup.run();
            throw new ClientException("SSE 连接初始化失败: " + e.getMessage(),
                    "实时连接建立失败，已自动切换为轮询模式", BaseErrorCode.SERVICE_ERROR);
        }
        return emitter;
    }

    /**
     * 阶段 21（受理制）：向指定用户的<b>全部本机连接</b>定向推送（与订阅的课程无关）——
     * 用于受理结果回执（settled）。用户不在线时静默丢弃：客户端有"超时兜底刷新"。
     */
    public void pushToUser(Long userId, String event, String json) {
        Set<String> ids = byUser.get(userId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int sent = 0;
        for (String connId : ids) {
            Conn c = conns.get(connId);
            if (c == null) {
                continue;
            }
            sent++;
            fanoutPool.execute(() -> send(c, event, json));
        }
        if (sent > 0) {
            metrics.fanout(sent);
        }
    }

    /** 向订阅了该教学班的全部本机连接扇出（独立线程池 + 失败摘除） */
    public void fanout(Long classId, String json) {
        Set<String> ids = byClass.get(classId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int sent = 0;
        for (String connId : ids) {
            Conn c = conns.get(connId);
            if (c == null) {
                continue;
            }
            sent++;
            fanoutPool.execute(() -> send(c, EVT_STOCK, json));
        }
        if (sent > 0) {
            metrics.fanout(sent);
        }
    }

    private void send(Conn c, String event, String data) {
        try {
            c.emitter().send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            metrics.fanoutDropped();
            unregister(c);
            try {
                c.emitter().complete();
            } catch (Exception ignore) {
                // 已断开
            }
        }
    }

    private void unregister(Conn c) {
        if (conns.remove(c.id()) == null) {
            return;
        }
        Set<String> mine = byUser.get(c.userId());
        if (mine != null) {
            mine.remove(c.id());
            if (mine.isEmpty()) {
                byUser.remove(c.userId(), mine);
            }
        }
        for (Long classId : c.classIds()) {
            Set<String> set = byClass.get(classId);
            if (set != null) {
                set.remove(c.id());
                if (set.isEmpty()) {
                    byClass.remove(classId, set);
                }
            }
        }
    }

    /** 心跳：注释帧不触发客户端 onmessage，仅防中间层空闲断连 */
    @Scheduled(fixedRateString = "${campus.stock.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        for (Conn c : conns.values()) {
            fanoutPool.execute(() -> {
                try {
                    c.emitter().send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    unregister(c);
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        fanoutPool.shutdownNow();
    }

    /** 观测快照 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conns", conns.size());
        m.put("users", byUser.size());
        m.put("subscribedClasses", byClass.size());
        m.put("fanoutQueue", ((ThreadPoolExecutor) fanoutPool).getQueue().size());
        return m;
    }
}
