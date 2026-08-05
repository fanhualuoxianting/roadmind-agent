import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: Number(env.WEB_PORT || 5173),
      proxy: {
        '/api': {
          target: env.VITE_SERVER_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: false,
        },
      },
    },
  }
})
