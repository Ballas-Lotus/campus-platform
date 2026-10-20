import request from './request'

/** 预约接口 */

/** 创建预约（区间独占/容量计数通用） */
export function createReservation(data) {
  return request.post('/reservation/create', data)
}

/** 签到（窗口：开始前 30 分钟 ~ 开始后 15 分钟；未签到将判爽约并禁约 24 小时） */
export function checkinReservation(id) {
  return request.post(`/reservation/${id}/checkin`)
}

/** 取消预约（释放库存 + 清位图；距开始不足 30 分钟取消将禁约 10 分钟） */
export function cancelReservation(id) {
  return request.post(`/reservation/${id}/cancel`)
}

/** 我的预约（分页） */
export function getMyReservations(params) {
  return request.get('/reservation/my', { params })
}
