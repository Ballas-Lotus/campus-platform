package com.campus.resource.controller;

import com.campus.common.result.PageRespDTO;
import com.campus.common.result.Result;
import com.campus.resource.dto.resp.ResourceRespDTO;
import com.campus.resource.dto.resp.SlotRespDTO;
import com.campus.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统一资源接口（需登录；文档见 docs/02-接口文档.md）。
 */
@RestController
@RequestMapping("/api/resource")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * 资源列表：GET /api/resource/list?resourceType=&campus=&keyword=&pageNum=1&pageSize=10
     */
    @GetMapping("/list")
    public Result<PageRespDTO<ResourceRespDTO>> list(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String campus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(resourceService.list(resourceType, campus, keyword, pageNum, pageSize));
    }

    /**
     * 时间片可用性：GET /api/resource/{resourceId}/slots?date=2026-10-08
     */
    @GetMapping("/{resourceId}/slots")
    public Result<List<SlotRespDTO>> slots(
            @PathVariable Long resourceId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return Result.success(resourceService.slots(resourceId, date));
    }

    /**
     * 工作台概览统计：GET /api/resource/stats/overview
     */
    @GetMapping("/stats/overview")
    public Result<Map<String, Long>> overview() {
        return Result.success(resourceService.overview());
    }
}
