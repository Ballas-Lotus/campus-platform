package com.campus.internal;

import com.campus.common.result.Result;
import com.campus.course.service.CourseService;
import com.campus.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（阶段 12：服务间调用；网关不路由 /api/internal/**，仅内网直连可达）。
 *
 * <p>场景：常驻测试账号登录时，user-service 需要重置该账号的预约与选课数据——
 * 数据归属 campus-service，由本接口承接（服务边界不因"同一个库"而搬逻辑）。</p>
 *
 * <p>生产加固方向：服务间鉴权（内网 mTLS / 内部 token）、Knife4j 隐藏（见 docs/04）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalController {

    private final ReservationService reservationService;
    private final CourseService courseService;

    /**
     * 重置指定用户的预约与选课数据（测试账号登录自愈用）。
     */
    @PostMapping("/test-reset/{userId}")
    public Result<Void> testReset(@PathVariable Long userId) {
        log.info("[内部调用] 重置测试账号数据: userId={}", userId);
        reservationService.resetForTestUser(userId);
        courseService.resetForTestUser(userId);
        return Result.success();
    }
}
