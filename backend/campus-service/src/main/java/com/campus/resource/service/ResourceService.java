package com.campus.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.cache.CacheWarmup;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.common.result.PageRespDTO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.framework.cache.CampusBloomFilter;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.lecture.dao.entity.LectureDO;
import com.campus.lecture.dao.mapper.LectureMapper;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.entity.ResourceSlotDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import com.campus.resource.dao.mapper.ResourceSlotMapper;
import com.campus.resource.dto.resp.ResourceRespDTO;
import com.campus.resource.dto.resp.SlotRespDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一资源与时间片查询（四个业务共用）。
 *
 * <p><b>阶段 16 · 缓存化</b>：本服务的三个读方法都是"读多写少 + 参数空间有限"的典型缓存对象：</p>
 * <ul>
 *   <li>{@link #slots} 时间片可用性——最热的读路径（每次点开资源都查），
 *       走 {@code safeGet}（防击穿）+ <b>布隆过滤器前置拦截</b>（防穿透）；</li>
 *   <li>{@link #list} 资源列表——按筛选参数缓存（TTL 60s），走普通读透；</li>
 *   <li>{@link #overview} 工作台概览——多表 count 汇总，单 key 热点，走 {@code safeGet}。</li>
 * </ul>
 *
 * <p><b>写后失效</b>由写路径负责（预约创建/取消/爽约 → 失效对应资源的 slots；
 * 见 ReservationService、CourseService）。缓存本身不做写穿透。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final LectureMapper lectureMapper;
    private final CourseClassMapper courseClassMapper;

    /** 阶段 16：缓存门面 + 布隆过滤器 */
    private final CampusCache cache;
    private final CampusBloomFilter bloomFilter;

    /**
     * 资源列表：按类型 / 校区 / 关键字（名称或位置）筛选，分页返回。
     */
    public PageRespDTO<ResourceRespDTO> list(String resourceType, String campus, String keyword,
                                             long pageNum, long pageSize) {
        String key = CampusCacheKeys.resourceList(resourceType, campus, keyword, pageNum, pageSize);
        return cache.get(key, new TypeReference<>() {
        }, CampusCacheKeys.TTL_RESOURCE_LIST, () -> listFromDb(resourceType, campus, keyword, pageNum, pageSize));
    }

    private PageRespDTO<ResourceRespDTO> listFromDb(String resourceType, String campus, String keyword,
                                                    long pageNum, long pageSize) {
        LambdaQueryWrapper<ResourceDO> wrapper = Wrappers.lambdaQuery(ResourceDO.class)
                .eq(StringUtils.hasText(resourceType), ResourceDO::getResourceType, resourceType)
                .eq(StringUtils.hasText(campus), ResourceDO::getCampus, campus)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(ResourceDO::getName, keyword)
                        .or()
                        .like(ResourceDO::getLocation, keyword))
                .orderByAsc(ResourceDO::getId);
        Page<ResourceDO> page = resourceMapper.selectPage(Page.of(pageNum, pageSize), wrapper);
        List<ResourceRespDTO> list = page.getRecords().stream()
                .map(this::toResp)
                .collect(Collectors.toList());
        return new PageRespDTO<>(page.getTotal(), list);
    }

    /**
     * 时间片可用性：某资源某天的片列表（命中 uk_resource_slot 唯一索引前缀）。
     *
     * <p>两重防护：① <b>布隆过滤器</b>——不存在的资源 id 直接拒绝，一次 DB/Redis 都不查；
     * ② <b>safeGet</b>——热点 key（某场地全天格子）过期瞬间只放一个线程回源，防击穿。</p>
     */
    public List<SlotRespDTO> slots(Long resourceId, LocalDate date) {
        if (resourceId != null && !bloomFilter.mightContain(CacheWarmup.BLOOM_RESOURCE, String.valueOf(resourceId))) {
            // 布隆说"一定不存在" → 无需查缓存/查库（布隆的误判只会"误判存在"，不会"误判不存在"）
            log.info("[缓存] 布隆拦截不存在资源: resourceId={}（未查库）", resourceId);
            throw new ClientException("资源不存在（布隆过滤器拦截）: " + resourceId,
                    "资源不存在", BaseErrorCode.NOT_FOUND);
        }
        return cache.safeGet(CampusCacheKeys.slots(resourceId, date), new TypeReference<>() {
        }, CampusCacheKeys.TTL_SLOTS, () -> slotsFromDb(resourceId, date));
    }

    /** 绕缓存直查（对照演示/强一致读）——前端「🚀 缓存」页的"有缓存 vs 无缓存"用 */
    public List<SlotRespDTO> slotsDirect(Long resourceId, LocalDate date) {
        return slotsFromDb(resourceId, date);
    }

    private List<SlotRespDTO> slotsFromDb(Long resourceId, LocalDate date) {
        ResourceDO resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new ClientException(BaseErrorCode.NOT_FOUND);
        }
        List<ResourceSlotDO> slots = resourceSlotMapper.selectList(
                Wrappers.lambdaQuery(ResourceSlotDO.class)
                        .eq(ResourceSlotDO::getResourceId, resourceId)
                        .eq(ResourceSlotDO::getSlotDate, date)
                        .orderByAsc(ResourceSlotDO::getStartTime));
        return slots.stream().map(s -> SlotRespDTO.builder()
                        .id(s.getId())
                        .startTime(s.getStartTime())
                        .endTime(s.getEndTime())
                        .capacity(s.getCapacity())
                        .booked(s.getBooked())
                        .available(Math.max(0, s.getCapacity() - s.getBooked()))
                        .status(s.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 工作台概览：四类资源与讲座/课程的数量统计。
     */
    public Map<String, Long> overview() {
        return cache.safeGet(CampusCacheKeys.overviewKey(), new TypeReference<>() {
        }, CampusCacheKeys.TTL_OVERVIEW, this::overviewFromDb);
    }

    /** 绕缓存直查概览（对照演示用；背后是 6 次 count 聚合） */
    public Map<String, Long> overviewDirect() {
        return overviewFromDb();
    }

    private Map<String, Long> overviewFromDb() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("librarySeat", resourceMapper.selectCount(Wrappers.lambdaQuery(ResourceDO.class)
                .eq(ResourceDO::getResourceType, "LIBRARY_SEAT")));
        stats.put("venue", resourceMapper.selectCount(Wrappers.lambdaQuery(ResourceDO.class)
                .eq(ResourceDO::getResourceType, "VENUE")
                .ne(ResourceDO::getId, 90001)));   // 排除并发压测专用场地（阶段 9 夹具，不在场馆页展示）
        stats.put("lecture", resourceMapper.selectCount(Wrappers.lambdaQuery(ResourceDO.class)
                .eq(ResourceDO::getResourceType, "LECTURE")));
        // 讲座开放窗口（2026-10-08 规则：开始前 7 天开放报名）
        LocalDateTime lectureWindow = LocalDateTime.now().plusDays(7);
        stats.put("lectureBookable", lectureMapper.selectCount(Wrappers.lambdaQuery(LectureDO.class)
                .eq(LectureDO::getStatus, 1)
                .le(LectureDO::getStartTime, lectureWindow)));
        stats.put("lectureUpcoming", lectureMapper.selectCount(Wrappers.lambdaQuery(LectureDO.class)
                .eq(LectureDO::getStatus, 1)
                .gt(LectureDO::getStartTime, lectureWindow)));
        stats.put("courseQuota", resourceMapper.selectCount(Wrappers.lambdaQuery(ResourceDO.class)
                .eq(ResourceDO::getResourceType, "COURSE_QUOTA")));
        // 教学班名额总账（选课页与工作台入口的共同口径）
        Map<String, Object> courseSummary = courseClassMapper.summary();
        stats.put("courseClass", ((Number) courseSummary.getOrDefault("classCount", 0)).longValue());
        stats.put("courseCapacity", ((Number) courseSummary.getOrDefault("totalCapacity", 0)).longValue());
        stats.put("courseSelected", ((Number) courseSummary.getOrDefault("totalSelected", 0)).longValue());
        // 今日可约座位片数（走 idx_date_status）
        Long freeSlots = resourceSlotMapper.selectCount(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getSlotDate, LocalDate.now())
                .eq(ResourceSlotDO::getStatus, 1)
                .eq(ResourceSlotDO::getBooked, 0));
        stats.put("todayFreeSlots", freeSlots);
        return stats;
    }

    private ResourceRespDTO toResp(ResourceDO r) {
        return ResourceRespDTO.builder()
                .id(r.getId())
                .resourceType(r.getResourceType())
                .name(r.getName())
                .parentId(r.getParentId())
                .location(r.getLocation())
                .campus(r.getCampus())
                .capacity(r.getCapacity())
                .exclusive(r.getExclusive())
                .needApproval(r.getNeedApproval())
                .status(r.getStatus())
                .build();
    }
}
