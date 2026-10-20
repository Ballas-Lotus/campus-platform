package com.campus.library.controller;

import com.campus.common.result.Result;
import com.campus.library.dao.mapper.LibraryAreaMapper;
import com.campus.library.dto.resp.LibraryAreaRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图书馆接口。
 */
@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryAreaMapper libraryAreaMapper;

    /**
     * 楼层列表：GET /api/library/areas（逸夫楼 5 层，含座位数 + 今日空闲率）。
     */
    @GetMapping("/areas")
    public Result<List<LibraryAreaRespDTO>> areas() {
        List<LibraryAreaRespDTO> list = libraryAreaMapper.listWithTodayStats();
        for (LibraryAreaRespDTO dto : list) {
            long total = dto.getTotalSlots() == null ? 0 : dto.getTotalSlots();
            long free = dto.getFreeSlots() == null ? 0 : dto.getFreeSlots();
            dto.setFreeRate(total > 0 ? Math.round(free * 1000.0 / total) / 10.0 : null);
        }
        return Result.success(list);
    }
}
