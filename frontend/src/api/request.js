import axios from 'axios'
import { ElMessage } from 'element-plus'

/** 与 stores/user.js 共用的 localStorage key */
export const TOKEN_KEY = 'campus:token'

/**
 * axios 统一封装：
 *  - 请求：自动注入 Authorization（从 localStorage 读，避免与 store 循环依赖）
 *  - 响应：code === 0 直接返回业务 data；40100/40101 清登录态并回登录页；其余弹错误提示
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 0) {
      return data
    }
    // 未登录 / 登录态失效：清理并回登录页（整页跳转，规避与 router 的循环依赖）
    if (code === 40100 || code === 40101) {
      localStorage.removeItem(TOKEN_KEY)
      if (!window.location.pathname.startsWith('/login')) {
        ElMessage.warning(message || '登录已过期，请重新登录')
        // 稍作延迟，让提示可见后再跳转（立即 replace 会让提示一闪而过）
        setTimeout(() => window.location.replace('/login'), 800)
      }
      return Promise.reject(new Error(message))
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
  },
  (error) => {
    ElMessage.error('网络异常，请检查后端服务是否启动')
    return Promise.reject(error)
  }
)

export default request
