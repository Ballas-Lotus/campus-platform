package com.campus.stock;

/**
 * 选课名额结构 / 频道 / 台账的 Redis key 规约（阶段 20 读侧同步 → 阶段 21 受理制）。
 *
 * <p><b>阶段 21 语义升级</b>：{@code campus:stock:{courseId}} 从"展示读模型"升级为
 * <b>选课真相</b>——受理（ACCEPT）在此原子完成"边界检查 + 扣减 + 版本 +1 + 写台账"，
 * 数据库退居"迟到的记录者"（MQ 异步落库，失败走补偿 + 修正信号）。
 * 因此新增两类结构：受理台账（pending）与用户已选集合（sel），
 * 以及"真相 = capacity − selected(DB) − Σ未决受理"的新对账口径。</p>
 *
 * <p>结构一览：</p>
 * <pre>
 *   campus:stock:{courseId}     Hash  TTL 6h（写时刷新）
 *       m:{classId} -> "120"        教学班容量
 *       c:{classId} -> "37:42"      可受理余量:版本号（复合值，受理/补偿/纠偏都单脚本原子写）
 *       @cver       -> "17"         课程级版本（L1 键 / 客户端去重辅助）
 *   campus:stock:pend:{courseId} Hash  TTL 24h   受理台账（见 PEND_PREFIX 注释）
 *   campus:stock:c2c            Hash  TTL 24h   信号反查：classId -> courseId
 *   campus:sel:{userId}         Set   TTL 24h   已选教学班（快速已选检查，惰性初始化）
 *   频道 campus:stock:signal     Pub/Sub         多实例广播（谁受理/落库谁发布）
 * </pre>
 */
public final class CourseStockKeys {

    private CourseStockKeys() {
    }

    /** 每课程一个 Hash 的前缀 */
    public static final String PREFIX = "campus:stock:";
    /** classId -> courseId（信号反查课程；重建/推送/受理时顺带维护） */
    public static final String CLASS_COURSE = PREFIX + "c2c";
    /** 信号广播频道 */
    public static final String SIGNAL_CHANNEL = PREFIX + "signal";
    /** 课程级版本字段 */
    public static final String CVER_FIELD = "@cver";
    /** 容量字段前缀 */
    public static final String CAPACITY_PREFIX = "m:";
    /** 余量（复合值 remain:ver）字段前缀 */
    public static final String STOCK_PREFIX = "c:";

    // ---------------- 阶段 21（受理制）新增 ----------------

    /**
     * 受理台账前缀：{@code campus:stock:pend:{courseId}} Hash，
     * field = {@code {classId}:{opId}} → value = {@code "{delta}|{acceptTs}|{userId}"}。
     *
     * <p>它在"Redis 即真相"模型里的三个用途：
     * ① 对账真值 = capacity − selected(DB) − Σ未决受理；
     * ② 消费端判定"该受理是否已被补偿过"（补偿脚本以 HEXISTS 为原子闸门）；
     * ③ 超时裁决：超过时限仍未落库的受理按 op_id 查库决定"清账 or 补偿"。</p>
     */
    public static final String PEND_PREFIX = PREFIX + "pend:";

    /**
     * 用户已选集合：{@code campus:sel:{userId}} Set（TTL 24h，惰性按 DB 初始化）。
     * 受理闸门的"快速已选检查"——防"受理之后才被数据库判重复"的无效受理与补偿抖动。
     * 它只是加速器：集合缺失时走惰性初始化，初始化失败则降级到同步老链路（正确性始终在 DB）。
     */
    public static final String SEL_PREFIX = "campus:sel:";

    public static String stock(Long courseId) {
        return PREFIX + courseId;
    }

    public static String pend(Long courseId) {
        return PEND_PREFIX + courseId;
    }

    public static String sel(Long userId) {
        return SEL_PREFIX + userId;
    }

    /** 台账 field：{classId}:{opId} */
    public static String pendField(Long classId, String opId) {
        return classId + ":" + opId;
    }

    /** 受理重建互斥锁（与 L3 重建锁共用命名空间风格） */
    public static String selLock(Long userId) {
        return "campus:lock:sel:" + userId;
    }

    /** 重建互斥锁（与缓存门面的锁前缀风格一致） */
    public static String lock(Long courseId) {
        return "campus:lock:stock:" + courseId;
    }

    public static String capacityField(Long classId) {
        return CAPACITY_PREFIX + classId;
    }

    public static String stockField(Long classId) {
        return STOCK_PREFIX + classId;
    }

    /** 复合值：余量:版本 */
    public static String stockValue(int remain, long version) {
        return remain + ":" + version;
    }

    /**
     * 信号体——<b>严格三字段，禁止扩展</b>（推信号不推数据）：
     * {@code {"id":<教学班id>,"v":<版本>,"remain":<剩余>}}。
     * remain 仅供客户端乐观渲染，任何"能不能选上"的判定一律以服务端为准。
     */
    public static String signalJson(Long classId, long version, int remain) {
        return "{\"id\":" + classId + ",\"v\":" + version + ",\"remain\":" + remain + "}";
    }
}
