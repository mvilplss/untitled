#!/usr/bin/env node
'use strict'

const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const desktopDir = __dirname.replace(/[\\/]scripts$/, '')
const repoRoot = path.resolve(desktopDir, '..')
const frontendDir = path.join(repoRoot, 'frontend')
const frontendDist = path.join(frontendDir, 'dist')
const destDir = path.join(desktopDir, 'resources', 'app')

if (!fs.existsSync(path.join(frontendDir, 'node_modules'))) {
  console.error('[prepare-frontend] frontend/node_modules 不存在，请先在 frontend/ 执行 npm install')
  process.exit(1)
}

console.log('[prepare-frontend] npm run build ...')
execSync('npm run build', { cwd: frontendDir, stdio: 'inherit' })

if (!fs.existsSync(frontendDist)) {
  console.error('[prepare-frontend] 构建失败：frontend/dist 未生成')
  process.exit(1)
}

fs.rmSync(destDir, { recursive: true, force: true })
fs.mkdirSync(destDir, { recursive: true })
fs.cpSync(frontendDist, destDir, { recursive: true })
console.log(`[prepare-frontend] 已拷贝 → ${destDir}`)