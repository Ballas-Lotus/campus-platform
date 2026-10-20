package com.campus.mq.message;

import com.campus.mq.MqMessage;

/**
 * 阶段 21（受理制）：选课落库消息。
 *
 * <p>幂等键 = {@code opId}（受理单号）：同一条受理消息无论重投多少次，消费端凭它
 * 判定"是否已生效"（落库行 op_id 比对）与"是否已解决"（受理台账是否还在）。</p>
 *
 * <p>携带 userId 的原因：落库事务需要显式用户身份（消费线程没有 UserContext）；
 * 超时裁决也要按 (userId, classId) 单分片点查落库行——分片键必须带上。</p>
 */
public record CourseSelectMessage(
        String opId,
        Long userId,
        Long classId,
        Long courseId,
        long acceptTs) implements MqMessage {

    @Override
    public String messageKey() {
        return opId;
    }
}
