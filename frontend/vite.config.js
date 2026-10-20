import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发环境：/api 代理到网关（阶段 12 起；单体期曾直连 8081）
      '/api': {
        target: 'http://127.0.0.1:9080',
        changeOrigin: true
      },
      // 阶段 15：单体留档基线（仅「📈 演进对比」页对照压测用；未启动时该页给出提示）
      '/mono-api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/mono-api/, '/api')
      }
    }
  }
})
