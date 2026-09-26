<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as dwsApi from '@/api/dws'
import { useDwsStore } from '@/stores/dws'
import UButton from '@/components/ui/UButton.vue'
import type { DwsAuthPhase, DwsFlowStateEvent } from '@/types/dws'

const store = useDwsStore()

const displayName = ref<string>('')
const submitting = ref(false)
let currentES: EventSource | null = null
let countdownTimer: number | null = null

const expiresInLabel = ref<string>('—')

const showCountdown = computed(() => store.flowExpiresAt > Date.now())
const isExpired = computed(() => store.phase === 'expired')
const isApproved = computed(() => store.phase === 'approved')
const isFailed = computed(() => store.phase === 'failed')

onMounted(async () => {
  await store.refreshStatus()
  await store.loadProfiles()
  if (store.isWaiting && store.userId) startStream(store.userId)
  startCountdown()
})

onBeforeUnmount(() => {
  if (currentES) currentES.close()
  if (countdownTimer) window.clearInterval(countdownTimer)
})

function startCountdown() {
  if (countdownTimer) window.clearInterval(countdownTimer)
  countdownTimer = window.setInterval(() => {
    if (!store.flowExpiresAt) {
      expiresInLabel.value = '—'
      return
    }
    const left = store.flowExpiresAt - Date.now()
    if (left <= 0) {
      expiresInLabel.value = '已过期'
      return
    }
    const m = Math.floor(left / 60000)
    const s = Math.floor((left % 60000) / 1000)
    expiresInLabel.value = `${m}:${s.toString().padStart(2, '0')}`
  }, 1000)
}

async function startAuth() {
  if (!displayName.value.trim()) {
    ElMessage.warning('请输入账号显示名')
    return
  }
  submitting.value = true
  try {
    const resp = await store.startDeviceFlow(displayName.value.trim())
    ElMessage.success('已生成设备码，请在浏览器完成授权')
    if (resp.userCode) startStream(resp.userId)
  } catch (e) {
    /* axios interceptor 已提示 */
  } finally {
    submitting.value = false
  }
}

function startStream(userId: string) {
  if (currentES) currentES.close()
  currentES = dwsApi.openAuthStream(userId, {
    onState: (state: DwsFlowStateEvent) => {
      const ph = state.phase as DwsAuthPhase | undefined
      if (ph) store.phase = ph
      if (state.userCode) store.flowUserCode = state.userCode
      if (state.verificationUriComplete) store.flowVerificationUrl = state.verificationUriComplete
      if (state.expiresAt) store.flowExpiresAt = state.expiresAt
    },
    onDone: async (state) => {
      const ph = state.phase as DwsAuthPhase | undefined
      if (ph) store.phase = ph
      await store.loadProfiles()
      await store.loadRegistry()
      ElMessage.success('授权完成，已绑定到当前账号')
    },
    onError: (msg) => {
      ElMessage.warning(`授权流异常：${msg}`)
    },
  })
}

async function copyCode() {
  if (!store.flowUserCode) return
  try {
    await navigator.clipboard.writeText(store.flowUserCode)
    ElMessage.success('设备码已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中')
  }
}

function openVerification() {
  if (!store.flowVerificationUrl) return
  if (window.electronAPI?.openExternal) {
    window.electronAPI.openExternal(store.flowVerificationUrl)
  } else {
    window.open(store.flowVerificationUrl, '_blank', 'noopener,noreferrer')
  }
}

async function doReset() {
  if (!store.userId) return
  try {
    await ElMessageBox.confirm('将清空该账号所有 DWS 授权并删除本地缓存，是否继续？', '重置授权', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await store.reset()
  ElMessage.success('已清空授权')
}

function changeAccount() {
  store.clear()
  displayName.value = ''
}
</script>

<template>
  <div class="dws-auth">
    <header class="dws-auth__head">
      <div>
        <div class="dws-auth__title">钉钉个人账号授权（dws）</div>
        <div class="dws-auth__sub">
          每个账号独立目录隔离。授权走 device-flow，用户在浏览器完成；后台 token 自动落盘并刷新。
        </div>
      </div>
      <UButton v-if="store.userId" variant="ghost" @click="changeAccount">切换账号</UButton>
    </header>

    <!-- 已授权 -->
    <section v-if="isApproved" class="dws-auth__ok">
      <div class="dws-auth__ok-icon">✓</div>
      <div class="dws-auth__ok-body">
        <div class="dws-auth__ok-title">账号已授权</div>
        <div class="dws-auth__ok-line">
          userId：<code>{{ store.userId }}</code>
        </div>
        <div v-if="store.registryEntry?.corpId" class="dws-auth__ok-line">
          corpId：<code>{{ store.registryEntry.corpId }}</code>
        </div>
      </div>
      <UButton variant="ghost" @click="doReset">清空授权</UButton>
    </section>

    <!-- 等待授权 -->
    <section v-else-if="store.isWaiting && store.flowUserCode" class="dws-auth__pending">
      <div class="dws-auth__pending-info">
        <div class="dws-auth__pending-title">请在浏览器完成授权</div>
        <div class="dws-auth__pending-row">
          设备码：<code class="dws-auth__code" @click="copyCode">{{ store.flowUserCode }}</code>
          <UButton variant="ghost" @click="copyCode">复制</UButton>
        </div>
        <div class="dws-auth__pending-row">
          剩余有效：<b>{{ expiresInLabel }}</b>
        </div>
        <div class="dws-auth__pending-row dws-auth__pending-tip">
          方式 1（推荐）：<UButton variant="primary" @click="openVerification">在浏览器打开授权链接</UButton>
        </div>
        <div class="dws-auth__pending-row dws-auth__pending-tip">
          方式 2：在已登录钉钉的手机/电脑上访问 <code>https://login.dingtalk.com</code>，输入上方设备码。
        </div>
      </div>
      <div class="dws-auth__pending-meta">
        userId：<code>{{ store.userId }}</code>
      </div>
    </section>

    <!-- 过期 / 失败 -->
    <section v-else-if="isExpired || isFailed" class="dws-auth__failed">
      <div class="dws-auth__failed-icon">!</div>
      <div class="dws-auth__failed-body">
        <div class="dws-auth__failed-title">
          {{ isExpired ? '设备码已过期' : '授权失败' }}
        </div>
        <div class="dws-auth__failed-line">
          {{ isExpired ? '请重新发起授权。' : (store.flowError || '请检查网络或重试') }}
        </div>
      </div>
    </section>

    <!-- 未发起 / 新账号 -->
    <section v-else class="dws-auth__form">
      <label class="dws-auth__label">
        账号显示名
        <input
          v-model="displayName"
          class="dws-auth__input"
          placeholder="例如 alice@corp"
          maxlength="64"
          @keyup.enter="startAuth"
        />
      </label>
      <div class="dws-auth__hint">
        显示名仅用于本地标识；后端会基于此生成 userId。授权完成后才绑定钉钉 corpId。
      </div>
      <UButton variant="primary" :disabled="submitting" @click="startAuth">
        {{ submitting ? '生成中…' : '去授权（device-flow）' }}
      </UButton>
    </section>
  </div>
</template>

<style scoped>
.dws-auth {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dws-auth__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.dws-auth__title {
  font-size: var(--fs-15);
  font-weight: 650;
  color: var(--txt);
}
.dws-auth__sub {
  font-size: var(--fs-12);
  color: var(--txt2);
  margin-top: 4px;
  line-height: 1.5;
  max-width: 640px;
}

.dws-auth__ok {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  background: var(--primary-soft, #eef2ff);
  border-radius: 10px;
}
.dws-auth__ok-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: var(--fs-15);
}
.dws-auth__ok-title { font-size: var(--fs-14); font-weight: 650; }
.dws-auth__ok-line { font-size: var(--fs-12); color: var(--txt2); margin-top: 3px; }
.dws-auth__ok-line code {
  background: rgba(0,0,0,0.05);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
}

.dws-auth__pending {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: #fff7e6;
  border: 1px solid #f5d791;
  border-radius: 10px;
}
.dws-auth__pending-title {
  font-size: var(--fs-14);
  font-weight: 650;
  color: #b46d00;
  margin-bottom: 4px;
}
.dws-auth__pending-row {
  font-size: var(--fs-13);
  color: var(--txt);
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dws-auth__pending-tip {
  color: var(--txt2);
  font-size: var(--fs-12);
}
.dws-auth__code {
  font-family: var(--font-mono);
  font-size: var(--fs-15);
  font-weight: 600;
  padding: 4px 10px;
  background: #fff;
  border: 1px dashed #f5d791;
  border-radius: 6px;
  cursor: pointer;
  user-select: all;
}
.dws-auth__pending-meta {
  font-size: var(--fs-11);
  color: var(--txt3);
  margin-top: 4px;
  word-break: break-all;
}

.dws-auth__failed {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: #fff1f0;
  border: 1px solid #ffa39e;
  border-radius: 10px;
}
.dws-auth__failed-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #f5222d;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.dws-auth__failed-title { font-size: var(--fs-14); font-weight: 650; color: #a8071a; }
.dws-auth__failed-line { font-size: var(--fs-12); color: var(--txt2); margin-top: 4px; }

.dws-auth__form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dws-auth__label {
  font-size: var(--fs-13);
  color: var(--txt2);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dws-auth__input {
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  font-size: var(--fs-13);
  background: #fff;
  color: var(--txt);
  outline: none;
  transition: border-color 0.12s ease;
  max-width: 320px;
}
.dws-auth__input:focus {
  border-color: var(--primary);
}
.dws-auth__hint {
  font-size: var(--fs-12);
  color: var(--txt3);
  margin-bottom: 4px;
}
</style>
