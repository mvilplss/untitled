<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { DesktopBackendStatus, DesktopConfig } from '@/types/electron'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const inElectron = computed(() => typeof window !== 'undefined' && !!window.electronAPI)

const visible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

const config = ref<DesktopConfig>({ mode: 'sidecar', sidecarPort: 8080, remoteUrl: 'http://localhost:8080' })
const status = ref<DesktopBackendStatus>({ status: 'idle', port: 8080, error: null })
const saving = ref(false)
const restarting = ref(false)

let unsubStatus: (() => void) | null = null

async function refresh() {
  if (!window.electronAPI) return
  config.value = await window.electronAPI.getConfig()
  status.value = await window.electronAPI.getBackendStatus()
}

async function save() {
  if (!window.electronAPI) return
  saving.value = true
  try {
    // IPC structuredClone 无法克隆 Vue reactive Proxy；序列化为纯对象
    const plain = JSON.parse(JSON.stringify(config.value))
    const saved = await window.electronAPI.setConfig(plain)
    config.value = saved
    ElMessage.success('已保存设置')
  } catch (e: any) {
    ElMessage.error(`保存失败：${e?.message || e}`)
  } finally {
    saving.value = false
  }
}

async function restart() {
  if (!window.electronAPI) return
  restarting.value = true
  try {
    const next = await window.electronAPI.restartBackend()
    status.value = next
    if (next.status === 'ready') {
      ElMessage.success('后端已就绪')
    } else if (next.error) {
      ElMessage.warning(`后端异常：${next.error}`)
    }
  } catch (e: any) {
    ElMessage.error(`重启失败：${e?.message || e}`)
  } finally {
    restarting.value = false
  }
}

function close() { visible.value = false }

watch(
  () => props.visible,
  (v) => { if (v) refresh() },
)

onMounted(() => {
  if (!window.electronAPI) return
  refresh()
  unsubStatus = window.electronAPI.onBackendStatus((s) => { status.value = s })
})

onBeforeUnmount(() => {
  if (unsubStatus) unsubStatus()
})

const statusLabel = computed(() => {
  switch (status.value.status) {
    case 'ready': return '已连接'
    case 'starting': return '启动中'
    case 'error': return '异常'
    default: return '空闲'
  }
})

const statusClass = computed(() => `status-pill--${status.value.status}`)
</script>

<template>
  <transition name="ds-fade">
    <div v-if="visible" class="ds-mask" @click.self="close">
      <div class="ds-drawer" role="dialog" aria-modal="true">
        <header class="ds-head">
          <div>
            <div class="ds-crumb mono">// 桌面客户端</div>
            <div class="ds-title">设置</div>
          </div>
          <button class="ds-close mono" @click="close">关闭 ×</button>
        </header>

        <div class="ds-body">
          <section class="ds-section">
            <div class="ds-section-head mono">// 后端连接状态</div>
            <div class="ds-status-row">
              <span :class="['ds-status-dot', statusClass]"></span>
              <span class="ds-status-label mono">{{ statusLabel }}</span>
              <span class="ds-status-port mono">:{{ status.port || config.sidecarPort }}</span>
              <span v-if="status.error" class="ds-status-err mono" :title="status.error">⚠ {{ status.error }}</span>
            </div>
          </section>

          <section class="ds-section">
            <div class="ds-section-head mono">// 后端模式</div>
            <el-radio-group v-model="config.mode">
              <el-radio-button value="sidecar">本地 Sidecar</el-radio-button>
              <el-radio-button value="remote">远程地址</el-radio-button>
            </el-radio-group>
            <div class="ds-hint mono">
              sidecar 模式：主进程自动启停 <code>resources/backend.jar</code>。<br />
              remote 模式：填后端地址，自己保证对方可用。
            </div>
          </section>

          <section v-if="config.mode === 'sidecar'" class="ds-section">
            <div class="ds-section-head mono">// Sidecar 端口</div>
            <el-input v-model.number="config.sidecarPort" type="number" :min="1" :max="65535" />
          </section>

          <section v-else class="ds-section">
            <div class="ds-section-head mono">// 远程地址</div>
            <el-input v-model="config.remoteUrl" placeholder="http://host:port" />
            <div class="ds-hint mono">支持局域网/公网；不要带尾部 <code>/</code>。</div>
          </section>

          <section class="ds-section ds-section--actions">
            <button class="btn-ghost mono" :disabled="!inElectron" @click="refresh">刷新状态</button>
            <button class="btn-primary" :disabled="saving" @click="save">
              <span class="btn-primary__caret">+</span>{{ saving ? '保存中' : '保存设置' }}
            </button>
            <button
              class="btn-ghost mono"
              :disabled="restarting || config.mode !== 'sidecar'"
              @click="restart"
            >
              {{ restarting ? '重启中' : '重启后端' }}
            </button>
          </section>
        </div>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.ds-mask {
  position: fixed;
  inset: 0;
  background: rgba(54, 69, 79, 0.4);
  z-index: var(--z-overlay);
  display: flex;
  justify-content: flex-end;
}

.ds-drawer {
  width: min(560px, 100%);
  height: 100%;
  background: var(--bg-base);
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.ds-head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  background: var(--bg-surface);
}

.ds-crumb {
  font-size: 11px;
  color: var(--text-mute);
  margin-bottom: 4px;
}

.ds-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text);
  letter-spacing: -0.01em;
}

.ds-close {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 6px 10px;
  cursor: pointer;
}

.ds-close:hover { color: var(--text); border-color: var(--text-mute); }

.ds-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.ds-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-soft);
}

.ds-section:last-child { border-bottom: none; }

.ds-section-head {
  font-size: 11px;
  color: var(--text-mute);
}

.ds-hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.6;
}

.ds-hint code {
  font-family: var(--font-mono);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  padding: 0 4px;
  font-size: 11px;
  color: var(--text);
}

.ds-section--actions {
  flex-direction: row;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.ds-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
}

.ds-status-dot {
  width: 8px;
  height: 8px;
  display: inline-block;
}

.ds-status-dot.status-pill--ready { background: var(--ok); }
.ds-status-dot.status-pill--starting { background: #d6a300; }
.ds-status-dot.status-pill--error { background: var(--err); }
.ds-status-dot.status-pill--idle { background: var(--text-faint); }

.ds-status-label {
  font-size: 12px;
  color: var(--text);
}

.ds-status-port {
  font-size: 11px;
  color: var(--text-mute);
}

.ds-status-err {
  font-size: 11px;
  color: var(--err);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.btn-primary {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--bg-base);
  background: var(--signal);
  border: 1px solid var(--signal);
  padding: 7px 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-primary__caret { font-family: var(--font-mono); font-size: 14px; }

.btn-ghost {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 6px 12px;
  cursor: pointer;
}

.btn-ghost:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-ghost:hover:not(:disabled) { color: var(--text); border-color: var(--text-mute); }

.ds-fade-enter-active, .ds-fade-leave-active { transition: opacity 0.12s ease; }
.ds-fade-enter-active .ds-drawer, .ds-fade-leave-active .ds-drawer { transition: transform 0.18s ease; }
.ds-fade-enter-from, .ds-fade-leave-to { opacity: 0; }
.ds-fade-enter-from .ds-drawer, .ds-fade-leave-to .ds-drawer { transform: translateX(100%); }
</style>