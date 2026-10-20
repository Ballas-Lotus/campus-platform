import request from './request'

/** 并发压测（阶段 9）：场景 OVERSELL / DUPLICATE / BITMAP / COURSE_OVERSELL
 * 压测含 100 并发事务的真实执行，单独放宽超时（全局默认 10s 不够）
 */
export function runStress(scenario, threads) {
  return request.post('/stress/run', { scenario, threads }, { timeout: 60000 })
}

/** 阶段 19：选课限流状态（当前 QPS 阈值 + 累计拦截）——「📈 齐射观测台」用 */
export function getSentinel() {
  return request.get('/stress/sentinel')
}

/** 阶段 19：运行时热调选课限流阈值（观察万人齐射前放开、演示后恢复 20；无需重启） */
export function setSentinelQps(qps) {
  return request.post('/stress/sentinel', null, { params: { qps } })
}
