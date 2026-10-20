package com.campus.user.controller;

import com.campus.common.result.Result;
import com.campus.user.dto.req.PasswordChangeReqDTO;
import com.campus.user.dto.resp.ScheduleRespDTO;
import com.campus.user.dto.resp.UserProfileRespDTO;
import com.campus.user.service.NotificationService;
import com.campus.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 个人中心接口（阶段 10）：资料统计 / 当日日程（位图反解）/ 修改密码；
 * 阶段 13 增加站内信（MQ 异步通知的读取侧）。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;
    private final NotificationService notificationService;

    /**
     * 个人资料 + 统计：GET /api/user/profile
     */
    @GetMapping("/profile")
    public Result<UserProfileRespDTO> profile() {
        return Result.success(userProfileService.profile());
    }

    /**
     * 当日日程：GET /api/user/schedule?date=2026-10-08（缺省今天）
     * 位图反解预约占用段 + 叠加当日课程，合并按时间排序。
     */
    @GetMapping("/schedule")
    public Result<ScheduleRespDTO> schedule(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(userProfileService.schedule(date));
    }

    /**
     * 修改密码：POST /api/user/password　{ "oldPassword": "123456", "newPassword": "abc12345" }
     */
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid PasswordChangeReqDTO requestParam) {
        userProfileService.changePassword(requestParam);
        return Result.success();
    }

    // ---------------- 站内信（阶段 13：MQ 异步通知读取侧） ----------------

    /**
     * 我的通知：GET /api/user/notifications?pageNum=1&pageSize=10 → { total, unread, list }
     */
    @GetMapping("/notifications")
    public Result<Map<String, Object>> notifications(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(notificationService.list(pageNum, pageSize));
    }

    /**
     * 单条已读：POST /api/user/notifications/{id}/read
     */
    @PostMapping("/notifications/{id}/read")
    public Result<Void> markNotificationRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.success();
    }

    /**
     * 全部已读：POST /api/user/notifications/read-all
     */
    @PostMapping("/notifications/read-all")
    public Result<Void> markAllNotificationsRead() {
        notificationService.markAllRead();
        return Result.success();
    }
}
