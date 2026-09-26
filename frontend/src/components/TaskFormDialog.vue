<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAgentsStore } from '@/stores/agents'
import { useSkillsStore } from '@/stores/skills'
import SkillMultiPicker from '@/components/SkillMultiPicker.vue'
import type { ScheduledTask } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  /** 编辑模式传入已有任务；新建模式传 null */
  initial?: ScheduledTask | null
}>()

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  'submit': [task: ScheduledTask, isEdit: boolean]
}>()

const agentsStore = useAgentsStore()
const skillsStore = useSkillsStore()

// ============ 表单字段 ============
const form = ref({
  id: '',
  name: '',
  agentId: '',
  prompt: '',
  skills: [] as string[],
  cron: '0 0 9 * * ?',
  enabled: true,
})

// 计划预设
type CronPreset = 'daily' | 'weekly' | 'monthly' | 'hourly' | 'custom'
const cronPreset = ref<CronPreset>('daily')
const cronTime = ref('09:00')      // HH:mm
const cronDow = ref<number[]>([1])  // 周一-周日（1=周一）
const cronDom = ref(1)              // 1-31
const cronCustom = ref('')          // 自定义 cron 6 段

const PRESET_OPTIONS: { label: string; value: CronPreset }[] = [
  { label: '每天', value: 'daily' },
  { label: '每周', value: 'weekly' },
  { label: '每月', value: 'monthly' },
  { label: '每小时', value: 'hourly' },
  { label: '自定义 cron', value: 'custom' },
]

const DOW_OPTIONS = [
  { label: '周一', value: 1 }, { label: '周二', value: 2 }, { label: '周三', value: 3 },
  { label: '周四', value: 4 }, { label: '周五', value: 5 }, { label: '周六', value: 6 },
  { label: '周日', value: 7 },
]
const DOW_NAMES = ['', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

// 编辑模式：选完数字人后再决定 skills 白名单
const skillsAllowed = computed<string[] | undefined>(() => {
  if (!form.value.agentId) return undefined
  const spec = agentsStore.list.find(a => a.id === form.value.agentId)
  if (!spec || !spec.skills || spec.skills.length === 0) return undefined // agent 无限制 → 全量
  return spec.skills
})

const effectiveCron = computed(() => {
  if (cronPreset.value === 'custom') return cronCustom.value.trim()
  const [h, m] = cronTime.value.split(':').map(Number)
  const mm = String(m).padStart(2, '0')
  const hh = String(h).padStart(2, '0')
  switch (cronPreset.value) {
    case 'daily':   return `0 ${mm} ${hh} * * ?`
    case 'weekly':  return `0 ${mm} ${hh} ? * ${cronDows.value.map(d => DOW_NAMES[d]).join(',')}`
    case 'monthly': return `0 ${mm} ${hh} ${cronDom.value} * ?`
    case 'hourly':  return `0 0 * * * ?`
  }
  return ''
})

const cronValid = computed(() => {
  const c = effectiveCron.value
  if (!c) return false
  // 6 段：秒 分 时 日 月 周。允许空格分隔的多值。校验最宽松的字符集。
  return /^[0-9\?\*\-\,\/\sA-Za-z]+$/.test(c)
})

const isEdit = computed(() => !!props.initial)

watch(() => props.modelValue, (v) => {
  if (v) {
    // 打开时初始化
    if (props.initial) {
      const t = props.initial
      form.value = {
        id: t.id,
        name: t.name,
        agentId: t.agentId,
        prompt: t.prompt,
        skills: t.skills ? [...t.skills] : [],
        cron: t.cron,
        enabled: t.enabled,
      }
      // 反向猜测预设（粗略匹配，不命中就 custom）
      cronPreset.value = guessPreset(t.cron)
      cronCustom.value = t.cron
    } else {
      form.value = {
        id: '',
        name: '',
        agentId: '',
        prompt: '',
        skills: [],
        cron: '0 0 9 * * ?',
        enabled: true,
      }
      cronPreset.value = 'daily'
      cronTime.value = '09:00'
    }
  }
})

function guessPreset(cron: string): CronPreset {
  const parts = cron.trim().split(/\s+/)
  if (parts.length !== 6) return 'custom'
  // daily: `0 m H * * ?`
  if (parts[3] === '*' && parts[4] === '*' && parts[5] === '?') {
    cronTime.value = `${parts[2].padStart(2,'0')}:${parts[1].padStart(2,'0')}`
    return 'daily'
  }
  // weekly: `0 m H ? * DOW`
  if (parts[3] === '?' && parts[4] === '*' && parts[5] !== '*') {
    cronTime.value = `${parts[2].padStart(2,'0')}:${parts[1].padStart(2,'0')}`
    const names = parts[5].split(',').map(s => DOW_NAMES.indexOf(s)).filter(n => n > 0)
    if (names.length) cronDow.value = names
    return 'weekly'
  }
  // monthly: `0 m H D * ?`
  if (/^\d+$/.test(parts[3]) && parts[4] === '*' && parts[5] === '?') {
    cronTime.value = `${parts[2].padStart(2,'0')}:${parts[1].padStart(2,'0')}`
    cronDom.value = Number(parts[3])
    return 'monthly'
  }
  // hourly: `0 0 * * * ?`
  if (parts[3] === '*' && parts[4] === '*' && parts[5] === '?' && parts[1] === '0' && parts[2] === '*') {
    return 'hourly'
  }
  return 'custom'
}

function close() {
  emit('update:modelValue', false)
}

async function onSubmit() {
  if (!form.value.id) {
    ElMessage.error('请输入任务 ID'); return
  }
  if (!/^[a-zA-Z0-9_-]{1,64}$/.test(form.value.id)) {
    ElMessage.error('任务 ID 必须匹配 ^[a-zA-Z0-9_-]{1,64}$'); return
  }
  if (!form.value.name) {
    ElMessage.error('请输入任务名称'); return
  }
  if (!form.value.agentId) {
    ElMessage.error('请选择关联数字人'); return
  }
  if (!form.value.prompt) {
    ElMessage.error('请输入任务提示词'); return
  }
  if (!cronValid.value) {
    ElMessage.error('请填写合法的 cron 表达式'); return
  }

  // 剪除不在数字人授权范围内的 skills
  let finalSkills = form.value.skills
  if (skillsAllowed.value != null) {
    const set = new Set(skillsAllowed.value)
    finalSkills = form.value.skills.filter(s => set.has(s))
  }

  const task: ScheduledTask = {
    id: form.value.id,
    name: form.value.name,
    agentId: form.value.agentId,
    prompt: form.value.prompt,
    skills: finalSkills.length ? finalSkills : undefined,
    cron: effectiveCron.value,
    enabled: form.value.enabled,
    createdAt: props.initial?.createdAt ?? Date.now(),
    updatedAt: Date.now(),
  }

  if (isEdit.value) {
    try {
      await ElMessageBox.confirm(
        `确认更新任务「${task.name}」？cron 变更或禁用/启用会立即生效。`,
        '更新定时任务',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }

  emit('submit', task, isEdit.value)
  close()
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑定时任务' : '新建定时任务'"
    width="640"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form label-position="top" :inline="false">
      <el-form-item label="任务 ID" required>
        <el-input v-model="form.id" :disabled="isEdit" placeholder="例如 daily-news-report" />
        <div class="tf-hint mono">仅允许字母数字下划线中划线，长度 1-64</div>
      </el-form-item>
      <el-form-item label="任务名称" required>
        <el-input v-model="form.name" placeholder="例如 每日新闻摘要" />
      </el-form-item>
      <el-form-item label="关联数字人" required>
        <el-select v-model="form.agentId" placeholder="选择数字人" style="width:100%" filterable>
          <el-option
            v-for="a in agentsStore.list"
            :key="a.id"
            :value="a.id"
            :label="a.name || a.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="任务提示词" required>
        <el-input
          v-model="form.prompt"
          type="textarea"
          :rows="6"
          placeholder="说明本次定时任务要做什么。skill 选项中勾选的会在执行时被强制要求加载使用。"
        />
      </el-form-item>
      <el-form-item label="技能选择（执行时必须使用）">
        <SkillMultiPicker v-model="form.skills" :allowed="skillsAllowed" />
      </el-form-item>
      <el-form-item label="执行计划">
        <el-radio-group v-model="cronPreset">
          <el-radio v-for="o in PRESET_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
        </el-radio-group>
        <div v-if="cronPreset === 'daily'" class="tf-row">
          <el-time-picker v-model="cronTime" format="HH:mm" value-format="HH:mm" placeholder="选择时间" />
        </div>
        <div v-else-if="cronPreset === 'weekly'" class="tf-row">
          <el-time-picker v-model="cronTime" format="HH:mm" value-format="HH:mm" placeholder="选择时间" />
          <el-checkbox-group v-model="cronDow" class="tf-dow">
            <el-checkbox v-for="d in DOW_OPTIONS" :key="d.value" :value="d.value">{{ d.label }}</el-checkbox>
          </el-checkbox-group>
        </div>
        <div v-else-if="cronPreset === 'monthly'" class="tf-row">
          <el-time-picker v-model="cronTime" format="HH:mm" value-format="HH:mm" placeholder="选择时间" />
          <span class="tf-sep">每月</span>
          <el-input-number v-model="cronDom" :min="1" :max="31" :step="1" />
          <span class="tf-sep">日</span>
        </div>
        <div v-else-if="cronPreset === 'hourly'" class="tf-hint mono">
          每小时整点（0 分）触发。
        </div>
        <div v-else class="tf-row">
          <el-input v-model="cronCustom" placeholder="例如 0 30 8 * * ?" />
        </div>
        <div class="tf-cron-preview mono">
          <span class="dim">cron：</span>
          <span :class="{ 'tf-invalid': !cronValid }">{{ effectiveCron || '(空)' }}</span>
          <span v-if="!cronValid" class="tf-invalid">⚠ 表达式非法</span>
        </div>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :disabled="!cronValid" @click="onSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tf-hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-top: 4px;
}
.tf-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.tf-dow {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
}
.tf-cron-preview {
  margin-top: 8px;
  font-size: 12px;
  padding: 6px 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border-soft);
  border-radius: 4px;
}
.tf-cron-preview .dim { color: var(--text-mute); margin-right: 4px; }
.tf-invalid { color: var(--danger, #d03050); }
.tf-sep { color: var(--text-mute); }
</style>