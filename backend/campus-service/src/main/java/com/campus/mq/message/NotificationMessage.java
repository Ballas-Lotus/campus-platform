package com.campus.mq.message;

import com.campus.mq.MqMessage;

/**
 * 站内信消息（预约成功 / 选课成功 / 演示）。
 */
public record NotificationMessage(
        String messageKey,
        Long userId,
        String title,
        String content,
        String bizType,
        String bizId) implements MqMessage {

    public static NotificationMessage of(Long userId, String title, String content,
                                         String bizType, String bizId) {
        String key = bizType + ":" + bizId + ":" + userId;
        return new NotificationMessage(key, userId, title, content, bizType, bizId);
    }
}
