package com.campus.course.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.common.result.PageRespDTO;
import com.campus.common.result.Result;
import com.campus.course.dao.entity.CourseDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.course.dao.mapper.CourseMapper;
import com.campus.course.dao.mapper.CourseSelectionMapper;
import com.campus.course.dto.req.CourseSelectReqDTO;
import com.campus.course.dto.resp.CourseClassRespDTO;
import com.campus.course.dto.resp.CourseListRespDTO;
import com.campus.course.dto.resp.MyCourseRespDTO;
import com.campus.course.errorcode.CourseErrorCode;
import com.campus.course.service.CourseService;
import com.campus.framework.cache.CampusCache;
import com.campus.framework.cache.CampusCacheKeys;
import com.campus.framework.user.core.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 课程接口：列表（阶段 4）+ 选课 / 退课 / 我的课表（阶段 8，人数限额制、无候补队列）。
 *
 * <p><b>阶段 16</b>：课程列表缓存化——<b>课程数据进缓存、"我已选"标记每次实时查询</b>
 * （用户态与共享数据分离，避免串号，见 {@code CourseService#classes} 的同款说明）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/course")
@RequiredArgsConstructor
public class CourseController {

    private final CourseMapper courseMapper;
    private final CourseClassMapper courseClassMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseService courseService;
    /** 阶段 14：选课接口 Sentinel 限流（规则注册 + 拦截计数） */
    private final com.campus.sentinel.SentinelFlowConfig sentinelFlowConfig;
    /** 阶段 16：缓存门面 */
    private final CampusCache cache;
    /** 阶段 17：选课预扣闸门（Redis 前置）+ 幂等注解在闸门方法上 */
    private final com.campus.prededuct.CourseSelectGate courseSelectGate;

    /**
     * 课程列表：GET /api/course/list?college=&courseType=&keyword=&pageNum=1&pageSize=10
     *
     * <p>两步查询避免 N+1：① 分页查课程；② 对当页课程批量聚合教学班概要。</p>
     */
    @GetMapping("/list")
    public Result<PageRespDTO<CourseListRespDTO>> list(
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String courseType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        String key = CampusCacheKeys.courseList(college, courseType, keyword, pageNum, pageSize);
        PageRespDTO<CourseListRespDTO> page = cache.get(key, new TypeReference<>() {
        }, CampusCacheKeys.TTL_COURSE_LIST,
                () -> listFromDb(college, courseType, keyword, pageNum, pageSize));
        return Result.success(withMyMarks(page));
    }

    /** 回源：课程 + 教学班聚合（不含用户态） */
    private PageRespDTO<CourseListRespDTO> listFromDb(String college, String courseType, String keyword,
                                                      long pageNum, long pageSize) {
        LambdaQueryWrapper<CourseDO> wrapper = Wrappers.lambdaQuery(CourseDO.class)
                .eq(StringUtils.hasText(college), CourseDO::getCollege, college)
                .eq(StringUtils.hasText(courseType), CourseDO::getCourseType, courseType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(CourseDO::getName, keyword)
                        .or()
                        .like(CourseDO::getCourseCode, keyword))
                .orderByAsc(CourseDO::getId);
        Page<CourseDO> page = courseMapper.selectPage(Page.of(pageNum, pageSize), wrapper);

        List<Long> ids = page.getRecords().stream().map(CourseDO::getId).collect(Collectors.toList());
        Map<Long, Map<String, Object>> agg = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : courseClassMapper.aggregateByCourseIds(ids)) {
                agg.put(((Number) row.get("courseId")).longValue(), row);
            }
        }

        List<CourseListRespDTO> list = page.getRecords().stream().map(c -> {
            Map<String, Object> a = agg.getOrDefault(c.getId(), Map.of());
            return CourseListRespDTO.builder()
                    .id(c.getId())
                    .courseCode(c.getCourseCode())
                    .name(c.getName())
                    .teacher(c.getTeacher())
                    .credits(c.getCredits())
                    .college(c.getCollege())
                    .courseType(c.getCourseType())
                    .classCount(((Number) a.getOrDefault("classCount", 0)).longValue())
                    .totalCapacity(((Number) a.getOrDefault("totalCapacity", 0)).longValue())
                    .totalSelected(((Number) a.getOrDefault("totalSelected", 0)).longValue())
                    .build();
        }).collect(Collectors.toList());
        return new PageRespDTO<>(page.getTotal(), list);
    }

    /** 合并"我在这批课程下已选的教学班"标记（用户态，实时查，不进缓存） */
    private PageRespDTO<CourseListRespDTO> withMyMarks(PageRespDTO<CourseListRespDTO> page) {
        if (page.getList() == null || page.getList().isEmpty()) {
            return page;
        }
        List<Long> ids = page.getList().stream().map(CourseListRespDTO::getId).collect(Collectors.toList());
        Map<Long, String> mySel = new HashMap<>();
        for (Map<String, Object> row : courseSelectionMapper.selectMyCoursesIn(UserContext.getUserId(), ids)) {
            mySel.put(((Number) row.get("courseId")).longValue(), (String) row.get("classNames"));
        }
        for (CourseListRespDTO dto : page.getList()) {
            dto.setMySelected(mySel.containsKey(dto.getId()));
            dto.setMyClassName(mySel.get(dto.getId()));
        }
        return page;
    }

    /**
     * 教学班列表：GET /api/course/{courseId}/classes
     * 含我的选课状态标记（SELECTED / DROPPED / null）。
     */
    @GetMapping("/{courseId}/classes")
    public Result<List<CourseClassRespDTO>> classes(@PathVariable Long courseId) {
        return Result.success(courseService.classes(courseId));
    }

    /**
     * 选课：POST /api/course/select　{ "classId": 7002 }
     * 人数限额，满员直接拒绝（40052，无候补队列）；时间冲突（40053）；重复选课（40051）。
     *
     * <p>阶段 14：Sentinel 限流入口——抢课高峰超过 QPS 阈值（默认 20）时快速失败（40055），
     * 削峰保护下游（DB/预约引擎）；阈值配置见 {@code campus.sentinel.course-select-qps}。</p>
     *
     * <p>阶段 17：通过预扣闸门（{@link com.campus.prededuct.CourseSelectGate}）进入业务——
     * 闸门在事务之外先用 Redis 判定余量，只有放行的请求才占用数据库连接。</p>
     */
    @PostMapping("/select")
    @com.alibaba.csp.sentinel.annotation.SentinelResource(
            value = com.campus.sentinel.SentinelFlowConfig.COURSE_SELECT_RESOURCE,
            blockHandler = "selectFlowBlocked")
    public Result<MyCourseRespDTO> select(@RequestBody @Valid CourseSelectReqDTO requestParam) {
        return Result.success(courseSelectGate.select(requestParam));
    }

    /**
     * Sentinel 限流兜底：被限流的请求返回 40055（结构与业务拒绝一致，前端提示语可读）。
     * 签名规则：原方法参数 + BlockException（Sentinel 块处理器约定）。
     */
    public Result<MyCourseRespDTO> selectFlowBlocked(CourseSelectReqDTO requestParam,
                                                     com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        sentinelFlowConfig.recordBlocked();
        log.warn("[Sentinel] 选课限流拦截: classId={}（QPS 阈值 {}）",
                requestParam == null ? null : requestParam.getClassId(), sentinelFlowConfig.qpsLimit());
        return Result.fail(CourseErrorCode.COURSE_FLOW_LIMIT);
    }

    /**
     * 退课：POST /api/course/drop　{ "classId": 7002 }
     * 释放名额（同一事务），"选-退-选"复用同一行状态复活；名额直接回池，先到先得。
     */
    @PostMapping("/drop")
    public Result<Void> drop(@RequestBody @Valid CourseSelectReqDTO requestParam) {
        courseSelectGate.drop(requestParam.getClassId());
        return Result.success();
    }

    /**
     * 我的课表：GET /api/course/my（本学期已选，按周几 + 节次排序）
     */
    @GetMapping("/my")
    public Result<List<MyCourseRespDTO>> my() {
        return Result.success(courseService.my());
    }
}
