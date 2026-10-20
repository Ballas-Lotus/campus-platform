import request from './request'

/** MQ 演示（阶段 13）：站内信 / 幂等消费 / 延时消息超时处理 */

export function getMqInfo() {
  return request.get('/mq/info')
}

/** 场景 A：发一条站内信（异步落库） */
export function demoNotification() {
  return request.post('/mq/demo/notification')
}

/** 场景 B：同一条消息投递两次 */
export function demoIdempotent() {
  return request.post('/mq/demo/idempotent')
}

/** 场景 B 轮询：该消息键落库条数（预期 1） */
export function idempotentResult(key) {
  return request.get(`/mq/demo/idempotent/${key}`)
}

/** 场景 C：真实预约 + 8 秒延时消息（到点判爽约） */
export function demoTimeout() {
  return request.post('/mq/demo/timeout')
}

/** 场景 C 轮询：预约当前状态 + 片库存 + 禁约 */
export function timeoutResult(reservationId) {
  return request.get(`/mq/demo/timeout/${reservationId}`)
}

/** 阶段 19 场景 D：一次调用批量投递 N 条站内信（演示消费者批量消费） */
export function sendBatchNotifications(count) {
  return request.post('/mq/demo/notification-batch', null, { params: { count } })
}

/** 阶段 19：消费者批量消费指标（批次/消息数/平均批大小/幂等跳过） */
export function getConsumerStats() {
  return request.get('/mq/consumer-stats')
}
