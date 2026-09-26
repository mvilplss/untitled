<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAgentsStore } from '@/stores/agents'
import { useTasksStore } from '@/stores/tasks'
import TaskFormDialog from '@/components/TaskFormDialog.vue'
import TaskRunsDrawer from '@/components/TaskRunsDrawer.vue'
import { fmtTime } from '@/utils/datetime'
import type { ScheduledTask, TaskRun } from '@/types/api'

const agentsStore = useAgentsStore()
const tasksStore = useTasksStore()

const showDialog = ref(false)
const editing = ref<ScheduledTask | null>(null)
const showRuns = ref(false)
const runsTask = ref<ScheduledTask | null>(null)

const rows = computed(() => tasksStore.list)

onMounted(async () => {
  await Promise.all([agentsStore.fetchList(), tasksStore.fetchList()])
})

async function openCreate() {
  editing.value = null
  showDialog.value = true
}

async function openEdit(t: ScheduledTask) {
  editing.value = await tasksStore.get(t.id)
  showDialog.value = true
}

async function onSubmit(task: ScheduledTask, isEdit: boolean) {
  try {
    if (isEdit) {
      await tasksStore.update(task.id, {
        id: task.id, name: task.name, agentId: task.agentId,
        prompt: task.prompt, skills: task.skills, cron: task.cron, enabled: task.enabled,
      })
      ElMessage.success('已更新')
    } else {
      await tasksStore.create({
        id: task.id, name: task.name, agentId: task.agentId,
        prompt: task.prompt, skills: task.skills, cron: task.cron, enabled: task.enabled,
      })
      ElMessage.success('已创建')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || e?.message || '操作失败')
  }
}

async function onDelete(t: ScheduledTask) {
  try {
    await ElMessageBox.confirm(
      `确认删除任务「${t.name}」？Quartz 触发器与全部执行记录将一并清除。`,
      '删除定时任务',
      { type: 'warning' },
    )
  } catch { return }
  try {
    await tasksStore.remove(t.id)
    ElMessage.success('已删除')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || e?.message || '删除失败')
  }
}

async function onRunNow(t: ScheduledTask) {
  try {
    const runId = await tasksStore.runNow(t.id)
    ElMessage.success(`已开始执行（runId=${runId}），请查看执行记录`)
    // 刷新列表（lastRunAt 实时显示），然后打开抽屉
    await tasksStore.fetchList()
    openRuns(t)
  } catch (e: any) {
    const msg = e?.response?.data?.error || e?.message || '执行失败'
    ElMessage.error(msg)
  }
}

async function onToggleEnabled(t: ScheduledTask, val: boolean) {
  try {
    await tasksStore.update(t.id, {
      id: t.id, name: t.name, agentId: t.agentId,
      prompt: t.prompt, skills: t.skills, cron: t.cron, enabled: val,
    })
    ElMessage.success(val ? '已启用' : '已停用')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || e?.message || '切换失败')
  }
}

function openRuns(t: ScheduledTask) {
  runsTask.value = t
  showRuns.value = true
}

function formatCronDesc(t: ScheduledTask): string {
  // 简单描述（与后端无关，仅展示）
  const c = t.cron.trim().split(/\s+/)
  if (c.length !== 6) return t.cron
  const [sec, min, hour, dom, mon, dow] = c
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dow === '?') {
    return `每天 ${hour.padStart(2,'0')}:${min.padStart(2,'0')}`
  }
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '?' && mon === '*' && dow !== '*') {
    const names = ['', '一', '二', '三', '四', '五', '六', '日']
    const dows = dow.split(',').map(d => '周' + (names[Number(d.replace(/[A-Z]/g,''))] || d)).join('、')
    return `${dows} ${hour.padStart(2,'0')}:${min.padStart(2,'0')}`
  }
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && /^\d+$/.test(dom) && mon === '*' && dow === '?') {
    return `每月 ${dom} 日 ${hour.padStart(2,'0')}:${min.padStart(2,'0')}`
  }
  if (sec === '0' && min === '0' && hour === '*' && dom === '*' && mon === '*' && dow === '?') {
    return '每小时整点'
  }
  return t.cron
}

function formatTime(ts?: number | null): string {
  if (ts == null) return '-'
  return fmtTime(ts)
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">定时任务</h2>
        <div class="page-desc mono">Quartz 触发引擎 · 每次执行现造新会话（避免上下文串台）· 跳过同任务重叠运行</div>
      </div>
      <el-button type="primary" @click="openCreate">新建定时任务</el-button>
    </div>

    <el-table v-loading="tasksStore.loading" :data="rows" stripe empty-text="暂无定时任务">
      <el-table-column label="任务" min-width="220">
        <template #default="{ row }">
          <div class="cell-stack">
            <div class="cell-title">{{ row.name }}</div>
            <div class="cell-sub mono dim">{{ row.id }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="提示词预览" min-width="220">
        <template #default="{ row }">
          <el-tooltip :content="row.prompt" placement="top" :show-after="300" raw-content>
            <div class="cell-ellipsis small">{{ row.prompt }}</div>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="执行计划" min-width="170">
        <template #default="{ row }">
          <div class="mono">{{ formatCronDesc(row) }}</div>
          <div class="dim small mono">{{ row.cron }}</div>
        </template>
      </el-table-column>
      <el-table-column label="上次执行" width="140">
        <template #default="{ row }">
          <span class="mono small">{{ formatTime(row.lastRunAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="下次执行" width="140">
        <template #default="{ row }">
          <span class="mono small">
            <template v-if="row.nextRunAt">{{ formatTime(row.nextRunAt) }}</template>
            <template v-else-if="row.enabled">计算中...</template>
            <template v-else class="dim">已停用</template>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @update:model-value="(v: boolean) => onToggleEnabled(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="onRunNow(row)">立即执行</el-button>
          <el-button size="small" @click="openRuns(row)">执行记录</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <TaskFormDialog v-model="showDialog" :initial="editing" @submit="onSubmit" />
    <TaskRunsDrawer v-model="showRuns" :task="runsTask" />
  </div>
</template>

<style scoped>
.page { padding: 24px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 18px;
}
.page-title { margin: 0 0 4px 0; font-size: 20px; font-weight: 600; }
.page-desc { color: var(--text-mute); font-size: 12px; }
.cell-stack { display: flex; flex-direction: column; gap: 2px; }
.cell-title { font-size: 14px; font-weight: 500; }
.cell-sub { font-size: 11px; }
.cell-ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.5;
}
.dim { color: var(--text-mute); }
.small { font-size: 12px; }
</style>