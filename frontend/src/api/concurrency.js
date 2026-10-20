import request from './request'

/** 阶段 18：并发控制（分布式锁 / 接口幂等 / 线程池）观测接口 */

/** 全景：锁 + 幂等 + 线程池 */
export function getConcurrencyMetrics() {
  return request.get('/concurrency/metrics')
}

/** 锁竞争现场演示：N 线程同抢一把锁（waitMs=0 只试一次；>0 自旋排队） */
export function runLockDemo(params) {
  return request.post('/concurrency/lock-demo', null, { params })
}

/** 阶段 19：合并 Lua 对照——旧路径（4 次往返）vs 快路径（2 次往返）各跑 N 轮 */
export function runLuaMergeDemo(params) {
  return request.post('/concurrency/lua-merge-demo', null, { params })
}
