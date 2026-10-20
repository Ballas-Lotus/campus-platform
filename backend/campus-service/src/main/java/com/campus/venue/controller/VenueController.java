package com.campus.venue.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.result.Result;
import com.campus.venue.dao.entity.VenueDO;
import com.campus.venue.dao.mapper.VenueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 体育场馆接口。
 */
@RestController
@RequestMapping("/api/venue")
@RequiredArgsConstructor
public class VenueController {

    private final VenueMapper venueMapper;

    /**
     * 场馆列表：GET /api/venue/list?venueType=&campus=（20 个）
     */
    @GetMapping("/list")
    public Result<List<VenueDO>> list(@RequestParam(required = false) String venueType,
                                      @RequestParam(required = false) String campus) {
        return Result.success(venueMapper.selectList(
                Wrappers.lambdaQuery(VenueDO.class)
                        .eq(StringUtils.hasText(venueType), VenueDO::getVenueType, venueType)
                        .eq(StringUtils.hasText(campus), VenueDO::getCampus, campus)
                        .orderByAsc(VenueDO::getId)));
    }
}
