package com.campus.prededuct;

import com.campus.framework.lock.CampusLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * <b>Redis 预扣闸门（阶段 17）——本项目吞吐提升的核心改造。</b>
 *
 * <h3>它在做什么</h3>
 * <p>把"余量判定"从数据库前移到 Redis：请求先到 Redis 做一次 <b>Lua 原子"校验 + 预扣"</b>，
 * 判定不过的直接返回"已满"（成本 ≈ 一次内存往返），<b>根本不占用数据库连接</b>；
 * 只有放行的少量请求才进入 MySQL 事务，由条件更新（{@code WHERE booked<capacity}）做最终裁决。</p>
 *
 * <h3>与 12306 令牌桶的关键差别（面试重点）</h3>
 * <table>
 *   <tr><th></th><th>12306</th><th>本项目</th></tr>
 *   <tr><td>Redis 角色</td><td><b>唯一真相</b>（数据库无唯一索引兜底，靠自愈删桶）</td>
 *       <td><b>预计数</b>（真相始终在 MySQL，唯一索引 + 条件更新兜底）</td></tr>
 *   <tr><td>Redis 挂了</td><td>购票不可用（桶不存在无法取令牌）</td>
 *       <td>自动降级直通数据库（{@code -2} 分支），正确性零损失，只是回到改造前的性能</td></tr>
 *   <tr><td>Redis 漂移</td><td>删桶重建（全量刷新）</td>
 *       <td>对账纠偏（只把计数器拉回 DB 真值，不打断在途请求）</td></tr>
 * </table>
 *
 * <h3>三条防线（缺一不可）</h3>
 * <ol>
 *   <li><b>预扣</b>（{@link #tryDeduct}）：Lua 内"先全部校验、再逐个扣减"，多片预约天然
 *       全成或全败（对应 12306 的"区间扣减"语义）；</li>
 *   <li><b>回补</b>（{@link #releaseQuietly}）：数据库拒绝（时间冲突/真满）时把预扣加回去——
 *       没有回补就会"少卖"（Redis 扣了、库里其实没占）;</li>
 *   <li><b>对账自愈</b>（{@link #reconcileAll}）：每 30 秒比对 Redis 预计数与 DB 真值，
 *       偏差超阈值就把计数器拉回真值——兜住进程崩溃等导致的漏回补。</li>
 * </ol>
 *
 * <h3>诚实的边界</h3>
 * <p>预扣只解决"存量争抢"（名额/余量）；<b>用户个人的时间冲突检测</b>（96 格位图）与
 * "同一用户串行化"仍必须由数据库承担——那是"读后判定"，无法在 Redis 上做无条件化。
 * 所以本闸门的定位是<b>挡量</b>，不是替代事务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreDeductService {

    public static final String TYPE_CLASS = "CLASS";
    public static final String TYPE_SLOT = "SLOT";

    private static final String KEY_PREFIX = "campus:prededuct:";
    private static final String INIT_LOCK_PREFIX = "campus:lock:prededuct:init:";
    private static final String RECONCILE_LOCK = "campus:lock:prededuct:reconcile";
    /** 计数器 TTL：过期后由下一次请求按 DB 真值重新初始化（幂等、无副作用） */
    private static final long TTL_SECONDS = 600;
    private static final long INIT_LOCK_WAIT_MS = 200;
    private static final long INIT_LOCK_TTL_MS = 3_000;

    private final StringRedisTemplate redis;
    private final CampusLock lock;
    private final PreDeductTruth truth;

    /** 冗余比例：初始化时多放行的比例（0 = 严格等于真值）。生产建议 2%~5%，见 docs/09 */
    @Value("${campus.prededuct.redundancy-ratio:0}")
    private double redundancyRatio;
    /** 对账纠偏阈值下限（吸收"已预扣但未提交"的在途噪音，避免误纠） */
    @Value("${campus.prededuct.drift-threshold:5}")
    private int driftThreshold;
    /** 对账开关（压测时可临时关闭，避免 30 秒周期干扰现场数字） */
    @Value("${campus.prededuct.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    /** 活跃计数器注册表（对账/观测用） */
    private final Map<String, KeyMeta> registry = new ConcurrentHashMap<>();

    /**
     * 计数器元信息的 Redis 持久副本（Hash：key → "type|refId|resourceId|date|createdAt"）。
     *
     * <p>为什么需要它：注册表原本只在进程内存里，<b>服务重启后 Redis 里遗留的计数器就成了"无人认领"的状态</b>——
     * 对账不认识它，若它恰好是偏小值（如手工清库后残留的 0），闸门会持续误拦（最长到 TTL 600 秒）。
     * 把元信息随计数器一起写 Redis，重启后由首轮对账"认领 + 纠偏"，把误拦窗口从"最长 10 分钟"压到"重启后首轮对账（秒级）"。</p>
     */
    private static final String META_KEY = "campus:prededuct:meta";
    private volatile boolean registryRestored = false;

    /* ---------------- 指标（前端「🔥 预扣闸门」看板） ---------------- */
    private final AtomicLong passed = new AtomicLong();            // 放行（进入数据库）
    private final AtomicLong intercepted = new AtomicLong();       // Redis 拦截（余量不足，未碰 DB）
    private final AtomicLong passedNotInit = new AtomicLong();     // 未初始化 → 降级直通
    private final AtomicLong rolledBack = new AtomicLong();        // DB 拒绝后回补次数
    private final AtomicLong released = new AtomicLong();          // 退课/取消回补次数
    private final AtomicLong initCount = new AtomicLong();         // 计数器初始化次数
    private final AtomicLong reconcileRuns = new AtomicLong();
    private final AtomicLong reconcileFixes = new AtomicLong();
    private final AtomicLong reconcileDriftSum = new AtomicLong();
    private final AtomicLong reconcileLockSkips = new AtomicLong();
    private final AtomicLong degradeOnError = new AtomicLong();    // Redis 异常降级次数
    private final AtomicLong fastPathSingleLua = new AtomicLong(); // 阶段 19：合并脚本一次原子完成的次数
    private final AtomicLong fastPathSlow = new AtomicLong();      // 阶段 19：计数器未初始化 → 走慢路径（ensure+两态）次数

    /** 计数器元信息（对账与观测的最小信息集） */
    public record KeyMeta(String key, String type, Long refId, Long resourceId, LocalDate date,
                          String label, long createdAt) {
    }

    /** Lua：多 key 原子"先校验后扣减"——任一不足则整体不扣（跨时间片预约的全成或全败） */
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local amount = tonumber(ARGV[1])
            for i = 1, #KEYS do
              local cur = redis.call('GET', KEYS[i])
              if cur == false then return -2 end
              if tonumber(cur) < amount then return -1 end
            end
            for i = 1, #KEYS do
              redis.call('DECRBY', KEYS[i], amount)
            end
            return 1
            """, Long.class);

    /** Lua：回补（INCRBY；不设上限，超发由对账纠偏） */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local amount = tonumber(ARGV[1])
            for i = 1, #KEYS do
              redis.call('INCRBY', KEYS[i], amount)
            end
            return #KEYS
            """, Long.class);

    /**
     * Lua：<b>选课快路径</b>（阶段 19）——把"幂等两态 + 预扣"合并成一次原子（一组 1 次往返）。
     *
     * <pre>
     *   KEYS[1] = 预扣计数器（如 campus:prededuct:class:{classId}）
     *   KEYS[2] = 幂等键（如 campus:idem:course-select:{userId}:{classId}）
     *   ARGV[1] = 扣减量（选课=1）  ARGV[2] = 幂等 TTL（秒）
     *
     *   返回  1 = 放行（已置 CONSUMING + 已预扣）
     *        -1 = 名额不足（不占幂等标记——失败可换班重试）
     *        -2 = 计数器未初始化（不占幂等标记——交回 Java 走"ensure + 慢路径"，避免冷启动误判）
     *        -3 = 窗口内已成功（CONSUMED）  -4 = 并发处理中（CONSUMING）
     * </pre>
     *
     * <p>顺序刻意与阶段 18 一致：<b>幂等判定在预扣之前</b>——重复提交应得到 40060（"你已提交过"），
     * 而不是因为名额恰好变 0 被误报 40052。</p>
     */
    private static final DefaultRedisScript<Long> FAST_PATH_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('GET', KEYS[2])
            if state == 'CONSUMED' then return -3 end
            if state == 'CONSUMING' then return -4 end
            local stock = redis.call('GET', KEYS[1])
            if stock == false then return -2 end
            local amount = tonumber(ARGV[1])
            if tonumber(stock) < amount then return -1 end
            redis.call('SET', KEYS[2], 'CONSUMING', 'EX', ARGV[2])
            redis.call('DECRBY', KEYS[1], amount)
            return 1
            """, Long.class);

    // ------------------------------------------------------------------
    // key 构造
    // ------------------------------------------------------------------

    public static String classKey(Long classId) {
        return KEY_PREFIX + "class:" + classId;
    }

    public static String slotKey(Long slotId) {
        return KEY_PREFIX + "slot:" + slotId;
    }

    // ------------------------------------------------------------------
    // 初始化（懒加载 + 锁 + 双检）
    // ------------------------------------------------------------------

    /**
     * 确保计数器存在（不存在则按 DB 真值初始化）。幂等，可在每次请求前调用。
     *
     * <p>用分布式锁 + 双检保证"只有一个线程初始化"——否则并发首个请求会各读到不同真值、
     * 反复覆盖计数器（也可能把已扣减的计数器重置回满值，造成超放行）。</p>
     */
    public void ensureInitialized(String key, KeyMeta meta, Supplier<Integer> truthLoader) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                rememberKey(key, meta);
                return;
            }
            lock.executeWithLock(INIT_LOCK_PREFIX + key, INIT_LOCK_WAIT_MS, INIT_LOCK_TTL_MS, () -> {
                if (Boolean.TRUE.equals(redis.hasKey(key))) {   // 双检
                    rememberKey(key, meta);
                    return null;
                }
                Integer truthValue = truthLoader.get();
                if (truthValue == null) {
                    return null;   // 对象不存在（如教学班已删）：不建计数器，走降级直通
                }
                int init = truthValue + redundancy(truthValue);
                redis.opsForValue().set(key, String.valueOf(init), Duration.ofSeconds(TTL_SECONDS));
                rememberKey(key, meta);
                initCount.incrementAndGet();
                log.info("[预扣] 初始化 {} = {}（DB 真值 {}{}）", key, init, truthValue,
                        redundancy(truthValue) == 0 ? "" : " + 冗余 " + redundancy(truthValue));
                return null;
            });
        } catch (Exception e) {
            log.warn("[预扣] 初始化失败（本次走降级直通）: key={} {}", key, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 预扣 / 回补
    // ------------------------------------------------------------------

    /**
     * 原子预扣（多 key 全成或全败）。
     *
     * @param keys   计数器 key（一次预约的 1~N 个时间片，或 1 个教学班）
     * @param amount 每个 key 扣减量（通常 1）
     * @return {@link #OK} 放行 / {@link #INSUFFICIENT} 余量不足（Redis 拦截）/ {@link #NOT_INIT} 未初始化（降级直通）
     */
    public int tryDeduct(List<String> keys, int amount) {
        try {
            Long r = redis.execute(DEDUCT_SCRIPT, keys, String.valueOf(amount));
            if (r == null) {
                degradeOnError.incrementAndGet();
                passedNotInit.incrementAndGet();
                return NOT_INIT;
            }
            if (r == 1L) {
                passed.incrementAndGet();
                return OK;
            }
            if (r == -1L) {
                intercepted.incrementAndGet();
                return INSUFFICIENT;
            }
            passedNotInit.incrementAndGet();
            return NOT_INIT;
        } catch (Exception e) {
            // Redis 异常 → 降级直通（正确性由数据库兜底；Redis 只是加速器）
            degradeOnError.incrementAndGet();
            passedNotInit.incrementAndGet();
            log.warn("[预扣] Redis 异常，降级直通数据库: keys={} {}", keys, e.getMessage());
            return NOT_INIT;
        }
    }

    /**
     * <b>选课快路径（阶段 19）</b>：幂等两态 + 预扣合并为一次原子（见 {@link #FAST_PATH_SCRIPT}）。
     *
     * <p>对照阶段 18 的成功路径（{@code hasKey 检查 → 幂等两态 Lua → 预扣 Lua → 置已消费}，
     * 共 3~4 次 Redis 往返），快路径把它压到 <b>1 次脚本 + 1 次置已消费</b>。</p>
     *
     * @return {@link #FAST_PASS} 放行 / {@link #FAST_FULL} 满员 / {@link #FAST_NOT_INIT} 未初始化（走慢路径）
     *         / {@link #FAST_DUP_CONSUMED} 已成功重复 / {@link #FAST_DUP_CONSUMING} 处理中重复
     * @throws RuntimeException Redis 异常（已计入降级指标；由闸门统一降级直通数据库）
     */
    public int tryFastPath(String counterKey, String idemKey, long idemTtlSeconds, int amount) {
        try {
            Long r = redis.execute(FAST_PATH_SCRIPT, List.of(counterKey, idemKey),
                    String.valueOf(amount), String.valueOf(idemTtlSeconds));
            long v = r == null ? FAST_NOT_INIT : r;
            if (v == FAST_PASS) {
                passed.incrementAndGet();
                fastPathSingleLua.incrementAndGet();
                return FAST_PASS;
            }
            if (v == FAST_FULL) {
                intercepted.incrementAndGet();
                fastPathSingleLua.incrementAndGet();   // 满员拒绝也在同一次原子里完成
                return FAST_FULL;
            }
            if (v == FAST_NOT_INIT) {
                fastPathSlow.incrementAndGet();
                return FAST_NOT_INIT;
            }
            fastPathSingleLua.incrementAndGet();       // 重复提交拦截：一次原子完成
            return (int) v;
        } catch (Exception e) {
            degradeOnError.incrementAndGet();
            throw e;
        }
    }

    /** 回补（数据库拒绝时调用）；忽略一切异常——回补失败由对账兜底 */
    public void releaseQuietly(List<String> keys, int amount) {

        try {
            redis.execute(RELEASE_SCRIPT, keys, String.valueOf(amount));
            rolledBack.incrementAndGet();
        } catch (Exception e) {
            log.warn("[预扣] 回补失败（由对账纠偏兜底）: keys={} {}", keys, e.getMessage());
        }
    }

    /** 正常释放（退课/取消/爽约：库存真正回到池子） */
    public void release(List<String> keys, int amount) {
        try {
            redis.execute(RELEASE_SCRIPT, keys, String.valueOf(amount));
            released.incrementAndGet();
        } catch (Exception e) {
            log.warn("[预扣] 释放失败（由对账纠偏兜底）: keys={} {}", keys, e.getMessage());
        }
    }

    /**
     * "确保初始化 + 预扣"的组合入口（业务闸门用）。
     *
     * <p>为什么要重试：冷启动/计数器过期的第一个请求潮里，只有一个线程拿到初始化锁，
     * 其余线程会读到"计数器不存在"。策略是<b>短暂重试 3 次（40ms 间隔）</b>——
     * 让初始化完成后再判定；仍不可用才降级直通数据库（宁可少挡，不可误拒）。</p>
     *
     * @param keys    预扣的 key 列表（多片预约：全部片）
     * @param metas   与 keys 一一对应的元信息
     * @param loaders 与 keys 一一对应的真值加载器
     */
    public int ensureThenDeduct(List<String> keys, List<KeyMeta> metas,
                                List<Supplier<Integer>> loaders, int amount) {
        if (keys.isEmpty()) {
            return OK;   // 无需预扣（如无匹配时间片：交由业务层给出准确错误）
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            for (int i = 0; i < keys.size(); i++) {
                ensureInitialized(keys.get(i), metas.get(i), loaders.get(i));
            }
            int r = tryDeduct(keys, amount);
            if (r != NOT_INIT) {
                return r;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 连续 3 次拿不到计数器（Redis 异常/持续竞争）：降级直通，正确性交给数据库
        return NOT_INIT;
    }

    // ------------------------------------------------------------------
    // 对账自愈
    // ------------------------------------------------------------------

    /**
     * 对账一轮：把每个活跃计数器与 DB 真值比对，偏差超阈值则拉回真值。
     *
     * <p>为什么要阈值而不是"必须相等"：请求在"已预扣、未提交"的窗口里，
     * Redis 天然比 DB 少 1~N（N = 在途并发）。阈值吸收这部分噪音，只在真正漂移时纠偏。</p>
     */
    public Map<String, Object> reconcileAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!reconcileEnabled) {
            result.put("skipped", "对账开关已关闭（campus.prededuct.reconcile-enabled=false）");
            return result;
        }
        restoreRegistryIfNeeded();   // 服务重启后：认领 Redis 里遗留的计数器元信息
        String token = lock.tryAcquire(RECONCILE_LOCK, 0, 25_000);
        if (token == null) {
            reconcileLockSkips.incrementAndGet();
            result.put("skipped", "另一实例正在对账（分布式锁）");
            return result;
        }
        int checked = 0;
        int fixed = 0;
        long driftSum = 0;
        try {
            reconcileRuns.incrementAndGet();
            for (Map.Entry<String, KeyMeta> entry : new ArrayList<>(registry.entrySet())) {
                String key = entry.getKey();
                KeyMeta meta = entry.getValue();
                String value = redis.opsForValue().get(key);
                if (value == null) {
                    registry.remove(key);      // 计数器已过期：不重建（按需懒加载）
                    continue;
                }
                Integer truthValue = truth.truthOf(meta);
                if (truthValue == null) {
                    registry.remove(key);
                    continue;
                }
                checked++;
                int expect = truthValue + redundancy(truthValue);
                int drift = Integer.parseInt(value) - expect;
                if (Math.abs(drift) > thresholdFor(truthValue)) {
                    redis.opsForValue().set(key, String.valueOf(expect), Duration.ofSeconds(TTL_SECONDS));
                    fixed++;
                    driftSum += Math.abs(drift);
                    log.warn("[预扣·对账] 纠偏 {}：Redis={} → DB 真值 {}（偏差 {}，超过阈值 {}）",
                            key, value, expect, drift, thresholdFor(truthValue));
                }
            }
            reconcileFixes.addAndGet(fixed);
            reconcileDriftSum.addAndGet(driftSum);
        } catch (Exception e) {
            log.warn("[预扣·对账] 异常: {}", e.getMessage());
        } finally {
            lock.release(RECONCILE_LOCK, token);
        }
        result.put("checked", checked);
        result.put("fixed", fixed);
        result.put("driftSum", driftSum);
        return result;
    }

    // ------------------------------------------------------------------
    // 元信息持久化 / 重启后认领（见 META_KEY 注释）
    // ------------------------------------------------------------------

    /**
     * 登记计数器：进注册表；<b>本进程首次见到该 key 时</b>顺手把元信息落 Redis。
     *
     * <p>"首次才落"是刻意的：既覆盖"新建计数器"，也覆盖"已存在但缺元信息"（如旧版本遗留），
     * 又把写入压到"每个 key 每进程一次"，不碰热路径。</p>
     */
    private void rememberKey(String key, KeyMeta meta) {
        if (registry.put(key, meta) == null) {
            persistMeta(key, meta);
        }
    }

    private void persistMeta(String key, KeyMeta meta) {
        try {
            String value = String.join("|", meta.type(), String.valueOf(meta.refId()),
                    String.valueOf(meta.resourceId()), String.valueOf(meta.date()),
                    String.valueOf(meta.createdAt()));
            redis.opsForHash().put(META_KEY, key, value);
            redis.expire(META_KEY, Duration.ofSeconds(TTL_SECONDS * 2));
        } catch (Exception e) {
            log.debug("[预扣] 元信息落盘失败（不影响功能）: key={} {}", key, e.getMessage());
        }
    }

    /**
     * 服务重启后首轮对账前调用：把 Redis 里"遗留计数器"的元信息认领回注册表。
     *
     * <p>没有这一步，重启后旧计数器就是"无人认领"状态——若它是偏小值（典型：手工清库后残留的 0），
     * 闸门会持续误拦，直到 TTL(600 秒) 过期。认领 + 首轮对账把窗口压到秒级。</p>
     */
    private void restoreRegistryIfNeeded() {
        if (registryRestored) {
            return;
        }
        registryRestored = true;
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(META_KEY);
            int restored = 0;
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                    redis.opsForHash().delete(META_KEY, key);   // 计数器已过期 → 元信息一并清理
                    continue;
                }
                KeyMeta meta = parseMeta(key, String.valueOf(e.getValue()));
                if (meta != null) {
                    registry.putIfAbsent(key, meta);
                    restored++;
                }
            }
            if (restored > 0) {
                log.info("[预扣] 服务重启后认领 {} 个遗留计数器（首轮对账将校验并纠偏）", restored);
            }
        } catch (Exception e) {
            registryRestored = false;   // 失败允许下一轮重试
            log.warn("[预扣] 认领遗留计数器失败: {}", e.getMessage());
        }
    }

    private KeyMeta parseMeta(String key, String value) {
        try {
            String[] p = value.split("\\|", 5);
            return new KeyMeta(key, p[0], Long.parseLong(p[1]),
                    "null".equals(p[2]) ? null : Long.parseLong(p[2]),
                    "null".equals(p[3]) ? null : LocalDate.parse(p[3]),
                    "", Long.parseLong(p[4]));
        } catch (Exception e) {
            log.warn("[预扣] 元信息解析失败（忽略该条）: key={} value={}", key, value);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 观测（前端页面）
    // ------------------------------------------------------------------

    /** 活跃计数器现场：Redis 预计数 vs DB 真值 vs 偏差（每个 key 都现场查一次真值） */
    public List<Map<String, Object>> state() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, KeyMeta> entry : registry.entrySet()) {
            String key = entry.getKey();
            KeyMeta meta = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("type", meta.type());
            row.put("refId", meta.refId());
            row.put("label", truth.labelOf(meta));
            String value = redis.opsForValue().get(key);
            row.put("redisRemaining", value == null ? null : Integer.parseInt(value));
            Integer truthValue = truth.truthOf(meta);
            row.put("dbRemaining", truthValue);
            if (value != null && truthValue != null) {
                row.put("drift", Integer.parseInt(value) - truthValue);
                row.put("inFlight", Math.max(0, Math.abs(Integer.parseInt(value) - truthValue)));
            }
            list.add(row);
        }
        list.sort((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))));
        return list;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", passed.get());
        m.put("intercepted", intercepted.get());
        m.put("passedNotInit", passedNotInit.get());
        m.put("rolledBack", rolledBack.get());
        m.put("released", released.get());
        m.put("initCount", initCount.get());
        m.put("reconcileRuns", reconcileRuns.get());
        m.put("reconcileFixes", reconcileFixes.get());
        m.put("reconcileDriftSum", reconcileDriftSum.get());
        m.put("reconcileLockSkips", reconcileLockSkips.get());
        m.put("degradeOnError", degradeOnError.get());
        m.put("fastPathSingleLua", fastPathSingleLua.get());
        m.put("fastPathSlow", fastPathSlow.get());
        m.put("trackedKeys", registry.size());
        long total = passed.get() + intercepted.get();
        m.put("interceptRate", total == 0 ? 0d : Math.round(intercepted.get() * 10000.0 / total) / 100.0);
        m.put("redundancyRatio", redundancyRatio);
        m.put("driftThreshold", driftThreshold);
        m.put("ttlSeconds", TTL_SECONDS);
        return m;
    }

    public int trackedKeyCount() {
        return registry.size();
    }

    /** 供压测/演示：清空某教学班的计数器（让它下次请求按 DB 真值重建） */
    public void dropCounter(String key) {
        redis.delete(key);
        redis.opsForHash().delete(META_KEY, key);
        registry.remove(key);
    }

    // ------------------------------------------------------------------

    public static final int OK = 1;
    public static final int INSUFFICIENT = -1;
    public static final int NOT_INIT = -2;

    /* 阶段 19：选课快路径（合并脚本）返回码 */
    public static final int FAST_PASS = 1;
    public static final int FAST_FULL = -1;
    public static final int FAST_NOT_INIT = -2;
    public static final int FAST_DUP_CONSUMED = -3;
    public static final int FAST_DUP_CONSUMING = -4;

    private int redundancy(int truthValue) {
        if (redundancyRatio <= 0) {
            return 0;
        }
        return (int) Math.ceil(truthValue * redundancyRatio);
    }

    /** 偏差阈值：至少 {@code driftThreshold}，且不小于真值的 20%（吸收在途噪音） */
    private int thresholdFor(int truthValue) {
        return Math.max(driftThreshold, (int) Math.ceil(truthValue * 0.2));
    }
}
