<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentsStore } from '@/stores/agents'
import { useSkillsStore } from '@/stores/skills'
import DesktopSettings from '@/components/DesktopSettings.vue'
import type { DesktopBackendStatus } from '@/types/electron'

const route = useRoute()
const router = useRouter()
const agentsStore = useAgentsStore()
const skillsStore = useSkillsStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string) || '')

interface NavItem {
  index: string
  label: string
  key: string
  count: () => number | null
}

const nav: NavItem[] = [
  { index: '/digital-humans', label: '数字人', key: '01', count: () => agentsStore.list.length },
  { index: '/chat',   label: '对话',   key: '02', count: () => null },
  { index: '/skills', label: '技能', key: '03', count: () => skillsStore.list.length },
]

function go(path: string) {
  router.push(path)
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

const inElectron = typeof window !== 'undefined' && !!window.electronAPI
const settingsOpen = ref(false)
const backendStatus = ref<DesktopBackendStatus>({ status: 'idle', error: null })
let unsubStatus: (() => void) | null = null

const statusLabel = computed(() => {
  switch (backendStatus.value.status) {
    case 'ready': return '已连接'
    case 'starting': return '启动中'
    case 'error': return '异常'
    default: return '空闲'
  }
})

const statusClass = computed(() => `app-sidebar__dot--${backendStatus.value.status}`)

function openSettings() { settingsOpen.value = true }

onMounted(() => {
  if (inElectron && window.electronAPI) {
    window.electronAPI.getBackendStatus().then((s) => { backendStatus.value = s })
    unsubStatus = window.electronAPI.onBackendStatus((s) => { backendStatus.value = s })
  }
})

onBeforeUnmount(() => {
  if (unsubStatus) unsubStatus()
})
</script>

<template>
  <div class="app-layout">
    <aside class="app-sidebar">
      <div class="app-sidebar__brand">
        <div class="app-sidebar__brand-mark mono">▮</div>
        <div class="app-sidebar__brand-text">
          <div class="app-sidebar__brand-name">数字人平台</div>
          <div class="app-sidebar__brand-sub mono">控制台 · v1.0</div>
        </div>
      </div>

      <div class="app-sidebar__section">
        <div class="app-sidebar__section-head mono">// 工作区</div>
        <nav class="app-sidebar__nav">
          <a
            v-for="item in nav"
            :key="item.index"
            class="app-sidebar__nav-item"
            :class="{ 'is-active': activeMenu === item.index }"
            :href="item.index"
            @click.prevent="go(item.index)"
          >
            <span class="app-sidebar__nav-indicator"></span>
            <span class="app-sidebar__nav-key mono">{{ item.key }}</span>
            <span class="app-sidebar__nav-label">{{ item.label }}</span>
            <span
              v-if="item.count() !== null"
              class="app-sidebar__nav-count mono"
            >{{ pad2(item.count() ?? 0) }}</span>
            <span v-else class="app-sidebar__nav-count app-sidebar__nav-count--placeholder mono">—</span>
          </a>
        </nav>
      </div>

      <div class="app-sidebar__section app-sidebar__section--foot">
        <div class="app-sidebar__section-head mono">// 运行时</div>
        <dl class="app-sidebar__status mono">
          <div class="app-sidebar__status-row">
            <dt>后端</dt>
            <dd v-if="inElectron">
              <span :class="['app-sidebar__dot', statusClass]"></span>
              {{ statusLabel }}
            </dd>
            <dd v-else>:8080 · web</dd>
          </div>
          <div class="app-sidebar__status-row">
            <dt>运行环境</dt>
            <dd>{{ inElectron ? 'electron' : 'jdk25 · vue3' }}</dd>
          </div>
          <div class="app-sidebar__status-row">
            <dt>构建</dt>
            <dd>
              <span class="app-sidebar__dot app-sidebar__dot--ok"></span>
              dev
            </dd>
          </div>
          <div v-if="inElectron" class="app-sidebar__status-row">
            <dt></dt>
            <dd>
              <button class="app-sidebar__settings mono" @click="openSettings" title="设置">
                ⚙ 设置
              </button>
            </dd>
          </div>
        </dl>
      </div>
    </aside>

    <main class="app-content">
      <header class="page-header">
        <div class="page-header__title">
          <span class="page-header__crumb mono">数字人平台 /</span>
          <h2 class="page-header__h">{{ pageTitle }}</h2>
        </div>
        <div class="page-header__extra">
          <slot name="header-extra" />
        </div>
      </header>
      <div class="page-body">
        <router-view />
      </div>
    </main>

    <DesktopSettings v-model:visible="settingsOpen" />
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  background: var(--bg-base);
  color: var(--text);
}

/* ---------- sidebar ---------- */
.app-sidebar {
  width: 224px;
  flex-shrink: 0;
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.app-sidebar__brand {
  padding: 16px 16px 14px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border-bottom: 1px solid var(--border);
}
.app-sidebar__brand-mark {
  font-size: 16px;
  color: var(--signal);
  line-height: 1.1;
  padding-top: 1px;
}
.app-sidebar__brand-text { flex: 1; min-width: 0; }
.app-sidebar__brand-name {
  font-family: var(--font-sans);
  font-weight: 600;
  font-size: 13px;
  color: var(--text);
  line-height: 1.2;
  letter-spacing: -0.01em;
}
.app-sidebar__brand-sub {
  font-size: 11px;
  color: var(--text-mute);
  margin-top: 3px;
}

.app-sidebar__section {
  padding: 14px 0 12px;
  display: flex;
  flex-direction: column;
}
.app-sidebar__section--foot {
  margin-top: auto;
  border-top: 1px solid var(--border);
  padding-bottom: 14px;
}
.app-sidebar__section-head {
  padding: 0 16px;
  font-size: 11px;
  color: var(--text-mute);
  margin-bottom: 6px;
}

.app-sidebar__nav {
  display: flex;
  flex-direction: column;
}

.app-sidebar__nav-item {
  position: relative;
  display: grid;
  grid-template-columns: 3px 22px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  color: var(--text-mute);
  text-decoration: none;
  font-size: 13px;
  transition: background-color 0.08s ease, color 0.08s ease;
}
.app-sidebar__nav-item:hover {
  color: var(--text);
  background: var(--bg-elevated);
}
.app-sidebar__nav-indicator {
  width: 2px;
  height: 14px;
  background: transparent;
  justify-self: center;
}
.app-sidebar__nav-item.is-active {
  color: var(--text);
  background: var(--bg-elevated);
}
.app-sidebar__nav-item.is-active .app-sidebar__nav-indicator {
  background: var(--signal);
}
.app-sidebar__nav-key {
  font-size: 11px;
  color: var(--text-faint);
}
.app-sidebar__nav-item.is-active .app-sidebar__nav-key { color: var(--text-mute); }
.app-sidebar__nav-label { font-weight: 500; }
.app-sidebar__nav-count {
  font-size: 11px;
  color: var(--text-faint);
}
.app-sidebar__nav-item.is-active .app-sidebar__nav-count { color: var(--text-mute); }
.app-sidebar__nav-count--placeholder { color: var(--border); }

/* status box */
.app-sidebar__status {
  margin: 0 16px;
  display: flex;
  flex-direction: column;
  font-size: 11px;
}
.app-sidebar__status-row {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  border-bottom: 1px dashed var(--border-soft);
  color: var(--text-mute);
}
.app-sidebar__status-row:last-child { border-bottom: none; }
.app-sidebar__status-row dd {
  margin: 0;
  color: var(--text);
  display: flex;
  align-items: center;
  gap: 6px;
}
.app-sidebar__status-row dt { color: var(--text-mute); }

.app-sidebar__dot {
  width: 6px;
  height: 6px;
  display: inline-block;
  background: var(--text-faint);
}
.app-sidebar__dot--ok { background: var(--ok); }
.app-sidebar__dot--ready { background: var(--ok); }
.app-sidebar__dot--starting { background: #d6a300; }
.app-sidebar__dot--error { background: var(--err); }
.app-sidebar__dot--idle { background: var(--text-faint); }

.app-sidebar__settings {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 3px 8px;
  cursor: pointer;
  transition: color 0.1s ease, border-color 0.1s ease;
}
.app-sidebar__settings:hover {
  color: var(--text);
  border-color: var(--text-mute);
}

/* ---------- main column ---------- */
.app-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-base);
  min-width: 0;
}

.page-header {
  padding: 12px 24px;
  background: var(--bg-base);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
}
.page-header__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.page-header__crumb {
  font-size: 12px;
  color: var(--text-mute);
}
.page-header__h {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  letter-spacing: -0.005em;
}
.page-header__extra {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-body {
  flex: 1;
  padding: 24px;
  overflow: auto;
}
</style>
