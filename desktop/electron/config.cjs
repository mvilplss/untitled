'use strict'

const { app } = require('electron')
const fs = require('node:fs')
const path = require('node:path')

const DEFAULT = Object.freeze({
  mode: 'sidecar',
  sidecarPort: 8080,
  remoteUrl: 'http://localhost:8080',
})

function configPath() {
  return path.join(app.getPath('userData'), 'config.json')
}

function load() {
  try {
    const p = configPath()
    if (!fs.existsSync(p)) return { ...DEFAULT }
    const raw = fs.readFileSync(p, 'utf8')
    const parsed = JSON.parse(raw)
    return { ...DEFAULT, ...parsed, sidecarPort: Number(parsed.sidecarPort) || DEFAULT.sidecarPort }
  } catch (err) {
    console.error('[config] load failed, fallback to defaults:', err && err.message)
    return { ...DEFAULT }
  }
}

function save(cfg) {
  const merged = {
    ...DEFAULT,
    ...cfg,
    sidecarPort: Number(cfg && cfg.sidecarPort) || DEFAULT.sidecarPort,
  }
  const p = configPath()
  fs.mkdirSync(path.dirname(p), { recursive: true })
  fs.writeFileSync(p, JSON.stringify(merged, null, 2), 'utf8')
  return merged
}

module.exports = { load, save, DEFAULT }