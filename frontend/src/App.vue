<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentsStore } from '@/stores/agents'
import { useSkillsStore } from '@/stores/skills'
import SideNav from '@/components/SideNav.vue'
import DesktopSettings from '@/components/DesktopSettings.vue'
import type { DesktopBackendStatus } from '@/types/electron'
import { inElectron } from '@/utils/env'

const route = useRoute()
const router = useRouter()
const agentsStore = useAgentsStore()
const skillsStore = useSkillsStore()

const pageTitle = computed(() => (route.meta.title as string) || '')

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
const statusClass = computed(() => `status-dot--${backendStatus.value.status}`)

const searchPlaceholder = computed(() => {
  if (route.path.startsWith('/chat')) return '搜索会话 / 数字人 / 工具（试试「报销」）'
  if (route.path.startsWith('/skills')) return '搜索技能 / 工具（试试「OCR」）'
  return '搜索数字人 / 技能 / 工具（试试「报销」）'
})

function openSettings() { settingsOpen.value = true }

function goSettings() { router.push('/skills') }

onMounted(() => {
  if (inElectron && window.electronAPI) {
    window.electronAPI.getBackendStatus().then((s) => { backendStatus.value = s })
    unsubStatus = window.electronAPI.onBackendStatus((s) => { backendStatus.value = s })
  }
})

onBeforeUnmount(() => {
  if (unsubStatus) unsubStatus()
})

// suppress unused-warning for kept-imports we still want on the store scope
void agentsStore
void skillsStore
void goSettings
</script>

<template>
  <div class="app">
    <!-- top bar -->
    <header class="topbar">
      <div class="topbar__brand">
        <div class="topbar__mark">DH</div>
        <span class="topbar__brand-name">数字人平台</span>
        <span class="topbar__pill">控制台 · v1.0</span>
      </div>
      <div class="topbar__search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-4-4" />
        </svg>
        <input :placeholder="searchPlaceholder" />
      </div>
      <div class="topbar__right">
        <span class="topbar__metric">
          本月总消耗 <b>¥ 28,641</b>
        </span>
        <span class="topbar__sep">|</span>
        <span class="topbar__user">李敏 · 财务部</span>
        <div class="topbar__avatar" title="李敏">李</div>
        <span v-if="inElectron" class="topbar__runtime" :title="`后端 ${statusLabel}`">
          <span class="runtime-dot" :class="statusClass"></span>
          {{ statusLabel }}
        </span>
        <button v-if="inElectron" class="topbar__settings" title="后端设置" @click="openSettings">⚙</button>
      </div>
    </header>

    <!-- layout: side nav + main -->
    <div class="layout">
      <SideNav />
      <main class="main">
        <header class="page-header">
          <div class="page-header__title">
            <span class="page-header__crumb">数字人平台 /</span>
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
    </div>

    <DesktopSettings v-model:visible="settingsOpen" />
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* ============== topbar ============== */
.topbar {
  height: 56px;
  flex: 0 0 56px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 20px;
  z-index: 20;
}

.topbar__brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-weight: 650;
  font-size: var(--fs-15);
  letter-spacing: 0.2px;
}
.topbar__mark {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.topbar__brand-name { color: var(--txt); }
.topbar__pill {
  background: var(--primary-soft);
  color: var(--primary);
  padding: 3px 9px;
  border-radius: 20px;
  font-size: var(--fs-12);
  font-weight: 600;
  margin-left: 4px;
}

.topbar__search {
  flex: 1;
  max-width: 420px;
  margin-left: 14px;
  position: relative;
}
.topbar__search input {
  width: 100%;
  height: 34px;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 0 12px 0 32px;
  background: #fafbfc;
  font-size: var(--fs-13);
  color: var(--txt);
  outline: none;
  font-family: inherit;
}
.topbar__search input:focus {
  border-color: var(--primary);
  background: #fff;
}
.topbar__search svg {
  position: absolute;
  left: 10px;
  top: 9px;
  opacity: 0.45;
}

.topbar__right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--txt2);
  font-size: var(--fs-13);
}
.topbar__metric b { color: var(--txt); font-weight: 650; }
.topbar__sep { color: var(--line); }
.topbar__user { color: var(--txt2); }
.topbar__avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f59e0b, #f97316);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.topbar__runtime {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-12);
  color: var(--txt2);
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 3px 8px;
  border-radius: 20px;
}
.runtime-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--txt3);
}
.runtime-dot--ready    { background: var(--green); }
.runtime-dot--starting { background: #d6a300; }
.runtime-dot--error    { background: var(--red); }
.runtime-dot--idle     { background: var(--txt3); }
.topbar__settings {
  width: 30px;
  height: 30px;
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  color: var(--txt2);
  font-size: 14px;
}
.topbar__settings:hover { color: var(--txt); border-color: var(--txt2); }

/* ============== layout ============== */
.layout {
  flex: 1;
  display: flex;
  min-height: 0;
}
.main {
  flex: 1;
  overflow-y: auto;
  padding: 22px 26px 60px;
  background: var(--bg);
  min-width: 0;
}

/* ============== page header ============== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
  gap: 14px;
}
.page-header__title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.page-header__crumb {
  font-size: var(--fs-12);
  color: var(--txt2);
}
.page-header__h {
  margin: 0;
  font-size: var(--fs-19);
  font-weight: 650;
  letter-spacing: 0.2px;
  color: var(--txt);
}
.page-header__extra {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-body { min-height: 0; }
</style>
