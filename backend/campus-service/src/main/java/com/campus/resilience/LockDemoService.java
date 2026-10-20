package com.campus.resilience;

import com.campus.framework.concurrent.CampusThreadPools;
import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁竞争现场演示（阶段 18）。
 *
 * <p>N 个线程同时抢同一把锁，临界区内做"共享计数器 +1"，并<b>检测互斥性违例</b>
 * （同时处于临界区的线程数 &gt; 1 的窗口数）——这是比"看起来没出问题"更硬的证据。</p>
 *
 * <ul>
 *   <li>{@code waitMs = 0}：只试一次 → 只有 1 个线程进入（其余立即放弃），演示"互斥"；</li>
 *   <li>{@code waitMs &gt; 0}：其余线程自旋等待 → 逐个串行进入，演示"排队"（总耗时 ≈ N × 临界区）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockDemoService {

    private static final String DEMO_LOCK_KEY = "campus:lock:demo:counter";
    private static final long DEMO_LOCK_TTL_MS = 5_000;
    /** 临界区耗时（毫秒）：放大后便于观察"串行排队"的总耗时 */
    private static final long CRITICAL_SECTION_MS = 5;

    private final CampusLock lock;
    private final CampusThreadPools threadPools;

    public Map<String, Object> run(int threads, long waitMs) {
        int n = Math.max(1, Math.min(threads, 1000));
        List<Integer> shared = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> times = new java.util.concurrent.CopyOnWriteArrayList<>();   // 每个线程的耗时（ms）
        AtomicInteger entered = new AtomicInteger();      // 进入临界区次数
        AtomicInteger timedOut = new AtomicInteger();     // 未抢到锁
        AtomicInteger violations = new AtomicInteger();   // 互斥性违例（同时进入 > 1）
        AtomicInteger inside = new AtomicInteger();       // 当前临界区内线程数
        AtomicInteger errors = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = threadPools.stressPool(n);

        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                ready.countDown();
                long t0 = System.currentTimeMillis();
                try {
                    go.await();
                    String token = lock.tryAcquire(DEMO_LOCK_KEY, waitMs, DEMO_LOCK_TTL_MS);
                    if (token == null) {
                        timedOut.incrementAndGet();
                        return;
                    }
                    try {
                        if (inside.incrementAndGet() > 1) {
                            violations.incrementAndGet();
                        }
                        entered.incrementAndGet();
                        shared.add(entered.get());
                        Thread.sleep(CRITICAL_SECTION_MS);
                        inside.decrementAndGet();
                    } finally {
                        lock.release(DEMO_LOCK_KEY, token);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    times.add(System.currentTimeMillis() - t0);
                    done.countDown();
                }
            });
        }

        long wall0 = System.currentTimeMillis();
        try {
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long wallMs = System.currentTimeMillis() - wall0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threads", n);
        m.put("waitMs", waitMs);
        m.put("entered", entered.get());
        m.put("timedOut", timedOut.get());
        m.put("violations", violations.get());
        m.put("errors", errors.get());
        m.put("wallMs", wallMs);
        m.put("pass", violations.get() == 0 && errors.get() == 0);
        m.put("times", times);
        m.put("lock", lock.stats());
        m.put("note", violations.get() == 0
                ? "互斥性违例 0：任意时刻临界区内至多 1 个线程（waitMs=0 时只有 1 个线程拿到锁）"
                : "出现互斥性违例 —— 锁失效，需要排查（不应发生）");
        log.info("[锁演示] {} 线程抢锁（waitMs={}）：进入 {}，超时 {}，违例 {}，耗时 {}ms",
                n, waitMs, entered.get(), timedOut.get(), violations.get(), wallMs);
        return m;
    }
}
