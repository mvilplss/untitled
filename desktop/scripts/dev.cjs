#!/usr/bin/env node
'use strict'

const { spawn, execSync } = require('node:child_process')
const http = require('node:http')
const path = require('node:path')

const desktopDir = __dirname.replace(/[\\/]scripts$/, '')
const repoRoot = path.resolve(desktopDir, '..')
const frontendDir = path.join(repoRoot, 'frontend')

const procs = []
let cleaned = false

function cleanup(code = 0) {
  if (cleaned) return
  cleaned = true
  for (const p of procs) {
    try { p.kill('SIGTERM') } catch {}
  }
  setTimeout(() => process.exit(code), 200)
}

process.on('SIGINT', () => cleanup(0))
process.on('SIGTERM', () => cleanup(0))
process.on('exit', () => cleanup(0))

function waitForUrl(url, timeoutMs = 30000, label = 'service') {
  return new Promise((resolve) => {
    const start = Date.now()
    const tick = () => {
      const req = http.get(url, (res) => {
        res.resume()
        resolve(true)
      })
      req.on('error', () => {
        if (Date.now() - start > timeoutMs) return resolve(false)
        setTimeout(tick, 300)
      })
      req.setTimeout(2000, () => req.destroy(new Error('timeout')))
    }
    tick()
  })
}

async function main() {
  console.log('[dev] 1/3 打包后端 JAR 到 resources/backend.jar ...')
  execSync('node scripts/prepare-backend.cjs', { cwd: desktopDir, stdio: 'inherit' })

  console.log('[dev] 2/3 启动 vite dev server ...')
  const vite = spawn('npm', ['run', 'dev'], {
    cwd: frontendDir,
    stdio: 'inherit',
    env: { ...process.env, BROWSER: 'none' },
  })
  procs.push(vite)

  const viteOk = await waitForUrl('http://localhost:5173', 30000)
  if (!viteOk) {
    console.error('[dev] vite 30 秒内未就绪')
    cleanup(1)
    return
  }
  console.log('[dev] vite 已就绪，启动 Electron ...')

  const electronBin = path.join(desktopDir, 'node_modules', '.bin', 'electron')
  const electron = spawn(electronBin, ['.'], {
    cwd: desktopDir,
    stdio: 'inherit',
    env: {
      ...process.env,
      VITE_DEV_SERVER_URL: 'http://localhost:5173',
      ELECTRON_DISABLE_SECURITY_WARNINGS: '1',
    },
  })
  procs.push(electron)
  electron.on('exit', (code) => cleanup(code ?? 0))
}

main().catch((err) => {
  console.error('[dev] failed:', err)
  cleanup(1)
})