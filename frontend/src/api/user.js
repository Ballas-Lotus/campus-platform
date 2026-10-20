import request from './request'

/** 个人中心（阶段 10）：资料统计 / 当日日程（位图反解）/ 修改密码 */

/** 个人资料 + 统计 */
export function getProfile() {
  return request.get('/user/profile')
}

/** 当日日程（date 缺省今天） */
export function getSchedule(date) {
  return request.get('/user/schedule', { params: { date } })
}

/** 修改密码 */
export function changePassword(data) {
  return request.post('/user/password', data)
}

/** 我的站内信：列表 + 未读数（阶段 13，MQ 异步通知读取侧） */
export function getNotifications(params) {
  return request.get('/user/notifications', { params })
}

/** 单条已读 */
export function markNotificationRead(id) {
  return request.post(`/user/notifications/${id}/read`)
}

/** 全部已读 */
export function markAllNotificationsRead() {
  return request.post('/user/notifications/read-all')
}
