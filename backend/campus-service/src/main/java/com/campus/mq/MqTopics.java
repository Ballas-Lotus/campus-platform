package com.campus.mq;

/**
 * MQ 主题与消费组常量（阶段 13；前缀 campus_* / campus-* 与 12306 项目隔离）。
 */
public final class MqTopics {

    private MqTopics() {
    }

    /** 站内信通知（预约成功 / 选课成功 / 演示消息） */
    public static final String NOTIFICATION = "campus_notification_topic";
    public static final String NOTIFICATION_GROUP = "campus-notification-consumer";

    /** 预约超时未签到（延时消息：到点判爽约，替代/兜底 @Scheduled 轮询） */
    public static final String RESERVATION_TIMEOUT = "campus_reservation_timeout_topic";
    public static final String RESERVATION_TIMEOUT_GROUP = "campus-timeout-consumer";

    /**
     * 阶段 21（受理制）：选课落库消息——Redis 受理通过后发出，消费端异步执行
     * {@code CourseService.select(userId, classId, opId)} 落库；被拒则走补偿回补名额。
     *
     * <p>与通知类的本质区别：<b>这条消息是"受理"的组成部分</b>——发送失败必须回滚并返回
     * 40056（先 MQ 后 PUBLISH 的原因），不是"丢失只少一封信"的通知。</p>
     */
    public static final String COURSE_SELECT = "campus_course_select_topic";
    public static final String COURSE_SELECT_GROUP = "campus-course-select-consumer";
}
