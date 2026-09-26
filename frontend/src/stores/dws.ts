import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as dwsApi from '@/api/dws'
import type {
  DwsAuthPhase,
  DwsProfileDto,
  DwsRegistryEntry,
} from '@/types/dws'

const STORAGE_KEY = 'untitled.dws.userId'

function loadStoredUserId(): string {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v && v.trim()) return v
  } catch { /* SSR / private mode */ }
  return ''
}

function saveUserId(v: string) {
  try {
    if (v) localStorage.setItem(STORAGE_KEY, v)
    else localStorage.removeItem(STORAGE_KEY)
  } catch { /* ignore */ }
}

/**
 * dws 工作区状态。当前 userId + 授权 phase + 已有 profile。
 * userId 持久化到 localStorage 以便刷新后复用。
 */
export const useDwsStore = defineStore('dws', () => {
  const userId = ref<string>(loadStoredUserId())
  const phase = ref<DwsAuthPhase>('idle')
  const flowUserCode = ref<string>('')
  const flowVerificationUrl = ref<string>('')
  const flowExpiresAt = ref<number>(0)
  const flowError = ref<string>('')

  const profiles = ref<DwsProfileDto[]>([])
  const registryEntry = ref<DwsRegistryEntry | null>(null)

  const isAuthorized = computed(() => phase.value === 'approved')
  const isWaiting = computed(() => phase.value === 'waiting')
  const hasUserId = computed(() => !!userId.value)

  function setUserId(v: string) {
    userId.value = v
    saveUserId(v)
  }

  function resetFlowState() {
    phase.value = 'idle'
    flowUserCode.value = ''
    flowVerificationUrl.value = ''
    flowExpiresAt.value = 0
    flowError.value = ''
  }

  async function startDeviceFlow(displayName: string) {
    flowError.value = ''
    try {
      const resp = await dwsApi.startDeviceFlow({ displayName })
      setUserId(resp.userId)
      phase.value = 'waiting'
      flowUserCode.value = resp.userCode
      flowVerificationUrl.value = resp.verificationUriComplete
      flowExpiresAt.value = resp.expiresAt
      return resp
    } catch (e) {
      flowError.value = (e as Error).message || '授权发起失败'
      phase.value = 'failed'
      throw e
    }
  }

  async function refreshStatus() {
    if (!userId.value) return
    try {
      const s = await dwsApi.getAuthStatus(userId.value)
      phase.value = s.phase
      registryEntry.value = s.registry ?? null
    } catch (e) {
      // 网络/未授权都静默
    }
  }

  async function loadProfiles() {
    if (!userId.value) {
      profiles.value = []
      return
    }
    try {
      profiles.value = await dwsApi.listProfiles(userId.value)
    } catch {
      profiles.value = []
    }
  }

  async function loadRegistry() {
    if (!userId.value) return
    try {
      const reg = await dwsApi.getRegistry(userId.value)
      registryEntry.value = reg.current ?? null
    } catch {
      registryEntry.value = null
    }
  }

  async function switchProfile(selector: string) {
    if (!userId.value) return
    await dwsApi.switchProfile(userId.value, selector)
    await loadProfiles()
  }

  async function removeProfile(corpId: string, dwsUserId: string) {
    if (!userId.value) return
    await dwsApi.removeProfile(userId.value, corpId, dwsUserId)
    await loadProfiles()
  }

  async function reset() {
    if (!userId.value) return
    await dwsApi.resetAuth(userId.value)
    resetFlowState()
    profiles.value = []
    registryEntry.value = null
  }

  function clear() {
    setUserId('')
    resetFlowState()
    profiles.value = []
    registryEntry.value = null
  }

  return {
    userId, phase,
    flowUserCode, flowVerificationUrl, flowExpiresAt, flowError,
    profiles, registryEntry,
    isAuthorized, isWaiting, hasUserId,
    setUserId, startDeviceFlow, refreshStatus, loadProfiles, loadRegistry,
    switchProfile, removeProfile, reset, clear, resetFlowState,
  }
})
