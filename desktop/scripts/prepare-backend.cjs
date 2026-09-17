#!/usr/bin/env node
'use strict'

const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const desktopDir = __dirname.replace(/[\\/]scripts$/, '')
const repoRoot = path.resolve(desktopDir, '..')
const targetDir = path.join(repoRoot, 'target')
const destJar = path.join(desktopDir, 'resources', 'backend.jar')

console.log('[prepare-backend] mvn -DskipTests package ...')
execSync('mvn -q -DskipTests package', { cwd: repoRoot, stdio: 'inherit' })

if (!fs.existsSync(targetDir)) {
  console.error('[prepare-backend] target/ 不存在，mvn 构建失败')
  process.exit(1)
}

const candidates = fs
  .readdirSync(targetDir)
  .filter((f) => f.startsWith('untitled-') && f.endsWith('.jar') && !f.endsWith('.original'))

if (candidates.length === 0) {
  console.error('[prepare-backend] 未找到可执行 JAR（untitled-*.jar）')
  process.exit(1)
}

candidates.sort()
const latest = candidates[candidates.length - 1]
const srcJar = path.join(targetDir, latest)

fs.mkdirSync(path.dirname(destJar), { recursive: true })
fs.copyFileSync(srcJar, destJar)
console.log(`[prepare-backend] 已拷贝 ${srcJar} → ${destJar}`)