<script setup lang="ts">
import { computed } from 'vue'
import { useSkillsStore } from '@/stores/skills'

const props = defineProps<{
  modelValue: string[]
  disabled?: boolean
  /**
   * 技能白名单：仅展示此列表中的技能。
   * - undefined：不限制（展示全部）
   * - 空数组：无可选项
   * - 非空：按名字过滤；modelValue 中不在白名单里的会被剪除（emit 时）
   */
  allowed?: string[]
}>()

const emit = defineEmits<{
  'update:modelValue': [v: string[]]
}>()

const skillsStore = useSkillsStore()

const filtered = computed(() => {
  if (props.allowed == null) return skillsStore.list
  const set = new Set(props.allowed)
  return skillsStore.list.filter(s => set.has(s.name))
})

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function onUpdate(v: string[]) {
  // 若传了 allowed，剪除不在白名单中的项（防止外部 stale state 残留）
  if (props.allowed != null) {
    const set = new Set(props.allowed)
    emit('update:modelValue', v.filter(n => set.has(n)))
  } else {
    emit('update:modelValue', v)
  }
}
</script>

<template>
  <div>
    <div class="smp-hint mono">
      <template v-if="allowed != null">
        已按数字人授权范围筛选（{{ filtered.length }} / {{ skillsStore.list.length }}）。
      </template>
      <template v-else>
        该数字人可调用的技能（从 .agentscope/skills/ 加载）。
        留空表示对所有技能可见。
      </template>
      <span v-if="!filtered.length" class="smp-hint-warn">
        — 暂无可选技能，请前往「技能」页面上传。
      </span>
    </div>
    <el-checkbox-group
      :model-value="modelValue"
      class="smp-grid"
      :disabled="disabled || !filtered.length"
      @update:model-value="onUpdate"
    >
      <el-checkbox
        v-for="s in filtered"
        :key="s.name"
        :value="s.name"
        class="smp-cell"
      >
        <div class="smp-cell__label">{{ s.name }}</div>
        <div class="smp-cell__desc">{{ s.description }}</div>
        <div class="smp-cell__name mono">
          {{ s.resourceCount }} 个资源 · {{ formatBytes(s.sizeBytes) }}
        </div>
      </el-checkbox>
    </el-checkbox-group>
  </div>
</template>

<style scoped>
.smp-hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-bottom: 12px;
}
.smp-hint-warn { color: var(--warn); }

.smp-grid {
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

.smp-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--border-soft);
}
.smp-cell:last-child { border-bottom: none; }
.smp-cell :deep(.el-checkbox__label) {
  display: block;
  flex: 1;
  width: auto;
  white-space: normal;
  line-height: 1.5;
  padding-left: 8px;
}
.smp-cell__label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  line-height: 1.4;
  margin-bottom: 4px;
}
.smp-cell__desc {
  display: block;
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
}
.smp-cell__name {
  display: block;
  font-size: 11px;
  color: var(--text-faint);
  line-height: 1.5;
  margin-bottom: 6px;
  word-break: break-all;
}
</style>