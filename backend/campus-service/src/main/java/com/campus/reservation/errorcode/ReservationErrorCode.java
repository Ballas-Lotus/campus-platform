package com.campus.reservation.errorcode;

import com.campus.common.errorcode.IErrorCode;

/**
 * 预约模块错误码（40020~40099 段）。
 */
public enum ReservationErrorCode implements IErrorCode {

    /** 资源不可预约 */
    RESOURCE_UNAVAILABLE(40020, "该资源当前不可预约"),

    /** 时段不合法 */
    INVALID_TIME_RANGE(40021, "时段不合法（需整点、1~4 小时、在开放的 08:00~22:00 内）"),

    /** 日期超出可约窗口 */
    DATE_OUT_OF_WINDOW(40022, "该日期超出可预约窗口（仅支持预约当天与明天）"),

    /** 与已有安排冲突（位图冲突检测） */
    TIME_CONFLICT(40023, "该时段与你的已有安排冲突"),

    /** 库存不足（并发下被别人抢先占用） */
    SOLD_OUT(40024, "手慢了，该时段刚被约满"),

    /** 超出周期配额 */
    QUOTA_EXCEEDED(40025, "今日图书馆预约次数已达上限（4 次/天）"),

    /** 重复预约（唯一索引兜底） */
    DUPLICATE_RESERVATION(40026, "你已预约过该时段"),

    /** 预约不存在或不可取消 */
    CANNOT_CANCEL(40027, "该预约不存在或当前状态不可取消"),

    /** 不是自己的预约 */
    NOT_YOUR_RESERVATION(40028, "无权操作他人的预约"),

    /** 被惩罚限制预约（动态提示带解禁时间） */
    PENALTY_RESTRICTED(40029, "你当前被限制预约，请稍后再试"),

    /** 不在签到窗口内或状态不可签到 */
    CHECKIN_NOT_ALLOWED(40030, "当前不可签到（仅已批准的预约可在开始前 30 分钟至开始后 15 分钟内签到）"),

    /** 已经签到过 */
    ALREADY_CHECKED_IN(40031, "该预约已签到"),

    /** 不在待审批状态 */
    NOT_PENDING(40032, "该预约不在待审批状态（可能已被处理）");

    private final Integer code;
    private final String message;

    ReservationErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public Integer code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
