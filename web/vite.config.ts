import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The Headwind server serves its REST API under the `/rest` context. The
// backend runs on :8090 on the same host, so during development (and when this
// SPA is served over the LAN) we proxy `/rest` there, sharing the session
// cookie (JSESSIONID). Override the target with VITE_DEV_PROXY_TARGET.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8090';

  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
      allowedHosts: true,
      proxy: {
        '/rest': {
          target: proxyTarget,
          changeOrigin: true,
          // The server is session-cookie based, so we must forward cookies.
          cookieDomainRewrite: '',
        },
      },
    },
  };
});
