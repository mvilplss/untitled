'use strict'

const { spawn } = require('node:child_process')
const path = require('node:path')
const http = require('node:http')
const fs = require('node:fs')

let proc = null
let status = 'idle' // 'idle' | 'starting' | 'ready' | 'error'
let port = 8080
let onStatus = () => {}
let lastError = null
let stopping = false // 主动 stop 期间为 true；用于区分「主动关闭」vs「意外退出」

function emit() {
  try {
    onStatus({ status, port, error: lastError })
  } catch (err) {
    console.error('[sidecar] onStatus callback threw:', err && err.message)
  }
}

function setStatus(s, err) {
  status = s
  lastError = err || null
  emit()
}

function waitForBackend(timeoutMs) {
  const start = Date.now()
  const probe = `http://localhost:${port}/api/agents`
  return new Promise((resolve) => {
    const tick = () => {
      if (Date.now() - start > timeoutMs) return resolve(false)
      if (proc && proc.exitCode !== null) return resolve(false)
      const req = http.get(probe, (res) => {
        res.resume()
        if (res.statusCode && res.statusCode < 500) return resolve(true)
        setTimeout(tick, 500)
      })
      req.on('error', () => setTimeout(tick, 500))
      req.setTimeout(2000, () => req.destroy(new Error('probe-timeout')))
    }
    tick()
  })
}

function start(opts) {
  if (proc) {
    return Promise.reject(new Error('Sidecar 已经在运行'))
  }
  const { jarPath, port: p, onStatus: cb, cwd } = opts || {}
  if (!jarPath) return Promise.reject(new Error('jarPath is required'))
  port = Number(p) || 8080
  onStatus = cb || (() => {})

  if (!fs.existsSync(jarPath)) {
    const msg = `后端 JAR 不存在：${jarPath}\n请先执行 npm run prepare:backend`
    setStatus('error', msg)
    return Promise.reject(new Error(msg))
  }

  setStatus('starting')

  return new Promise((resolve, reject) => {
    let exited = false
    proc = spawn(
      'java',
      [
        '-Xms128m',
        '-Xmx768m',
        '-Dfile.encoding=UTF-8',
        '-jar', jarPath,
        `--server.port=${port}`,
        '--spring.profiles.active=local',
      ],
      {
        cwd: cwd || path.dirname(jarPath),
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env },
      },
    )

    // 同时把 java 的 stdout/stderr 持久化到 userData 下的 sidecar.log，方便定位钉钉等异步问题
    let logStream = null
    try {
      const logPath = path.join(cwd || path.dirname(jarPath), 'sidecar.log')
      logStream = fs.createWriteStream(logPath, { flags: 'a' })
      logStream.write(`\n========== sidecar started at ${new Date().toISOString()} ==========\n`)
    } catch (err) {
      console.error('[sidecar] cannot open sidecar.log:', err && err.message)
    }

    proc.stdout.on('data', (d) => {
      process.stdout.write(`[sidecar:out] ${d}`)
      if (logStream) logStream.write(d)
    })
    proc.stderr.on('data', (d) => {
      process.stderr.write(`[sidecar:err] ${d}`)
      if (logStream) logStream.write(d)
    })

    proc.on('error', (err) => {
      proc = null
      const msg = `无法启动 java 进程：${err.message}\n请确认系统已安装 JDK 17+，且 java 在 PATH 中`
      setStatus('error', msg)
      reject(new Error(msg))
    })

    proc.on('exit', (code, signal) => {
      exited = true
      proc = null
      const wasReady = status === 'ready'
      if (stopping) {
        // 主动 stop 触发的退出：state 由 stop() 末尾负责重置，不上抛 error
        stopping = false
        if (!wasReady) reject(new Error('Sidecar 启动失败被取消'))
        return
      }
      const msg = `后端进程退出（code=${code}, signal=${signal}）`
      setStatus('error', msg)
      if (!wasReady) reject(new Error(msg))
    })

    waitForBackend(60000).then((ok) => {
      if (exited) return
      if (ok) {
        setStatus('ready')
        resolve()
      } else {
        try { proc && proc.kill('SIGTERM') } catch {}
        proc = null
        setStatus('error', `后端在 60 秒内未就绪（端口 ${port}）`)
        reject(new Error(lastError))
      }
    })
  })
}

function stop() {
  return new Promise((resolve) => {
    if (!proc) return resolve()
    const p = proc
    proc = null
    stopping = true
    const done = () => {
      setStatus('idle')
      resolve()
    }
    p.once('exit', done)
    try { p.kill('SIGTERM') } catch {}
    setTimeout(() => {
      try { p.kill('SIGKILL') } catch {}
      done()
    }, 3000)
  })
}

function getLogPath(cwd) {
  return path.join(cwd || path.dirname(jarPath || ''), 'sidecar.log')
}

async function restart(opts) {
  await stop()
  if (!opts) {
    setStatus('idle')
    return
  }
  return start(opts)
}

function getStatus() {
  return { status, port, error: lastError }
}

module.exports = { start, stop, restart, getStatus }