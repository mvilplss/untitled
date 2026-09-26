<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDwsStore } from '@/stores/dws'
import UButton from '@/components/ui/UButton.vue'

const store = useDwsStore()

const selected = ref<string>('')
const refreshing = ref(false)

onMounted(() => store.loadProfiles())

async function refresh() {
  refreshing.value = true
  try {
    await store.loadProfiles()
  } finally {
    refreshing.value = false
  }
}

async function switchTo(selector: string) {
  if (selector === selected.value) return
  try {
    await store.switchProfile(selector)
    selected.value = selector
    ElMessage.success('已切换默认 profile')
  } catch (e) {
    /* axios interceptor */
  }
}

async function removeOne(corpId: string, dwsUserId: string) {
  try {
    await ElMessageBox.confirm(
      `确认删除钉钉 profile ${corpId}:${dwsUserId}？该账号将退出登录。`,
      '删除 profile',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  try {
    await store.removeProfile(corpId, dwsUserId)
    ElMessage.success('已删除')
  } catch { /* ignore */ }
}

function fmtTs(ts?: number | null) {
  if (!ts) return '—'
  try { return new Date(ts).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) } catch { return '—' }
}
</script>

<template>
  <div class="dws-prof">
    <header class="dws-prof__head">
      <div class="dws-prof__title">已登录的 DWS 账号</div>
      <UButton variant="ghost" :disabled="refreshing" @click="refresh">
        {{ refreshing ? '刷新中…' : '刷新' }}
      </UButton>
    </header>

    <div v-if="!store.profiles.length" class="dws-prof__empty">
      当前 userId 暂未授权，或 dws 还未生成 profile 数据。
    </div>

    <ul v-else class="dws-prof__list">
      <li
        v-for="p in store.profiles"
        :key="`${p.corpId}:${p.dwsUserId}`"
        class="dws-prof__row"
        :class="{ 'dws-prof__row--active': selected === `${p.corpId}:${p.dwsUserId}` }"
      >
        <div class="dws-prof__main">
          <div class="dws-prof__name">
            <b>{{ p.userName || p.dwsUserId || '钉钉账号' }}</b>
            <span class="dws-prof__corp">@ {{ p.corpName || p.corpId }}</span>
          </div>
          <div class="dws-prof__meta">
            selector: <code>{{ p.corpId }}:{{ p.dwsUserId }}</code>
            · 状态：<b>{{ p.status }}</b>
          </div>
        </div>
        <div class="dws-prof__ops">
          <UButton
            v-if="selected !== `${p.corpId}:${p.dwsUserId}`"
            variant="ghost"
            @click="switchTo(`${p.corpId}:${p.dwsUserId}`)"
          >设为默认</UButton>
          <span v-else class="dws-prof__default">默认</span>
          <UButton variant="ghost" @click="removeOne(p.corpId, p.dwsUserId)">删除</UButton>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.dws-prof {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px;
}
.dws-prof__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.dws-prof__title {
  font-size: var(--fs-14);
  font-weight: 650;
  color: var(--txt);
}
.dws-prof__empty {
  padding: 20px;
  text-align: center;
  color: var(--txt2);
  font-size: var(--fs-12);
  border: 1px dashed var(--line);
  border-radius: 8px;
}
.dws-prof__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dws-prof__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  gap: 12px;
}
.dws-prof__row--active {
  border-color: var(--primary);
  background: var(--primary-soft, #eef2ff);
}
.dws-prof__name { font-size: var(--fs-14); color: var(--txt); }
.dws-prof__corp { color: var(--txt2); margin-left: 6px; font-size: var(--fs-12); }
.dws-prof__meta {
  font-size: var(--fs-12);
  color: var(--txt3);
  margin-top: 4px;
}
.dws-prof__meta code {
  background: rgba(0,0,0,0.04);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
.dws-prof__ops {
  display: flex;
  gap: 6px;
  align-items: center;
}
.dws-prof__default {
  font-size: var(--fs-12);
  color: var(--primary);
  font-weight: 600;
}
</style>
