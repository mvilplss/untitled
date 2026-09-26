<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import type { AgentMode, AgentSpec, BailianRagConfig, DingTalkBotConfig } from '@/types/api'
import { useSkillsStore } from '@/stores/skills'
import SkillMultiPicker from '@/components/SkillMultiPicker.vue'
import DingTalkSubForm from '@/components/DingTalkSubForm.vue'
import BailianRagSubForm from '@/components/BailianRagSubForm.vue'
import { generateAgentId } from '@/utils/id'

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

const MASK = '***'

const formRef = ref<FormInstance>()
const form = ref<AgentSpec>({
  id: generateAgentId(),
  name: '',
  sysPrompt: '你是一个有帮助的助手。',
  modelName: 'minimax-m3',
  skills: [],
})
const submitting = ref(false)

/** 把可选 skills 收敛成 string[] 给 SkillMultiPicker（v-model 要求非可选） */
const skillsModel = computed<string[]>({
  get: () => form.value.skills ?? [],
  set: (v) => { form.value.skills = v },
})

const dingTalkRef = ref<InstanceType<typeof DingTalkSubForm> | null>(null)
const bailianRef = ref<InstanceType<typeof BailianRagSubForm> | null>(null)

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
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
    id: generateAgentId(),
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
        dingTalkRef.value?.load(init.id)
        bailianRef.value?.load(init.id)
      } else {
        dingTalkRef.value?.reset()
        bailianRef.value?.reset()
      }
    }
  },
  { immediate: true, flush: 'post' },
)

function handleClose() { visible.value = false }

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: AgentSpec = {
      id: form.value.id,
      name: form.value.name.trim(),
      sysPrompt: form.value.sysPrompt.trim(),
      modelName: form.value.modelName.trim(),
      skills: form.value.skills && form.value.skills.length > 0 ? [...form.value.skills] : undefined,
    }
    // 新建模式：把机器人配置一起带进 POST；编辑模式主表单不带 dingtalk（机器人区独立保存）
    if (!isEdit.value && dingTalkRef.value) {
      const bot = dingTalkRef.value.getBotSnapshot()
      if (bot.enabled) {
        const secret = bot.appSecret === MASK ? '' : (bot.appSecret?.trim() || '')
        if (!bot.appKey?.trim() || !secret || !bot.robotCode?.trim()) {
          ElMessage.error('启用机器人需填写 AppKey / AppSecret / RobotCode')
          submitting.value = false
          return
        }
        const dingtalk: DingTalkBotConfig = {
          enabled: true,
          appKey: bot.appKey.trim(),
          appSecret: secret,
          robotCode: bot.robotCode.trim(),
        }
        payload.dingtalk = dingtalk
      }
    }
    // 新建模式：把百炼 RAG 配置一起带进 POST；编辑模式主表单不带 bailian（RAG 区独立保存）
    if (!isEdit.value && bailianRef.value) {
      const rag = bailianRef.value.getRagSnapshot()
      if (rag.enabled) {
        const secret = rag.accessKeySecret === MASK ? '' : (rag.accessKeySecret?.trim() || '')
        if (!rag.accessKeyId?.trim() || !secret || !rag.workspaceId?.trim() || !rag.indexId?.trim()) {
          ElMessage.error('启用百炼 RAG 需填写 AccessKeyId / AccessKeySecret / WorkspaceId / IndexId')
          submitting.value = false
          return
        }
        const bailian: BailianRagConfig = {
          enabled: true,
          accessKeyId: rag.accessKeyId.trim(),
          accessKeySecret: secret,
          workspaceId: rag.workspaceId.trim(),
          indexId: rag.indexId.trim(),
          endpoint: rag.endpoint?.trim() || undefined,
          limit: rag.limit,
          scoreThreshold: rag.scoreThreshold,
          enableRerank: rag.enableRerank,
          enableRewrite: rag.enableRewrite,
        }
        payload.bailian = bailian
      }
    }
    emit('submit', payload, props.mode)
    ElMessage.success(
      isEdit.value ? `数字人 '${payload.name}' 已更新` : `数字人 '${payload.name}' 已创建`,
    )
    handleClose()
  } finally {
    submitting.value = false
  }
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
              {{ isEdit ? (form.name || '未命名') : '新建数字人' }}
            </h2>
          </div>
          <button class="btn-ghost mono" @click="handleClose">关闭 ×</button>
        </header>

        <el-form ref="formRef" :model="form" :rules="rules" class="dialog__form">
          <div class="dialog__grid">
            <div class="field field--full">
              <label class="field__label mono">名称 <span class="field__required">*</span></label>
              <el-input
                v-model="form.name"
                placeholder="给你的数字人起个名字"
                maxlength="64"
                show-word-limit
              />
              <div class="field__hint mono">
                ID
                <span class="field__id-mono">{{ form.id }}</span>
                <span class="field__hint-sep">·</span>
                <span>系统自动生成，标识符不可改</span>
              </div>
            </div>

            <div class="field field--full">
              <label class="field__label mono">系统提示词 <span class="field__required">*</span></label>
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
              <label class="field__label mono">模型 <span class="field__required">*</span></label>
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
            <SkillMultiPicker v-model="skillsModel" />
          </section>

          <section class="dialog__section">
            <DingTalkSubForm
              ref="dingTalkRef"
              :agent-id="isEdit ? (initial?.id ?? '') : ''"
              :is-edit="isEdit"
            />
          </section>

          <section class="dialog__section">
            <BailianRagSubForm
              ref="bailianRef"
              :agent-id="isEdit ? (initial?.id ?? '') : ''"
              :is-edit="isEdit"
            />
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
  background: rgba(24,27,32,.42);
  z-index: var(--z-overlay);
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
}

.dialog {
  width: min(820px, 100%);
  height: 100%;
  background: var(--bg);
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 32px rgba(20,24,31,.08);
}

.dialog__head {
  padding: 18px 24px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  background: var(--panel);
}
.dialog__crumb {
  font-size: 11px;
  color: var(--txt2);
  margin-bottom: 4px;
}
.dialog__title {
  margin: 0;
  font-size: var(--fs-18);
  font-weight: 650;
  letter-spacing: 0;
  color: var(--txt);
  font-family: var(--font-sans);
}

.dialog__form {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.dialog__grid {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-bottom: 24px;
}
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { width: 100%; }
.field__label {
  font-size: 11px;
  color: var(--txt2);
  display: flex;
  align-items: center;
  gap: 4px;
}
.field__required {
  color: var(--red);
  font-family: var(--font-mono);
  font-weight: 600;
}
.field__hint {
  font-size: 11px;
  color: var(--txt3);
  margin-top: 2px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.field__id-mono {
  font-family: var(--font-mono);
  color: var(--txt2);
  background: var(--bg);
  border: 1px solid var(--line-soft);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  user-select: all;
}
.field__hint-sep { color: var(--line); }

.dialog__section {
  border-top: 1px solid var(--line);
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
  font-size: var(--fs-13);
  color: var(--txt);
  font-weight: 650;
}
.dialog__section-meta {
  font-size: 11px;
  color: var(--txt2);
}

.dialog__foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 24px;
  border-top: 1px solid var(--line);
  background: var(--panel);
}
.dialog__foot-hint { font-size: 11px; color: var(--txt3); }
.dialog__foot-spacer { flex: 1; }

/* buttons (shared locally for scoped override) */
.btn-primary {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--primary);
  border: 1px solid var(--primary);
  border-radius: var(--radius-sm);
  padding: 7px 16px;
  cursor: pointer;
  transition: background-color 0.1s ease, border-color 0.1s ease;
}
.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }

.btn-ghost {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--txt2);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 6px 12px;
  cursor: pointer;
  transition: color 0.1s ease, border-color 0.1s ease;
}
.btn-ghost:hover { color: var(--txt); border-color: var(--txt2); }

.overlay-enter-active, .overlay-leave-active { transition: opacity 0.12s ease; }
.overlay-enter-active .dialog, .overlay-leave-active .dialog { transition: transform 0.18s ease; }
.overlay-enter-from, .overlay-leave-to { opacity: 0; }
.overlay-enter-from .dialog, .overlay-leave-to .dialog { transform: translateX(100%); }
</style>