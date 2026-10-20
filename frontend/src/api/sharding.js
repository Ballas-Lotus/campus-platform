import request from './request'

/** 阶段 14：分库分表观测接口 */

/** 分片拓扑 + 各物理分片行数（campus-service，观测接口物理直连统计） */
export function getShardingInfo() {
  return request.get('/sharding/info')
}

/** 路由验证：传入 userId（可选 reservationNo）——返回基因法推导 + 真实查询结果 */
export function getShardingRoute(params) {
  return request.get('/sharding/route', { params })
}

/** 选课接口 Sentinel 限流状态（阈值 / 累计拦截数） */
export function getSentinelInfo() {
  return request.get('/sharding/sentinel')
}
