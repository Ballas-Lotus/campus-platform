import request from './request'

/** 阶段 20：名额实时同步（读侧）接口 */

/**
 * 名额快照（教学班粒度）。
 * version 仅用于服务端 L1 直返（命中即零网络调用）；不传则强制走分布式缓存/回源，
 * 用作"校准轮"（降级轮询与重连拉齐时使用，保证最终收敛）。
 */
export function getCourseStock(courseId, version) {
  return request.get(`/course/${courseId}/stock`, {
    params: version != null ? { version } : {}
  })
}

/** 观测计数（信号发布/扇出/连接数/L1 命中…） */
export function getStockMetrics() {
  return request.get('/course/stock/metrics')
}
