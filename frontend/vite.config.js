import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import os from 'os'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const MERMAID_CHUNK_PATTERN = /node_modules[\\/](mermaid|@mermaid-js|dagre-d3-es|cytoscape|cytoscape-cose-bilkent|cytoscape-fcose|d3(?:-|$|[\\/])|d3-sankey|khroma|roughjs|katex|@braintree[\\/]sanitize-url|@upsetjs[\\/]venn\.js|@iconify[\\/]utils|dompurify|marked|dayjs|lodash-es|stylis|uuid|ts-dedent|es-toolkit)/

function isMermaidChunk(id) {
  return MERMAID_CHUNK_PATTERN.test(id.replace(/\\/g, '/'))
}

export default defineConfig({
  // Packaged clients use a relative asset base so the same output can be
  // served by Capacitor/Electron as well as a conventional web server.
  base: process.env.VITE_NATIVE_APP === 'true' ? './' : '/',
  plugins: [vue()],
  // OneDrive 同步目录下浏览器/Vite 缓存易损坏，将缓存放到系统临时目录
  cacheDir: path.join(os.tmpdir(), 'visionary-tutor-vite'),
  esbuild: {
    drop: process.env.NODE_ENV === 'production' ? ['console', 'debugger'] : [],
  },
  build: {
    // A release must never contain chunks from an older hashed build.
    emptyOutDir: true,
    // Mermaid is an intentionally isolated async chunk. The bundle budget
    // script verifies that it never enters the initial preload graph.
    chunkSizeWarningLimit: 3200,
    modulePreload: {
      resolveDependencies: (_filename, deps) => deps.filter((dep) =>
        !dep.includes('vendor-mermaid') && !dep.includes('vendor-markdown')),
    },
    rollupOptions: {
      onLog(level, log, handler) {
        // @vueuse/core ships misplaced PURE comments that Rolldown reports even
        // though they do not affect correctness or tree shaking of app code.
        if (log.code === 'INVALID_ANNOTATION' && log.id?.includes('@vueuse/core')) return
        handler(level, log)
      },
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/[\\/]node_modules[\\/]markdown-it[\\/]/.test(id)) return 'vendor-markdown'
          if (isMermaidChunk(id)) return 'vendor-mermaid'
          if (id.includes('@mediapipe/tasks-vision')) return 'vendor-ai'
          if (/[\\/]node_modules[\\/](vue|vue-router|pinia)[\\/]/.test(id)) return 'vendor-ui'
          return 'vendor'
        },
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const info = assetInfo.name.split('.')
          const ext = info[info.length - 1]
          if (/\.(png|jpe?g|gif|svg|webp|ico)$/i.test(assetInfo.name)) {
            return 'assets/images/[name]-[hash][extname]'
          }
          if (/\.css$/i.test(assetInfo.name)) {
            return 'assets/styles/[name]-[hash][extname]'
          }
          return 'assets/[name]-[hash][extname]'
        },
      },
    },
    sourcemap: process.env.NODE_ENV !== 'production',
    minify: 'esbuild',
  },
  server: {
    watch: {
      usePolling: true,
    },
    fs: {
      strict: false,
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
