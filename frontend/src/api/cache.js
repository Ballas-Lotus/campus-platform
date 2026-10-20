import request from './request'

/** 阶段 16：缓存观测接口 */

/** 缓存全景：命中率/回源/击穿/穿透 + 布隆过滤器状态 + 规则表 + key 采样 */
export function getCacheMetrics() {
  return request.get('/cache/metrics')
}

/** 「有缓存 vs 无缓存」对照实验：type = overview | slots */
export function runCacheDemo(params) {
  return request.post('/cache/demo', null, { params })
}

/** 布隆过滤器探针：传一个资源 id，看是否在全集内（不存在的 id 会被拦在缓存/DB 之前） */
export function bloomProbe(resourceId) {
  return request.get('/cache/bloom/probe', { params: { resourceId } })
}

/** 手动失效：scope = slots | resources | courses | classes | lectures | overview | all */
export function evictCache(params) {
  return request.post('/cache/evict', null, { params })
}

/** 阶段 19：教学班读模型现场（余量 Hash vs DB 真值，逐班偏差） */
export function getStockState(courseId) {
  return request.get('/cache/stock/state', { params: { courseId } })
}

/** 阶段 19：抢课窗口预热（不传 courseId 则预热热门课程） */
export function warmupCache(courseId) {
  return request.post('/cache/warmup', null, { params: courseId ? { courseId } : {} })
}

/** 阶段 19：手动对账一轮（余量 Hash 拉回 DB 真值） */
export function reconcileStock() {
  return request.post('/cache/stock/reconcile')
}

/** 阶段 19（演示）：毒化缓存余量——看偏差与对账自愈 */
export function poisonStock(params) {
  return request.post('/cache/stock/poison', null, { params })
}
