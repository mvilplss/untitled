<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getDingTalkBot,
  getDingTalkBotHealth,
  upsertDingTalkBot,
  deleteDingTalkBot,
  type DingTalkBotHealth,
} from '@/api/agents'
import type { DingTalkBotConfig } from '@/types/api'

const props = defineProps<{
  agentId: string
  isEdit: boolean
}>()

const emit = defineEmits<{
  'change': [cfg: DingTalkBotConfig]
}>()

const MASK = '***'

const bot = ref<DingTalkBotConfig>({
  enabled: false,
  appKey: '',
  appSecret: '',
  robotCode: '',
})
const showBotSecret = ref(false)
const botSaving = ref(false)
const botLoading = ref(false)
const health = ref<DingTalkBotHealth | null>(null)
const healthTimer = ref<number | null>(null)

function reset() {
  bot.value = { enabled: false, appKey: '', appSecret: '', robotCode: '' }
  showBotSecret.value = false
  health.value = null
  stopHealthPoll()
}

async function load(agentId: string) {
  if (!agentId) return
  botLoading.value = true
  try {
    const [cfg, h] = await Promise.all([
      getDingTalkBot(agentId),
      getDingTalkBotHealth(agentId).catch(() => null),
    ])
    bot.value = {
      enabled: cfg.enabled,
      appKey: cfg.appKey ?? '',
      appSecret: cfg.appSecret ?? '',
      robotCode: cfg.robotCode ?? '',
    }
    health.value = h
    if (cfg.enabled) startHealthPoll(agentId)
  } catch {
    reset()
  } finally {
    botLoading.value = false
  }
}

function startHealthPoll(agentId: string) {
  stopHealthPoll()
  healthTimer.value = window.setInterval(async () => {
    if (!agentId) return
    try {
      health.value = await getDingTalkBotHealth(agentId)
    } catch {
      /* 保留旧值 */
    }
  }, 5000)
}

function stopHealthPoll() {
  if (healthTimer.value != null) {
    window.clearInterval(healthTimer.value)
    healthTimer.value = null
  }
}

watch(() => props.agentId, (v, old) => {
  if (v && v !== old) {
    if (bot.value.enabled) startHealthPoll(v)
  } else if (!v) {
    stopHealthPoll()
  }
})

const secretMasked = computed(() => bot.value.appSecret === MASK)
const hasAllFields = computed(() =>
  !!bot.value.appKey?.trim() && !!bot.value.appSecret?.trim() && !!bot.value.robotCode?.trim()
)
const canEnable = computed(() => !bot.value.enabled || hasAllFields.value)

/** 编辑模式：仅当 (启用+三字段齐) 或 (关闭+之前启用过) 才允许点保存；新建模式：只要有任何字段即可（最终由父表单决定要不要带 dingtalk） */
const dirty = computed(() => {
  if (!props.isEdit) return bot.value.enabled
  if (bot.value.enabled) return true
  return hasAllFields.value
})

const statusMeta = computed(() => {
  if (!health.value) {
    if (bot.value.enabled) return { text: '● 已保存', tone: 'warn' as const }
    return { text: '○ 未启用', tone: 'mute' as const }
  }
  const h = health.value
  if (h.status === 'ACTIVE') return { text: '● 运行中', tone: 'ok' as const }
  if (h.status === 'STARTING') return { text: '● 启动中', tone: 'warn' as const }
  if (h.status === 'ERROR') return { text: '× 启动失败', tone: 'err' as const }
  return { text: '○ 未启用', tone: 'mute' as const }
})

async function save() {
  if (!props.agentId) return
  if (bot.value.enabled && !hasAllFields.value) {
    ElMessage.error('启用机器人需填写 AppKey / AppSecret / RobotCode')
    return
  }
  botSaving.value = true
  try {
    const cfg: DingTalkBotConfig = {
      enabled: bot.value.enabled,
      appKey: bot.value.appKey?.trim() || undefined,
      appSecret: secretMasked.value ? MASK : (bot.value.appSecret?.trim() || undefined),
      robotCode: bot.value.robotCode?.trim() || undefined,
    }
    const result = await upsertDingTalkBot(props.agentId, cfg)
    ElMessage.success(bot.value.enabled ? '钉钉机器人已启动' : '配置已保存')
    bot.value = {
      enabled: result.enabled,
      appKey: result.appKey ?? '',
      appSecret: result.appSecret ?? '',
      robotCode: result.robotCode ?? '',
    }
    // 保存后立刻拉一次健康状态
    health.value = await getDingTalkBotHealth(props.agentId).catch(() => null)
    if (bot.value.enabled) startHealthPoll(props.agentId)
    else stopHealthPoll()
    emit('change', bot.value)
  } catch {
    /* axios interceptor 已 ElMessage */
  } finally {
    botSaving.value = false
  }
}

async function disable() {
  if (!props.agentId) return
  try {
    await deleteDingTalkBot(props.agentId)
    ElMessage.success('钉钉机器人已停用')
    reset()
    emit('change', bot.value)
  } catch {
    /* */
  }
}

function onToggleEnabled(v: string | number | boolean) {
  bot.value.enabled = Boolean(v)
  emit('change', bot.value)
}

function getBotSnapshot(): DingTalkBotConfig {
  return bot.value
}

defineExpose({ load, reset, getBotSnapshot })
</script>

<template>
  <section>
    <header class="dts-head">
      <span class="dts-head__title mono">// 钉钉机器人</span>
      <span v-if="isEdit" class="dts-head__meta mono">
        <span v-if="botLoading">加载中…</span>
        <span
          v-else
          class="dts-status"
          :class="`dts-status--${statusMeta.tone}`"
        >{{ statusMeta.text }}</span>
        <span v-if="health?.status === 'ERROR' && health.lastError" class="dts-status__err mono">
          {{ health.lastError }}
        </span>
      </span>
      <span v-else class="dts-head__meta mono dts-status dts-status--mute">
        {{ bot.enabled ? '● 启用（随创建一并启动）' : '○ 不启用' }}
      </span>
    </header>

    <div class="dts-hint mono">
      为该数字人绑定专属钉钉机器人（1:1 绑定）。
      钉钉用户向机器人发送消息时，会由该数字人回复；变更保存后立即生效。
      <span v-if="!isEdit">新建时如启用，机器人将与 Agent 同步创建并启动。</span>
    </div>

    <div class="dts-grid">
      <div class="dts-field dts-field--full">
        <label class="dts-field__label mono">启用机器人</label>
        <el-switch
          :model-value="bot.enabled"
          :disabled="!canEnable && !bot.enabled"
          @update:model-value="onToggleEnabled"
        />
      </div>

      <div class="dts-field">
        <label class="dts-field__label mono">AppKey</label>
        <el-input
          v-model="bot.appKey"
          placeholder="钉钉开放平台 → Client ID（原 AppKey）"
          :disabled="!bot.enabled"
          maxlength="128"
        />
      </div>

      <div class="dts-field">
        <label class="dts-field__label mono">AppSecret</label>
        <el-input
          v-model="bot.appSecret"
          :type="showBotSecret ? 'text' : 'password'"
          :placeholder="secretMasked
            ? '已保存 · 输入新值以替换'
            : (isEdit ? '留空则沿用旧值' : '钉钉开放平台 → Client Secret')"
          :disabled="!bot.enabled"
          maxlength="256"
        >
          <template #append>
            <button
              class="dts-eye mono"
              type="button"
              @click="showBotSecret = !showBotSecret"
            >
              {{ showBotSecret ? '隐藏' : '显示' }}
            </button>
          </template>
        </el-input>
        <div v-if="isEdit && secretMasked" class="dts-field__hint mono">
          已保存当前密钥；如需修改请直接输入新值，否则提交时沿用旧值
        </div>
      </div>

      <div class="dts-field dts-field--full">
        <label class="dts-field__label mono">RobotCode</label>
        <el-input
          v-model="bot.robotCode"
          placeholder="钉钉开放平台 → 机器人与消息接收 → RobotCode"
          :disabled="!bot.enabled"
          maxlength="128"
        />
      </div>
    </div>

    <footer v-if="isEdit" class="dts-foot">
      <button
        v-if="bot.enabled"
        class="btn-ghost mono"
        type="button"
        @click="disable"
      >
        停用机器人
      </button>
      <span class="dts-foot-spacer"></span>
      <button
        class="btn-primary"
        type="button"
        :disabled="botSaving || !dirty || (bot.enabled && !hasAllFields)"
        @click="save"
      >
        {{ botSaving ? '保存中' : (bot.enabled ? '保存' : '启用并启动') }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.dts-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.dts-head__title {
  font-size: 13px;
  color: var(--text);
  font-weight: 600;
}
.dts-head__meta {
  font-size: 11px;
  color: var(--text-mute);
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.dts-status--ok { color: var(--ok); }
.dts-status--warn { color: var(--warn); }
.dts-status--err { color: var(--err); }
.dts-status--mute { color: var(--text-faint); }
.dts-status__err {
  color: var(--err);
  font-size: 11px;
  max-width: 320px;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dts-hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-bottom: 12px;
}

.dts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 24px;
  row-gap: 14px;
  padding: 12px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
}
.dts-field { display: flex; flex-direction: column; gap: 6px; }
.dts-field--full { grid-column: 1 / -1; }
.dts-field__label { font-size: 11px; color: var(--text-mute); }
.dts-field__hint {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.dts-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.dts-foot-spacer { flex: 1; }

.dts-eye {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 4px 10px;
  cursor: pointer;
}
.dts-eye:hover { color: var(--text); border-color: var(--text-mute); }
</style>
