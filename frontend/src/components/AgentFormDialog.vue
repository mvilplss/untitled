<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import type { AgentMode, AgentSpec, DingTalkBotConfig } from '@/types/api'
import { useSkillsStore } from '@/stores/skills'
import { getDingTalkBot, upsertDingTalkBot, deleteDingTalkBot } from '@/api/agents'

const props = defineProps<{
  modelValue: boolean
  mode: AgentMode
  initial?: AgentSpec | null
}>()

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  'submit': [spec: AgentSpec, mode: AgentMode]
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => (visible.value = v))
watch(visible, v => emit('update:modelValue', v))

const isEdit = computed(() => props.mode === 'edit')

const skillsStore = useSkillsStore()
onMounted(() => skillsStore.fetchList())

const formRef = ref<FormInstance>()
const form = ref<AgentSpec>({
  id: '',
  name: '',
  sysPrompt: '你是一个有帮助的助手。',
  modelName: 'minimax-m3',
  skills: [],
})
const submitting = ref(false)

const rules: FormRules = {
  id: [
    { required: true, message: '请输入数字人 ID', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_-]{1,32}$/, message: '1-32 字符，仅含字母数字下划线连字符', trigger: 'blur' },
  ],
  name: [
    { max: 64, message: '长度不超过 64 字符', trigger: 'blur' },
  ],
  sysPrompt: [
    { required: true, message: '请输入系统提示词', trigger: 'blur' },
    { min: 1, max: 4096, message: '长度 1-4096 字符', trigger: 'blur' },
  ],
  modelName: [
    { required: true, message: '请输入模型名', trigger: 'blur' },
  ],
}

function emptyForm(): AgentSpec {
  return {
    id: '',
    name: '',
    sysPrompt: '你是一个有帮助的助手。',
    modelName: 'minimax-m3',
    skills: [],
  }
}

function fillFromInitial(spec?: AgentSpec | null) {
  if (!spec) {
    form.value = emptyForm()
  } else {
    form.value = {
      id: spec.id,
      name: spec.name ?? '',
      sysPrompt: spec.sysPrompt,
      modelName: spec.modelName,
      skills: spec.skills ? [...spec.skills] : [],
    }
  }
  formRef.value?.clearValidate()
}

watch(
  () => [props.modelValue, props.mode, props.initial] as const,
  ([open, , init]) => {
    if (open) {
      fillFromInitial(isEdit.value ? init : null)
      if (isEdit.value && init) {
        loadBot(init.id)
      } else {
        resetBot()
      }
    }
  },
  { immediate: true },
)

function handleClose() { visible.value = false }

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: AgentSpec = {
      id: form.value.id.trim(),
      name: form.value.name?.trim() || form.value.id.trim(),
      sysPrompt: form.value.sysPrompt.trim(),
      modelName: form.value.modelName.trim(),
      skills: form.value.skills && form.value.skills.length > 0 ? [...form.value.skills] : undefined,
    }
    // 新建模式：把机器人配置一起带进 POST；编辑模式主表单不带 dingtalk（机器人区独立保存）
    if (!isEdit.value && bot.value.enabled) {
      const secret = botSecretMasked.value ? '' : (bot.value.appSecret?.trim() || '')
      if (!bot.value.appKey?.trim() || !secret || !bot.value.robotCode?.trim()) {
        ElMessage.error('启用机器人需填写 AppKey / AppSecret / RobotCode')
        submitting.value = false
        return
      }
      payload.dingtalk = {
        enabled: true,
        appKey: bot.value.appKey.trim(),
        appSecret: secret,
        robotCode: bot.value.robotCode.trim(),
      }
    }
    emit('submit', payload, props.mode)
    ElMessage.success(
      isEdit.value ? `数字人 '${payload.id}' 已更新` : `数字人 '${payload.id}' 已创建`,
    )
    handleClose()
  } finally {
    submitting.value = false
  }
}

// ===== 钉钉机器人配置（独立保存） =====
const bot = ref<DingTalkBotConfig>({
  enabled: false,
  appKey: '',
  appSecret: '',
  robotCode: '',
})
const showBotSecret = ref(false)
const botSaving = ref(false)
const botLoading = ref(false)
const MASK = '***'

function resetBot() {
  bot.value = { enabled: false, appKey: '', appSecret: '', robotCode: '' }
  showBotSecret.value = false
}

async function loadBot(agentId: string) {
  botLoading.value = true
  try {
    const cfg = await getDingTalkBot(agentId)
    bot.value = {
      enabled: cfg.enabled,
      appKey: cfg.appKey ?? '',
      appSecret: cfg.appSecret ?? '',
      robotCode: cfg.robotCode ?? '',
    }
  } catch (e) {
    resetBot()
  } finally {
    botLoading.value = false
  }
}

const botDirty = computed(() => {
  if (!bot.value.enabled) return bot.value.appKey || bot.value.robotCode
  return true
})

const botSecretMasked = computed(() => bot.value.appSecret === MASK)

async function saveBot() {
  if (!props.initial) return
  botSaving.value = true
  try {
    const cfg: DingTalkBotConfig = {
      enabled: bot.value.enabled,
      appKey: bot.value.appKey?.trim() || undefined,
      appSecret: botSecretMasked.value ? MASK : (bot.value.appSecret?.trim() || undefined),
      robotCode: bot.value.robotCode?.trim() || undefined,
    }
    const result = await upsertDingTalkBot(props.initial.id, cfg)
    ElMessage.success(bot.value.enabled ? '钉钉机器人已启动' : '配置已保存')
    bot.value = {
      enabled: result.enabled,
      appKey: result.appKey ?? '',
      appSecret: result.appSecret ?? '',
      robotCode: result.robotCode ?? '',
    }
  } catch {
    /* axios interceptor 已经 ElMessage */
  } finally {
    botSaving.value = false
  }
}

async function disableBot() {
  if (!props.initial) return
  try {
    await deleteDingTalkBot(props.initial.id)
    ElMessage.success('钉钉机器人已停用')
    resetBot()
  } catch {
    /* */
  }
}

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}
</script>

<template>
  <transition name="overlay">
    <div v-if="visible" class="dialog-mask" @click.self="handleClose">
      <div class="dialog" role="dialog" aria-modal="true">
        <header class="dialog__head">
          <div>
            <div class="dialog__crumb mono">{{ isEdit ? '编辑' : '新建' }} · 数字人配置</div>
            <h2 class="dialog__title">
              {{ isEdit ? form.id || '数字人' : '新建数字人' }}
            </h2>
          </div>
          <button class="btn-ghost mono" @click="handleClose">关闭 ×</button>
        </header>

        <el-form ref="formRef" :model="form" :rules="rules" class="dialog__form">
          <div class="dialog__grid">
            <div class="field">
              <label class="field__label mono">数字人 ID</label>
              <el-input
                v-model="form.id"
                placeholder="例如：assistant / writer / translator"
                maxlength="32"
                show-word-limit
                :disabled="isEdit"
              />
              <div class="field__hint mono">1–32 字符，仅含字母数字下划线连字符</div>
            </div>

            <div class="field">
              <label class="field__label mono">显示名称</label>
              <el-input v-model="form.name" placeholder="选填，留空则与 ID 一致" maxlength="64" />
            </div>

            <div class="field field--full">
              <label class="field__label mono">系统提示词</label>
              <el-input
                v-model="form.sysPrompt"
                type="textarea"
                :rows="5"
                placeholder="设定数字人的角色与回答风格"
                maxlength="4096"
                show-word-limit
              />
            </div>

            <div class="field field--full">
              <label class="field__label mono">模型</label>
              <el-input v-model="form.modelName" placeholder="OpenAI 兼容接口的模型标识" />
            </div>
          </div>

          <section class="dialog__section">
            <header class="dialog__section-head">
              <span class="dialog__section-title mono">// 技能</span>
              <span class="dialog__section-meta mono">
                已选 {{ form.skills?.length ?? 0 }} / {{ skillsStore.list.length }}
              </span>
            </header>
            <div class="dialog__hint mono">
              该数字人可调用的技能（从 .agentscope/skills/ 加载）。
              留空表示对所有技能可见。
              <span v-if="!skillsStore.list.length" class="dialog__hint-warn">
                — 暂无可用技能，请前往「技能」页面上传。
              </span>
            </div>
            <el-checkbox-group
              v-model="form.skills"
              class="skill-grid"
              :disabled="!skillsStore.list.length"
            >
              <el-checkbox
                v-for="s in skillsStore.list"
                :key="s.name"
                :value="s.name"
                class="tool-cell"
              >
                <div class="tool-cell__label">{{ s.name }}</div>
                <div class="tool-cell__desc">{{ s.description }}</div>
                <div class="tool-cell__name mono">
                  {{ s.resourceCount }} 个资源 · {{ formatBytes(s.sizeBytes) }}
                </div>
              </el-checkbox>
            </el-checkbox-group>
          </section>

          <section class="dialog__section">
            <header class="dialog__section-head">
              <span class="dialog__section-title mono">// 钉钉机器人</span>
              <span v-if="isEdit" class="dialog__section-meta mono">
                <span v-if="botLoading">加载中…</span>
                <span v-else-if="bot.enabled && botSecretMasked" class="dialog__hint-ok">● 运行中</span>
                <span v-else-if="bot.enabled" class="dialog__hint-warn">● 配置已保存（待启动）</span>
                <span v-else class="dialog__hint-mute">○ 未启用</span>
              </span>
              <span v-else class="dialog__section-meta mono dialog__hint-mute">
                {{ bot.enabled ? '● 启用（随创建一并启动）' : '○ 不启用' }}
              </span>
            </header>
            <div class="dialog__hint mono">
              为该数字人绑定专属钉钉机器人（1:1 绑定）。
              钉钉用户向机器人发送消息时，会由该数字人回复；变更保存后立即生效。
              <span v-if="!isEdit">新建时如启用，机器人将与 Agent 同步创建并启动。</span>
            </div>

            <div class="bot-grid">
              <div class="field field--full">
                <label class="field__label mono">启用机器人</label>
                <el-switch v-model="bot.enabled" />
              </div>

              <div class="field">
                <label class="field__label mono">AppKey</label>
                <el-input
                  v-model="bot.appKey"
                  placeholder="钉钉开放平台 → Client ID（原 AppKey）"
                  :disabled="!bot.enabled"
                  maxlength="128"
                />
              </div>

              <div class="field">
                <label class="field__label mono">AppSecret</label>
                <el-input
                  v-model="bot.appSecret"
                  :type="showBotSecret ? 'text' : 'password'"
                  :placeholder="botSecretMasked ? '已保存 · 输入新值以替换' : (isEdit ? '留空则沿用旧值' : '钉钉开放平台 → Client Secret')"
                  :disabled="!bot.enabled"
                  maxlength="256"
                >
                  <template #append>
                    <button
                      class="btn-eye mono"
                      type="button"
                      @click="showBotSecret = !showBotSecret"
                    >
                      {{ showBotSecret ? '隐藏' : '显示' }}
                    </button>
                  </template>
                </el-input>
                <div v-if="isEdit && botSecretMasked" class="field__hint mono">
                  已保存当前密钥；如需修改请直接输入新值，否则提交时沿用旧值
                </div>
              </div>

              <div class="field field--full">
                <label class="field__label mono">RobotCode</label>
                <el-input
                  v-model="bot.robotCode"
                  placeholder="钉钉开放平台 → 机器人与消息接收 → RobotCode"
                  :disabled="!bot.enabled"
                  maxlength="128"
                />
              </div>
            </div>

            <footer v-if="isEdit" class="bot-foot">
              <button
                v-if="bot.enabled"
                class="btn-ghost mono"
                type="button"
                @click="disableBot"
              >
                停用机器人
              </button>
              <span class="bot-foot-spacer"></span>
              <button
                class="btn-primary"
                type="button"
                :disabled="botSaving || !botDirty"
                @click="saveBot"
              >
                {{ botSaving ? '保存中' : (bot.enabled ? '保存' : '启用并启动') }}
              </button>
            </footer>
          </section>
        </el-form>

        <footer class="dialog__foot">
          <span class="dialog__foot-hint mono">// 按 Esc 取消</span>
          <span class="dialog__foot-spacer"></span>
          <button class="btn-ghost mono" @click="handleClose">取消</button>
          <button class="btn-primary" :disabled="submitting" @click="handleSubmit">
            {{ submitting ? '保存中' : (isEdit ? '保存' : '创建') }}
          </button>
        </footer>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(54, 69, 79, 0.4);
  z-index: var(--z-overlay);
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
}

.dialog {
  width: min(820px, 100%);
  height: 100%;
  background: var(--bg-base);
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.dialog__head {
  padding: 18px 24px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  background: var(--bg-surface);
}
.dialog__crumb {
  font-size: 11px;
  color: var(--text-mute);
  margin-bottom: 4px;
}
.dialog__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
  font-family: var(--font-mono);
}

.dialog__form {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.dialog__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px 24px;
  margin-bottom: 24px;
}
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label {
  font-size: 11px;
  color: var(--text-mute);
}
.field__hint {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.dialog__section {
  border-top: 1px solid var(--border);
  padding-top: 20px;
  margin-top: 24px;
}
.dialog__section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}
.dialog__section-title {
  font-size: 13px;
  color: var(--text);
  font-weight: 600;
}
.dialog__section-meta {
  font-size: 11px;
  color: var(--text-mute);
}
.dialog__hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-bottom: 12px;
}
.dialog__hint-warn { color: var(--warn); }
.dialog__hint-ok { color: var(--ok); }
.dialog__hint-mute { color: var(--text-faint); }

.skill-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 24px;
  row-gap: 4px;
  max-height: 280px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
}

.tool-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--border-soft);
}
.tool-cell:last-child { border-bottom: none; }
.tool-cell :deep(.el-checkbox__label) {
  display: block;
  flex: 1;
  width: auto;
  white-space: normal;
  line-height: 1.5;
  padding-left: 8px;
}
.tool-cell__label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  line-height: 1.4;
  margin-bottom: 4px;
}
.tool-cell__name {
  display: block;
  font-size: 11px;
  color: var(--text-faint);
  line-height: 1.5;
  margin-bottom: 6px;
  word-break: break-all;
}
.tool-cell__desc {
  display: block;
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
}

/* ===== bot section ===== */
.bot-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 24px;
  row-gap: 14px;
  padding: 12px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
}
.bot-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.bot-foot-spacer { flex: 1; }

.btn-eye {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 4px 10px;
  cursor: pointer;
}
.btn-eye:hover { color: var(--text); border-color: var(--text-mute); }

.dialog__foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 24px;
  border-top: 1px solid var(--border);
  background: var(--bg-surface);
}
.dialog__foot-hint { font-size: 11px; color: var(--text-faint); }
.dialog__foot-spacer { flex: 1; }

/* buttons (shared locally for scoped override) */
.btn-primary {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--bg-base);
  background: var(--signal);
  border: 1px solid var(--signal);
  padding: 7px 16px;
  cursor: pointer;
  transition: background-color 0.1s ease, border-color 0.1s ease;
}
.btn-primary:hover:not(:disabled) {
  background: var(--signal-hover);
  border-color: var(--signal-hover);
}
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }

.btn-ghost {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 6px 12px;
  cursor: pointer;
  transition: color 0.1s ease, border-color 0.1s ease;
}
.btn-ghost:hover { color: var(--text); border-color: var(--text-mute); }

.overlay-enter-active, .overlay-leave-active { transition: opacity 0.12s ease; }
.overlay-enter-active .dialog, .overlay-leave-active .dialog { transition: transform 0.18s ease; }
.overlay-enter-from, .overlay-leave-to { opacity: 0; }
.overlay-enter-from .dialog, .overlay-leave-to .dialog { transform: translateX(100%); }
</style>