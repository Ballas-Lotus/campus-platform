import axios from 'axios'
import { TOKEN_KEY } from './request'

/**
 * 阶段 15：「演进对比」页专用——同一场景分别打到两套后端。
 *  - 分片形态：/api      → 网关 9080（当前系统）
 *  - 单体基线：/mono-api → 留档 jar 8081（resources/legacy/…，可能未启动）
 * 独立 axios 实例：不做统一拦截（基线未启动时给页面自行处理，而不是全局弹错）。
 */

const monoClient = axios.create({ baseURL: '/mono-api', timeout: 180000 })
const shardClient = axios.create({ baseURL: '/api', timeout: 180000 })

function withToken(client) {
  client.interceptors.request.use((config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) config.headers.Authorization = token
    return config
  })
  return client
}

withToken(monoClient)
withToken(shardClient)

/** 在指定后端运行压测场景，返回 { conclusion, durationMs, success, requests, p95Ms } */
export async function runStressOn(side, scenario, threads) {
  const client = side === 'mono' ? monoClient : shardClient
  const resp = await client.post('/stress/run', { scenario, threads })
  const { code, message, data } = resp.data
  if (code !== 0) {
    throw new Error(message || `code=${code}`)
  }
  return data
}
