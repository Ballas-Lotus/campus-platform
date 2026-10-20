package com.campus.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.common.result.PageRespDTO;
import com.campus.framework.user.core.UserContext;
import com.campus.notification.dao.entity.NotificationDO;
import com.campus.notification.dao.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 站内信服务（阶段 13）：读取由 campus-service 的 MQ 消费者异步写入的 t_notification。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /**
     * 我的通知列表 + 未读数（顶栏铃铛 / 个人中心）。
     */
    public Map<String, Object> list(long pageNum, long pageSize) {
        Long userId = UserContext.getUserId();
        Page<NotificationDO> page = notificationMapper.selectPage(Page.of(pageNum, pageSize),
                Wrappers.lambdaQuery(NotificationDO.class)
                        .eq(NotificationDO::getUserId, userId)
                        .orderByDesc(NotificationDO::getId));
        Long unread = notificationMapper.selectCount(Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getUserId, userId)
                .eq(NotificationDO::getIsRead, 0));
        Map<String, Object> data = new HashMap<>();
        data.put("total", page.getTotal());
        data.put("unread", unread);
        data.put("list", page.getRecords());
        return data;
    }

    /**
     * 单条已读（仅限本人）。
     */
    public void markRead(Long id) {
        notificationMapper.update(null, Wrappers.lambdaUpdate(NotificationDO.class)
                .set(NotificationDO::getIsRead, 1)
                .eq(NotificationDO::getId, id)
                .eq(NotificationDO::getUserId, UserContext.getUserId()));
    }

    /**
     * 全部已读。
     */
    public void markAllRead() {
        notificationMapper.update(null, Wrappers.lambdaUpdate(NotificationDO.class)
                .set(NotificationDO::getIsRead, 1)
                .eq(NotificationDO::getUserId, UserContext.getUserId())
                .eq(NotificationDO::getIsRead, 0));
    }
}
