import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  preview: {
    // 穿透只走打包后的页面，避免开发模式把上百个脚本逐个转发
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
