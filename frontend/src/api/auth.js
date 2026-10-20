import request from './request'

/** 认证接口（对应后端 com.campus.auth.controller.AuthController，文档 docs/02-接口文档.md） */

/**
 * 登录：account 支持 学号 / 手机号 / 邮箱（自动识别）
 */
export function login(account, password) {
  return request.post('/auth/login', { account, password })
}

/** 当前登录用户信息 */
export function me() {
  return request.get('/auth/me')
}

/** 登出（服务端删除 Redis 登录态副本） */
export function logout() {
  return request.post('/auth/logout')
}
