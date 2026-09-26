<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useTasksStore } from '@/stores/tasks'
import { fmtTime } from '@/utils/datetime'
import type { ScheduledTask, TaskRun } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  task: ScheduledTask | null
}>()

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
}>()

const router = useRouter()
const tasksStore = useTasksStore()

const runs = ref<TaskRun[]>([])
const loading = ref(false)
let pollHandle: number | null = null

watch(() => props.modelValue, async (open) => {
  if (open && props.task) {
    await refresh()
    startPolling()
  } else {
    stopPolling()
  }
})

onBeforeUnmount(stopPolling)

async function refresh() {
  if (!props.task) return
  loading.value = true
  try {
    runs.value = await tasksStore.fetchRuns(props.task.id, 100)
  } finally {
    loading.value = false
  }
}

function startPolling() {
  stopPolling()
  pollHandle = window.setInterval(async () => {
    if (!props.task) return
    const anyRunning = runs.value.some(r => r.status === 'RUNNING' || r.status === 'QUEUED')
    if (anyRunning) await refresh()
  }, 3000)
}

function stopPolling() {
  if (pollHandle != null) {
    clearInterval(pollHandle)
    pollHandle = null
  }
}

function close() {
  emit('update:modelValue', false)
}

function statusColor(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'TIMEOUT': return 'warning'
    case 'SKIPPED': return 'info'
    case 'RUNNING': return 'primary'
    case 'QUEUED': return 'info'
    default: return 'info'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'QUEUED': return '排队中'
    case 'RUNNING': return '执行中'
    case 'SUCCESS': return '成功'
    case 'FAILED': return '失败'
    case 'TIMEOUT': return '超时'
    case 'SKIPPED': return '跳过'
    default: return status
  }
}

function durationText(r: TaskRun): string {
  if (r.status === 'QUEUED' || r.status === 'RUNNING') return '执行中...'
  if (r.durationMs == null) return '-'
  const s = Math.round(r.durationMs / 1000)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  return `${m} 分 ${s % 60} 秒`
}

function tokensText(r: TaskRun): string {
  if (r.tokensTotal == null) return '-'
  const inT = r.tokensInput ?? 0
  const outT = r.tokensOutput ?? 0
  const cacheT = r.tokensCached ?? 0
  const total = r.tokensTotal ?? 0
  const cachedRate = inT > 0 ? Math.round((cacheT / inT) * 100) : 0
  return `${inT.toLocaleString()} / ${outT.toLocaleString()} / 缓存 ${cachedRate}% / 总 ${total.toLocaleString()}`
}

function jumpToConversation(r: TaskRun) {
  if (!props.task || !r.sessionId) {
    ElMessage.warning('该次执行没有关联会话')
    return
  }
  close()
  router.push({ path: '/chat', query: { agent: props.task.agentId, session: r.sessionId } })
}

function togglePreview(r: TaskRun) {
  const i = runs.value.indexOf(r)
  if (i < 0) return
  // 不真"切换"，统一用 dialog 展示
  previewTarget.value = r
  previewVisible.value = true
}

const previewVisible = ref(false)
const previewTarget = ref<TaskRun | null>(null)
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="task ? `执行记录：${task.name}` : '执行记录'"
    direction="rtl"
    size="720"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-if="task" class="tr-meta mono">
      <span class="dim">cron:</span> {{ task.cron }}
      <span class="sep">|</span>
      <span class="dim">数字人:</span> {{ task.agentName || task.agentId }}
      <span class="sep">|</span>
      <span class="dim">skill:</span>
      <template v-if="task.skills && task.skills.length">
        {{ task.skills.join('、') }}
      </template>
      <template v-else>
        <span class="dim">(沿用数字人配置)</span>
      </template>
    </div>
    <el-table v-loading="loading" :data="runs" stripe size="small" empty-text="暂无执行记录">
      <el-table-column label="触发" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.triggerType === 'MANUAL' ? 'warning' : 'primary'" effect="plain">
            {{ row.triggerType === 'MANUAL' ? '手动' : '定时' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="起始时间" width="160">
        <template #default="{ row }">
          <span class="mono">{{ row.startedAt ? fmtTime(row.startedAt) : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="statusColor(row.status)" effect="plain">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">
          <span class="mono">{{ durationText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Tokens" min-width="220">
        <template #default="{ row }">
          <span class="mono small">{{ tokensText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="输出预览 / 操作" min-width="220">
        <template #default="{ row }">
          <div class="tr-preview">
            <div class="tr-preview-text" v-if="row.replyPreview">{{ row.replyPreview }}</div>
            <div v-else-if="row.error" class="tr-error mono small">{{ row.error }}</div>
            <div v-else class="dim small">-</div>
            <div class="tr-actions">
              <el-button size="small" link @click="togglePreview(row)">查看完整</el-button>
              <el-button
                size="small"
                link
                type="primary"
                :disabled="!row.sessionId"
                @click="jumpToConversation(row)"
              >
                跳转对话
              </el-button>
            </div>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="previewVisible"
      title="执行详情"
      width="600"
      append-to-body
    >
      <template v-if="previewTarget">
        <div class="tr-detail">
          <div class="tr-row mono"><span class="dim">runId:</span> {{ previewTarget.runId }}</div>
          <div class="tr-row mono"><span class="dim">触发:</span> {{ previewTarget.triggerType }}</div>
          <div class="tr-row mono"><span class="dim">状态:</span> {{ statusLabel(previewTarget.status) }}</div>
          <div class="tr-row mono" v-if="previewTarget.startedAt">
            <span class="dim">开始:</span> {{ fmtTime(previewTarget.startedAt) }}
          </div>
          <div class="tr-row mono" v-if="previewTarget.finishedAt">
            <span class="dim">结束:</span> {{ fmtTime(previewTarget.finishedAt) }}
          </div>
          <div class="tr-row mono" v-if="previewTarget.durationMs != null">
            <span class="dim">耗时:</span> {{ previewTarget.durationMs }} ms
          </div>
          <div class="tr-row mono" v-if="previewTarget.sessionId">
            <span class="dim">会话:</span> {{ previewTarget.sessionId }}
          </div>
          <div v-if="previewTarget.error" class="tr-error mono">
            {{ previewTarget.error }}
          </div>
          <div v-if="previewTarget.replyPreview" class="tr-output">
            <div class="dim small mono">完整输出（仅显示前 500 字，完整内容请跳转对话）</div>
            <pre class="tr-output-pre">{{ previewTarget.replyPreview }}</pre>
          </div>
        </div>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.tr-meta {
  font-size: 12px;
  color: var(--text-mute);
  margin-bottom: 16px;
  padding: 8px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border-soft);
  border-radius: 4px;
}
.tr-meta .sep { margin: 0 6px; opacity: 0.5; }
.tr-preview {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tr-preview-text {
  font-size: 12px;
  color: var(--text-mute);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.4;
}
.tr-error { color: var(--danger, #d03050); }
.dim { color: var(--text-mute); }
.small { font-size: 12px; }
.tr-actions { display: flex; gap: 4px; }
.tr-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.tr-row { line-height: 1.6; }
.tr-output-pre {
  background: var(--bg-surface);
  border: 1px solid var(--border-soft);
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>