// Bundles the engine and its dependencies into one file for the Android app's assets.
import { build } from 'esbuild'
import fs from 'node:fs'
import path from 'node:path'

const outDir = path.resolve('../app/src/main/assets/nodejs-project')
fs.rmSync(outDir, { recursive: true, force: true })
fs.mkdirSync(outDir, { recursive: true })

await build({
  entryPoints: ['src/main.mjs'],
  outfile: path.join(outDir, 'main.mjs'),
  bundle: true,
  platform: 'node',
  target: 'node18',
  format: 'esm',
  minify: true,
  legalComments: 'none',
  // Optional media helpers Baileys never needs for text-only use.
  external: ['sharp', 'jimp', 'link-preview-js', 'qrcode-terminal', 'audio-decode', '@img/*'],
  banner: { js: "import { createRequire as __cr } from 'node:module'; const require = __cr(import.meta.url);" },
  logLevel: 'warning'
})
fs.writeFileSync(path.join(outDir, 'package.json'), JSON.stringify({ name: 'busymode-engine', type: 'module', main: 'main.mjs' }))
console.log('Engine bundled to', outDir, (fs.statSync(path.join(outDir, 'main.mjs')).size / 1024 / 1024).toFixed(2), 'MB')
