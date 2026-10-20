package com.campus.lecture.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.common.errorcode.BaseErrorCode;
import com.campus.common.exception.ClientException;
import com.campus.common.result.Result;
import com.campus.lecture.dao.entity.LectureDO;
import com.campus.lecture.dao.mapper.LectureMapper;
import com.campus.lecture.dto.resp.LectureRespDTO;
import com.campus.lecture.errorcode.LectureErrorCode;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.dto.resp.MyReservationRespDTO;
import com.campus.reservation.service.ReservationService;
import com.campus.resource.dao.entity.ResourceDO;
import com.campus.resource.dao.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 讲座接口。
 *
 * <p>2026-10-08 改为<b>报名制</b>：无座位图（到场自选座），仅人数限额——
 * 每场讲座一条 LECTURE 名额资源（容量计数，同场馆语义），报名复用预约引擎。</p>
 */
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
public class LectureController {

    private final LectureMapper lectureMapper;
    private final ResourceMapper resourceMapper;
    private final ReservationService reservationService;

    /**
     * 讲座列表：GET /api/lecture/list（20 场，含已报名数 bookable/bookableFrom）
     */
    @GetMapping("/list")
    public Result<List<LectureRespDTO>> list() {
        return Result.success(lectureMapper.listWithBooked());
    }

    /**
     * 讲座报名：POST /api/lecture/{id}/signup
     *
     * <p>校验：讲座已发布、开始时间在 7 天窗口内；随后组装"整场时段"走统一预约引擎
     * （名额扣减 / 位图冲突检测 / 防重与幂等与座位和场馆完全一致）。</p>
     */
    @PostMapping("/{id}/signup")
    public Result<MyReservationRespDTO> signup(@PathVariable Long id) {
        LectureDO lecture = lectureMapper.selectById(id);
        if (lecture == null || lecture.getStatus() == null || lecture.getStatus() != 1) {
            throw new ClientException(BaseErrorCode.NOT_FOUND);
        }
        // 7 天开放窗口（2026-10-08 规则）
        if (lecture.getStartTime().isAfter(LocalDateTime.now().plusDays(7))) {
            throw new ClientException(LectureErrorCode.NOT_BOOKABLE);
        }
        // 讲座名额资源（每场一条，parent_id = 讲座 id）
        ResourceDO resource = resourceMapper.selectOne(Wrappers.lambdaQuery(ResourceDO.class)
                .eq(ResourceDO::getParentId, id)
                .eq(ResourceDO::getResourceType, "LECTURE"));
        if (resource == null) {
            throw new ClientException(BaseErrorCode.NOT_FOUND);
        }
        ReservationCreateReqDTO request = new ReservationCreateReqDTO();
        request.setResourceId(resource.getId());
        request.setSlotDate(lecture.getStartTime().toLocalDate());
        request.setStartTime(lecture.getStartTime().toLocalTime());
        request.setEndTime(lecture.getEndTime().toLocalTime());
        return Result.success(reservationService.create(request));
    }
}
