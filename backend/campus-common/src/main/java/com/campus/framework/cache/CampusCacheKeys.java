package com.campus.framework.cache;

import cn.hutool.crypto.digest.DigestUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存 key 规约与 TTL 表（阶段 16）。
 *
 * <p>命名约定：{@code campus:cache:{域}:{标识}}——与已有 {@code campus:penalty:*}、
 * {@code campus:mq:idem:*} 风格一致，便于 {@code redis-cli SCAN} 按域排查。</p>
 *
 * <p>TTL 的取值逻辑（"按数据变化速度定 TTL"）：</p>
 * <ul>
 *   <li><b>可用性类（timeline/余量）</b>——变化最快，30 秒，且每次写入主动失效；</li>
 *   <li><b>目录/列表类（资源、课程）</b>——变化慢，60 秒；</li>
 *   <li><b>静态/动态分离（阶段 19）</b>——教学班拆成"静态信息（10 分钟）"与
 *       "动态余量 Hash（120 秒，写路径 HINCRBY 增量维护，miss/过期按 DB 真值重建）"：
 *       静态的拉长 TTL，动态的短 TTL + 增量更新 + 对账自愈；</li>
 *   <li><b>空值回填</b>——30 秒（见 {@link RedisCampusCache}），防穿透但不长期占位。</li>
 * </ul>
 *
 * <p>列表类 key 的参数部分做 MD5：分页 + 筛选条件的组合空间大、还可能带中文关键字，
 * 直接拼进 key 会又长又难读——摘要后 key 长度恒定，命中率不受影响。</p>
 */
public final class CampusCacheKeys {

    /** 统一前缀（SCAN 与统计口径） */
    public static final String PREFIX = "campus:cache:";

    /** 空值回填标记（防穿透） */
    public static final String NULL_MARKER = "__NULL__";

    /* ---------------- TTL（秒） ---------------- */
    public static final long TTL_SLOTS = 30;
    public static final long TTL_RESOURCE_LIST = 60;
    public static final long TTL_OVERVIEW = 30;
    public static final long TTL_COURSE_LIST = 60;
    /** 阶段 19：教学班静态信息（名称/教师/时间/容量）——变化极慢，拉长到 10 分钟 */
    public static final long TTL_CLASS_INFO = 600;
    /** 阶段 19：教学班动态余量 Hash——写路径增量维护；TTL 到期即按 DB 真值重建（顺带自愈漂移） */
    public static final long TTL_CLASS_STOCK = 120;
    public static final long TTL_LECTURES = 60;

    /* ---------------- key 构造 ---------------- */

    /** 时间片可用性：{@code campus:cache:slots:{resourceId}:{date}} */
    public static String slots(Long resourceId, LocalDate date) {
        return PREFIX + "slots:" + resourceId + ":" + date;
    }

    /** 时间片前缀（按资源批量失效用） */
    public static String slotsPrefix(Long resourceId) {
        return PREFIX + "slots:" + resourceId + ":";
    }

    /** 资源列表：{@code campus:cache:resources:{md5(筛选参数)}} */
    public static String resourceList(String resourceType, String campus, String keyword,
                                      long pageNum, long pageSize) {
        return PREFIX + "resources:" + md5(String.join("|",
                nvl(resourceType), nvl(campus), nvl(keyword), pageNum + "", pageSize + ""));
    }

    public static String resourceListPrefix() {
        return PREFIX + "resources:";
    }

    /** 课程列表：{@code campus:cache:courses:{md5(筛选参数)}} */
    public static String courseList(String college, String courseType, String keyword,
                                    long pageNum, long pageSize) {
        return PREFIX + "courses:" + md5(String.join("|",
                nvl(college), nvl(courseType), nvl(keyword), pageNum + "", pageSize + ""));
    }

    public static String courseListPrefix() {
        return PREFIX + "courses:";
    }

    /**
     * 教学班<b>静态信息</b>（阶段 19 起：不含 selected/available，余量走 {@link #classStock}）：
     * {@code campus:cache:classes:{courseId}}
     */
    public static String courseClasses(Long courseId) {
        return PREFIX + "classes:" + courseId;
    }

    public static String courseClassesPrefix() {
        return PREFIX + "classes:";
    }

    /**
     * 教学班<b>动态余量</b>（阶段 19）：{@code campus:cache:class_stock:{courseId}}
     * ——Hash 结构（field = classId，value = 剩余名额），写路径 HINCRBY 增量维护。
     */
    public static String classStock(Long courseId) {
        return PREFIX + "class_stock:" + courseId;
    }

    public static String classStockPrefix() {
        return PREFIX + "class_stock:";
    }

    /** 讲座列表：{@code campus:cache:lectures:{md5(筛选参数)}} */
    public static String lectures(String campus, String keyword, long pageNum, long pageSize) {
        return PREFIX + "lectures:" + md5(String.join("|",
                nvl(campus), nvl(keyword), pageNum + "", pageSize + ""));
    }

    public static String lecturesPrefix() {
        return PREFIX + "lectures:";
    }

    /** 工作台概览统计 */
    public static final String OVERVIEW = PREFIX + "overview";

    /** 概览失效（前缀区分：概览是精确 key，单独给出） */
    public static String overviewKey() {
        return OVERVIEW;
    }

    /* ---------------- 规则表（前端展示"缓存了什么"） ---------------- */

    public static List<Map<String, Object>> rules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("时间片可用性", "campus:cache:slots:{resourceId}:{date}", TTL_SLOTS,
                "预约页每次点开资源都查——最热的读路径；写预约/取消后主动失效"));
        rules.add(rule("资源列表", "campus:cache:resources:{md5(筛选参数)}", TTL_RESOURCE_LIST,
                "按类型/校区/关键字分页；写操作少，短 TTL 足够"));
        rules.add(rule("课程列表", "campus:cache:courses:{md5(筛选参数)}", TTL_COURSE_LIST,
                "课程目录 + 教学班余量聚合；'我的已选'标记不进缓存（用户态分离）"));
        rules.add(rule("教学班静态信息", "campus:cache:classes:{courseId}", TTL_CLASS_INFO,
                "名称/教师/时间/容量——变化极慢，长 TTL；选课/退课不再失效它（阶段 19）"));
        rules.add(rule("教学班动态余量（Hash）", "campus:cache:class_stock:{courseId}", TTL_CLASS_STOCK,
                "field=classId，value=剩余名额；写路径 HINCRBY 增量维护（阶段 19），miss/过期按 DB 真值重建，30 秒对账纠偏"));
        rules.add(rule("讲座列表", "campus:cache:lectures:{md5(筛选参数)}", TTL_LECTURES,
                "可报名/未开放分组；报名后主动失效"));
        rules.add(rule("工作台概览", "campus:cache:overview", TTL_OVERVIEW,
                "多表 count 汇总，最适合缓存；30 秒 TTL 兜底"));
        return rules;
    }

    private static Map<String, Object> rule(String name, String pattern, long ttl, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pattern", pattern);
        m.put("ttlSeconds", ttl);
        m.put("note", note);
        return m;
    }

    private static String md5(String s) {
        return DigestUtil.md5Hex(s);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private CampusCacheKeys() {
    }
}
