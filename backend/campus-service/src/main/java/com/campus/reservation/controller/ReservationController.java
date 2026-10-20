package com.campus.reservation.controller;

import com.campus.common.result.PageRespDTO;
import com.campus.common.result.Result;
import com.campus.prededuct.ReservationGate;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.dto.resp.MyReservationRespDTO;
import com.campus.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预约接口（须登录；文档见 docs/02-接口文档.md）。
 *
 * <p>阶段 17/18：创建入口改走 {@link ReservationGate}——预扣闸门（Redis 前置挡量 + 幂等）
 * 在数据库事务之外先判定余量，事务内仍是原来的"位图冲突检测 + 条件更新"五步。</p>
 */
@RestController
@RequestMapping("/api/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationGate reservationGate;

    /**
     * 创建预约：POST /api/reservation/create
     * 请求体：{ "resourceId": 10001, "slotDate": "2026-10-09", "startTime": "14:00", "endTime": "16:00" }
     *
     * <p>链路：幂等注解 → Redis 预扣（跨时间片全成或全败）→ 事务（位图冲突 → 条件更新占片 → 落单）→
     * 失败回补预扣。</p>
     */
    @PostMapping("/create")
    public Result<MyReservationRespDTO> create(@RequestBody @Valid ReservationCreateReqDTO requestParam) {
        return Result.success(reservationGate.create(requestParam));
    }

    /**
     * 签到：POST /api/reservation/{id}/checkin
     * 窗口：开始前 30 分钟 ~ 开始后 15 分钟；超窗未签到将由定时任务判爽约（禁约 24 小时）。
     */
    @PostMapping("/{id}/checkin")
    public Result<Void> checkin(@PathVariable Long id) {
        reservationService.checkin(id);
        return Result.success();
    }

    /**
     * 取消预约：POST /api/reservation/{id}/cancel（释放库存 + 清位图 + 状态流转，同一事务）
     * 提前 30 分钟以上取消无惩罚；不足 30 分钟取消 → 禁约 10 分钟。
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        reservationService.cancel(id);
        return Result.success();
    }

    /**
     * 我的预约：GET /api/reservation/my?status=&pageNum=1&pageSize=10
     */
    @GetMapping("/my")
    public Result<PageRespDTO<MyReservationRespDTO>> my(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(reservationService.myReservations(status, pageNum, pageSize));
    }
}
