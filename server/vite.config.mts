import { defineConfig } from 'vite'
import { viteStaticCopy } from 'vite-plugin-static-copy'
import { resolve } from 'node:path'

const assetPaths = {
  main_js: 'app/assets/javascripts/main.ts',
  uswds_css: 'app/assets/stylesheets/uswds/styles.scss',
  uswds_js: 'node_modules/@uswds/uswds/dist/js/uswds.min.js',
  uswdsinit_js: 'node_modules/@uswds/uswds/dist/js/uswds-init.min.js',
  uswds_img: 'node_modules/@uswds/uswds/dist/img/**',
  tailwind_css: 'app/assets/stylesheets/styles.css',
}

export default defineConfig({
  publicDir: false,
  server: {
    port: 5173,
    host: '0.0.0.0',
    cors: true,
    strictPort: true,
  },
  build: {
    outDir: 'public/dist',
    emptyOutDir: true,
    sourcemap: true,
    rollupOptions: {
      input: {
        main: resolve(import.meta.dirname, assetPaths.main_js),
        uswdsinit_js: resolve(import.meta.dirname, assetPaths.uswdsinit_js),
        uswds_css: resolve(import.meta.dirname, assetPaths.uswds_css),
        tailwind: resolve(import.meta.dirname, assetPaths.tailwind_css),
      },
      output: {
        entryFileNames: '[name].bundle.js',
        chunkFileNames: '[hash]-[name].chunk.js',
        assetFileNames: (assetInfo) => {
          const name = assetInfo.names?.[0] ?? ''
          if (name.match(/\.(css|scss)$/)) {
            return '[name].min.css'
          }
          if (name.match(/\.(woff2?|ttf|eot)$/)) {
            return 'fonts/[name][extname]'
          }
          if (name.match(/\.(png|jpe?g|svg|gif|webp)$/)) {
            return 'img/[name][extname]'
          }
          return '[name][extname]'
        },
      },
    },
    target: 'es2022',
  },
  css: {
    preprocessorOptions: {
      scss: {
        quietDeps: true,
        loadPaths: [
          resolve(import.meta.dirname, 'app/assets/stylesheets/uswds'),
          resolve(import.meta.dirname, 'node_modules/@uswds/uswds/packages'),
        ],
      },
    },
  },
  resolve: {
    alias: {
      '@': resolve(import.meta.dirname, 'app/assets/javascripts'),
    },
  },
  plugins: [
    viteStaticCopy({
      targets: [
        {
          src: assetPaths.uswds_js,
          dest: '.',
          rename: 'uswds.min.js',
        },
        {
          src: assetPaths.uswds_img,
          dest: 'img',
        },
      ],
    }),
  ],
})
