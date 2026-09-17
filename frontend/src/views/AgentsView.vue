<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAgentsStore } from '@/stores/agents'
import AgentFormDialog from '@/components/AgentFormDialog.vue'
import type { AgentMode, AgentSpec } from '@/types/api'

const store = useAgentsStore()
const dialogVisible = ref(false)
const dialogMode = ref<AgentMode>('create')
const editingAgent = ref<AgentSpec | null>(null)

onMounted(() => store.fetchList())

function openCreate() {
  dialogMode.value = 'create'
  editingAgent.value = null
  dialogVisible.value = true
}

function openEdit(row: AgentSpec) {
  dialogMode.value = 'edit'
  editingAgent.value = row
  dialogVisible.value = true
}

async function handleSubmit(spec: AgentSpec, mode: AgentMode) {
  if (mode === 'create') {
    await store.create(spec)
  } else {
    await store.update(spec.id, spec)
  }
}

async function handleDelete(row: AgentSpec) {
  try {
    await ElMessageBox.confirm(
      `确认删除数字人 "${row.id}"？此操作不可撤销。`,
      '确认操作',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  await store.remove(row.id)
  ElMessage.success(`数字人 "${row.id}" 已删除`)
}

function refresh() {
  store.fetchList()
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

function pad3(n: number): string {
  if (n < 10) return `00${n}`
  if (n < 100) return `0${n}`
  return `${n}`
}

const total = computed(() => store.list.length)
</script>

<template>
  <div class="agents-view">
    <header class="agents-view__head">
      <div class="agents-view__head-left">
        <div class="agents-view__crumb mono">
          // 数字人库 · 共 {{ pad3(total) }} 条记录
        </div>
        <h1 class="agents-view__title">已配置的数字人</h1>
      </div>
      <div class="agents-view__actions">
        <button class="btn-ghost mono" :disabled="store.loading" @click="refresh">
          <span class="btn-ghost__caret">↻</span>
          {{ store.loading ? '刷新中' : '刷新' }}
        </button>
        <button class="btn-primary" @click="openCreate">
          <span class="btn-primary__caret">+</span>
          新建数字人
        </button>
      </div>
    </header>

    <section v-if="store.loading && !store.list.length" class="agents-view__loading mono">
      正在加载数字人…
    </section>

    <section v-else-if="!store.list.length" class="agents-view__empty">
      <div class="agents-view__empty-mark mono">// 暂无记录</div>
      <div class="agents-view__empty-line mono">
        还没有数字人 — 点击
        <button class="agents-view__empty-cta" @click="openCreate">+ 新建数字人</button>
        来创建第一个
      </div>
    </section>

    <section v-else class="agents-table">
      <div class="agents-table__head mono">
        <div class="agents-table__col agents-table__col--id">标识</div>
        <div class="agents-table__col agents-table__col--model">模型</div>
        <div class="agents-table__col agents-table__col--meta">工具 · 技能</div>
        <div class="agents-table__col agents-table__col--act"></div>
      </div>

      <article
        v-for="row in store.list"
        :key="row.id"
        class="agents-table__row"
        @click="openEdit(row)"
      >
        <div class="agents-table__col agents-table__col--id">
          <div class="agents-table__id">
            <span class="agents-table__id-dot"></span>
            <span class="agents-table__id-text mono">{{ row.id }}</span>
            <span v-if="row.name && row.name !== row.id" class="agents-table__name">
              {{ row.name }}
            </span>
            <span
              v-if="row.dingtalk?.enabled"
              class="agents-table__bot-dot"
              title="已绑定钉钉机器人"
            >●</span>
          </div>
          <div v-if="row.sysPrompt" class="agents-table__prompt mono">
            {{ row.sysPrompt.length > 100 ? row.sysPrompt.slice(0, 100) + '…' : row.sysPrompt }}
          </div>
        </div>

        <div class="agents-table__col agents-table__col--model">
          <span class="agents-table__model-tag mono">{{ row.modelName }}</span>
        </div>

        <div class="agents-table__col agents-table__col--meta">
          <div class="agents-table__meta-item">
            <span class="agents-table__meta-key mono">工具</span>
            <span class="agents-table__meta-val mono">{{ pad2(row.tools?.length ?? 0) }}</span>
          </div>
          <div class="agents-table__meta-item">
            <span class="agents-table__meta-key mono">技能</span>
            <span class="agents-table__meta-val mono">{{ pad2(row.skills?.length ?? 0) }}</span>
          </div>
        </div>

        <div class="agents-table__col agents-table__col--act">
          <button class="row-action" @click.stop="openEdit(row)">编辑</button>
          <button class="row-action row-action--danger" @click.stop="handleDelete(row)">
            删除
          </button>
        </div>
      </article>
    </section>

    <AgentFormDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :initial="editingAgent"
      @submit="handleSubmit"
    />
  </div>
</template>

<style scoped>
.agents-view {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ----- head ----- */
.agents-view__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}
.agents-view__head-left { min-width: 0; }
.agents-view__crumb {
  font-size: 11px;
  color: var(--text-mute);
  margin-bottom: 6px;
}
.agents-view__title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}
.agents-view__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ----- buttons ----- */
.btn-primary {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: var(--bg-base);
  background: var(--signal);
  border: 1px solid var(--signal);
  padding: 7px 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: background-color 0.1s ease, border-color 0.1s ease;
}
.btn-primary:hover { background: var(--signal-hover); border-color: var(--signal-hover); }
.btn-primary__caret { font-family: var(--font-mono); font-size: 14px; }

.btn-ghost {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 6px 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color 0.1s ease, border-color 0.1s ease;
}
.btn-ghost:hover:not(:disabled) { color: var(--text); border-color: var(--text-mute); }
.btn-ghost:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-ghost__caret { font-size: 13px; }

/* ----- empty / loading ----- */
.agents-view__loading {
  padding: 56px 0;
  color: var(--text-mute);
  font-size: 13px;
  text-align: center;
}
.agents-view__empty {
  padding: 72px 0;
  text-align: center;
  border: 1px dashed var(--border);
}
.agents-view__empty-mark {
  font-size: 13px;
  color: var(--text-faint);
  margin-bottom: 8px;
}
.agents-view__empty-line {
  font-size: 13px;
  color: var(--text-mute);
}
.agents-view__empty-cta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--signal);
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.agents-view__empty-cta:hover { color: var(--signal-hover); }

/* ----- table ----- */
.agents-table { display: flex; flex-direction: column; }

.agents-table__head,
.agents-table__row {
  display: grid;
  grid-template-columns:
    minmax(0, 2.6fr)   /* id / name / prompt */
    minmax(0, 1.2fr)   /* model */
    minmax(0, 1.4fr)   /* tools/skills */
    minmax(0, 0.8fr);  /* actions */
  gap: 16px;
  align-items: start;
  padding: 12px 0;
}
.agents-table__head {
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
  padding: 10px 0;
  color: var(--text-mute);
  align-items: center;
  font-size: 11px;
}

.agents-table__row {
  border-bottom: 1px solid var(--border-soft);
  cursor: pointer;
  position: relative;
  transition: background-color 0.08s ease;
}
.agents-table__row:hover { background: var(--bg-elevated); }
.agents-table__row::before {
  content: '';
  position: absolute;
  left: -24px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: transparent;
}
.agents-table__row:hover::before { background: var(--signal); }

.agents-table__col { min-width: 0; }
.agents-table__col--act {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 14px;
  padding-top: 2px;
}

.agents-table__id {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.agents-table__id-dot {
  width: 6px;
  height: 6px;
  background: var(--ok);
  display: inline-block;
  flex-shrink: 0;
  align-self: center;
}
.agents-table__bot-dot {
  font-size: 8px;
  color: var(--signal);
  margin-left: 4px;
  flex-shrink: 0;
  align-self: center;
}
.agents-table__id-text {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}
.agents-table__name {
  font-size: 12px;
  color: var(--text-mute);
}
.agents-table__prompt {
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-family: var(--font-mono);
}

.agents-table__model-tag {
  display: inline-block;
  font-size: 11px;
  color: var(--signal-2);
  padding: 2px 8px;
  border: 1px solid var(--signal-2);
  background: transparent;
}

.agents-table__meta-item {
  display: grid;
  grid-template-columns: 50px auto;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 3px;
}
.agents-table__meta-key {
  font-size: 11px;
  color: var(--text-mute);
}
.agents-table__meta-val {
  font-size: 13px;
  color: var(--text);
}

.row-action {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  background: transparent;
  border: none;
  padding: 2px 0;
  cursor: pointer;
  transition: color 0.1s ease;
}
.row-action:hover { color: var(--text); }
.row-action--danger:hover { color: var(--err); }
</style>
