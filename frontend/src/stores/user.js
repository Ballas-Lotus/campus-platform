import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { TOKEN_KEY } from '../api/request'

/**
 * 登录用户状态：token 持久化到 localStorage（刷新页面保持登录），
 * 用户信息（学院/专业等）登录后从 /auth/me 拉取。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    info: null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token
  },

  actions: {
    async login(account, password) {
      const data = await authApi.login(account, password)
      this.token = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      this.info = {
        userId: data.userId,
        studentNo: data.studentNo,
        realName: data.realName,
        role: data.role
      }
      return data
    },

    /** 拉取完整用户信息（学院/专业/年级），刷新页面后调用 */
    async fetchMe() {
      if (!this.token) return null
      this.info = await authApi.me()
      return this.info
    },

    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.clear()
      }
    },

    clear() {
      this.token = ''
      this.info = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
