import request from './request'

/** 阶段 17：预扣闸门观测接口 */

/** 闸门全景：指标 + 活跃计数器现场（Redis 预计数 vs DB 真值 vs 偏差） */
export function getPreDeductState() {
  return request.get('/prededuct/state')
}

/** 手动触发一轮对账（定时任务每 30 秒自动跑一次） */
export function reconcile() {
  return request.post('/prededuct/reconcile')
}

/** 丢弃某教学班的预扣计数器（下次请求按 DB 真值重建） */
export function dropCounter(classId) {
  return request.post('/prededuct/drop', null, { params: { classId } })
}
