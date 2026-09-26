<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAgentsStore } from '@/stores/agents'
import Avatar from '@/components/ui/Avatar.vue'
import StatusDot from '@/components/ui/StatusDot.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import UButton from '@/components/ui/UButton.vue'
import DeptBar from '@/components/ui/DeptBar.vue'
import AgentFormDialog from '@/components/AgentFormDialog.vue'
import type { AgentMode, AgentSpec } from '@/types/api'

const store = useAgentsStore()
const router = useRouter()
const dialogVisible = ref(false)
const dialogMode = ref<AgentMode>('create')
const editingAgent = ref<AgentSpec | null>(null)

const deptFilter = ref<string>('all')

const DEPTS = [
  { id: 'all', name: '全部部门' },
  { id: 'fin', name: '财务部' },
  { id: 'hr', name: '人力资源部' },
  { id: 'it', name: '信息技术部' },
  { id: 'sale', name: '销售部' },
]

onMounted(() => store.fetchList())

// synthetic dept mapping: ids are mapped by hash so different agents land
// in different departments for visual variety. once backend exposes a real
// field, swap this for `agent.dept`.
function syntheticDept(id: string): string {
  const buckets = ['fin', 'fin', 'hr', 'it', 'sale', 'fin']
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return buckets[h % buckets.length]
}

const filtered = computed(() =>
  store.list.map((a) => ({ ...a, _deptId: a.dept ? deptIdOf(a.dept) : syntheticDept(a.id) }))
    .filter((a) => deptFilter.value === 'all' || a._deptId === deptFilter.value),
)

function deptIdOf(name: string): string {
  if (name.includes('财务')) return 'fin'
  if (name.includes('人力')) return 'hr'
  if (name.includes('信息') || name.includes('IT') || name.includes('技术')) return 'it'
  if (name.includes('销售')) return 'sale'
  return 'all'
}

const grouped = computed(() => {
  const groups: Record<string, { deptName: string; agents: typeof filtered.value }> = {}
  for (const a of filtered.value) {
    const deptName = DEPTS.find((d) => d.id === a._deptId)?.name || '其他'
    if (!groups[a._deptId]) groups[a._deptId] = { deptName, agents: [] as any }
    groups[a._deptId].agents.push(a)
  }
  return Object.entries(groups)
})

function agentName(row: AgentSpec) {
  return row.name && row.name.trim() ? row.name : row.id
}

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

function openDetail(row: AgentSpec) {
  router.push(`/digital-humans/${encodeURIComponent(row.id)}`)
}

function openChat(row: AgentSpec) {
  router.push({ path: '/chat', query: { agent: row.id } })
}

async function handleSubmit(spec: AgentSpec, mode: AgentMode) {
  if (mode === 'create') {
    await store.create(spec)
  } else {
    await store.update(spec.id, spec)
  }
}

async function handleDelete(row: AgentSpec) {
  const hasBot = row.dingtalk?.enabled
  const hasSessions = (row as any).sessions != null && (row as any).sessions > 0
  const bullets = [
    '该数字人将立即从列表中移除',
    '其系统提示词、模型绑定、技能配置一并清空',
    hasBot ? '已绑定的钉钉机器人将被停用并解除绑定' : null,
    hasSessions ? '关联的会话历史将保留在工作区目录中，不再可访问' : null,
  ].filter(Boolean)
  const displayName = agentName(row)
  const html = `
    <div class="del-confirm">
      <p class="del-confirm__lead">确认删除数字人 <b>${escapeHtml(displayName)}</b><span class="del-confirm__id">（${escapeHtml(row.id)}）</span>？此操作不可撤销。</p>
      <ul class="del-confirm__list">
        ${bullets.map((b) => `<li>${escapeHtml(b as string)}</li>`).join('')}
      </ul>
      <p class="del-confirm__hint">如不再需要，建议先备份其系统提示词。</p>
    </div>
  `
  try {
    await ElMessageBox.confirm(html, '删除数字人', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'el-message-box--danger',
      dangerouslyUseHTMLString: true,
      autofocus: false,
    })
  } catch {
    return
  }
  try {
    await store.remove(row.id)
    ElMessage.success(`数字人 "${displayName}" 已删除`)
  } catch {
    /* axios interceptor 已 ElMessage.error */
  }
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&')
    .replace(/</g, '<')
    .replace(/>/g, '>')
    .replace(/"/g, '"')
    .replace(/'/g, '&#39;')
}

const total = computed(() => filtered.value.length)
</script>

<template>
  <div class="agents-view">
    <header class="agents-view__head">
      <div class="agents-view__head-left">
        <div class="agents-view__h1">部门数字人</div>
        <div class="agents-view__sub">共 {{ total }} 个数字人 · 按所属部门筛选</div>
      </div>
      <div class="agents-view__actions">
        <UButton disabled title="开发中">导入数字人包</UButton>
        <UButton variant="primary" @click="openCreate">+ 新建数字人</UButton>
      </div>
    </header>

    <DeptBar v-model="deptFilter" :options="DEPTS" />

    <section v-if="store.loading && !store.list.length" class="agents-view__loading">
      正在加载数字人…
    </section>

    <section v-else-if="!filtered.length" class="agents-view__empty">
      <div class="agents-view__empty-mark">// 暂无记录</div>
      <div class="agents-view__empty-line">
        还没有数字人 —
        <button class="agents-view__empty-cta" @click="openCreate">+ 新建数字人</button>
        来创建第一个
      </div>
    </section>

    <template v-else>
      <div v-for="[deptId, group] in grouped" :key="deptId" class="agents-view__group">
        <div class="agents-view__group-head">
          {{ group.deptName }}
          <span class="agents-view__group-cnt">{{ group.agents.length }} 个数字人</span>
        </div>
        <div class="acard-grid">
          <article
            v-for="row in group.agents"
            :key="row.id"
            class="acard"
            @click="openDetail(row)"
          >
            <div class="acard__row1">
              <Avatar :initial="(row.name || row.id).slice(0, 1)" size="md" />
              <div class="acard__id">
                <div class="acard__name">
                  {{ agentName(row) }}
                  <TagBadge>未配置版本</TagBadge>
                </div>
                <div class="acard__meta">
                  <StatusDot status="online" /> · 负责人 {{ row.owner || '未指定' }}
                </div>
              </div>
            </div>
            <div class="acard__desc">
              {{ row.sysPrompt ? (row.sysPrompt.length > 60 ? row.sysPrompt.slice(0, 60) + '…' : row.sysPrompt) : '尚未配置系统提示词。' }}
            </div>
            <div class="acard__stats">
              <div class="acard__stat">
                <b>{{ row.skills?.length ?? 0 }}</b>
                <span>技能</span>
              </div>
              <div class="acard__stat">
                <b>{{ row.tools?.length ?? 0 }}</b>
                <span>工具</span>
              </div>
              <div class="acard__stat" title="用量数据接入中">
                <b>—</b>
                <span>今日 token</span>
              </div>
              <div class="acard__stat" title="用量数据接入中">
                <b>—</b>
                <span>本月费用</span>
              </div>
            </div>
            <div class="acard__ops">
              <UButton variant="primary" @click.stop="openChat(row)">对话</UButton>
              <UButton @click.stop="openDetail(row)">详情</UButton>
              <UButton disabled title="开发中" @click.stop>导出包</UButton>
              <UButton variant="ghost" @click.stop="openEdit(row)">编辑</UButton>
              <UButton disabled @click.stop="handleDelete(row)">删除</UButton>
            </div>
          </article>
        </div>
      </div>
    </template>

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
  gap: 16px;
}

.agents-view__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 4px;
}
.agents-view__head-left { min-width: 0; }
.agents-view__h1 {
  font-size: var(--fs-19);
  font-weight: 650;
  letter-spacing: 0.2px;
  color: var(--txt);
}
.agents-view__sub {
  color: var(--txt2);
  font-size: var(--fs-13);
  margin-top: 3px;
}
.agents-view__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* loading / empty */
.agents-view__loading {
  padding: 56px 0;
  text-align: center;
  color: var(--txt2);
  font-size: var(--fs-13);
}
.agents-view__empty {
  padding: 64px 24px;
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  background: #fff;
}
.agents-view__empty-mark { color: var(--txt3); margin-bottom: 8px; font-size: var(--fs-13); }
.agents-view__empty-line { color: var(--txt2); font-size: var(--fs-13); }
.agents-view__empty-cta {
  font-family: var(--font-mono);
  color: var(--primary);
  background: transparent;
  border: none;
  padding: 0 4px;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
  font-size: var(--fs-13);
}

/* group */
.agents-view__group { margin-bottom: 26px; }
.agents-view__group-head {
  font-size: var(--fs-13);
  font-weight: 650;
  color: var(--txt);
  margin: 6px 0 10px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.agents-view__group-cnt {
  color: var(--txt3);
  font-weight: 500;
  font-size: var(--fs-12);
}

/* card grid */
.acard-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 14px;
}

/* card */
.acard {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px;
  cursor: pointer;
  box-shadow: var(--shadow);
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.acard:hover {
  transform: translateY(-2px);
  border-color: #cdd1d8;
  box-shadow: 0 8px 26px rgba(20,24,31,.09);
}

.acard__row1 {
  display: flex;
  gap: 11px;
  align-items: center;
}
.acard__id { min-width: 0; flex: 1; }
.acard__name {
  font-size: var(--fs-15);
  font-weight: 650;
  letter-spacing: 0.2px;
  color: var(--txt);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.acard__meta {
  color: var(--txt2);
  font-size: var(--fs-12);
  margin-top: 2px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.acard__desc {
  font-size: var(--fs-12);
  color: var(--txt2);
  line-height: 1.55;
  height: 38px;
  overflow: hidden;
}

.acard__stats {
  display: flex;
  gap: 14px;
  padding: 10px 0;
  border-top: 1px dashed var(--line);
  border-bottom: 1px dashed var(--line);
}
.acard__stat {
  font-size: var(--fs-11);
  color: var(--txt3);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.acard__stat b {
  display: block;
  font-size: var(--fs-13);
  color: var(--txt);
  font-weight: 650;
  letter-spacing: 0.2px;
}

.acard__ops {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.acard__ops :deep(.u-btn) {
  height: 28px;
  padding: 0 10px;
  font-size: var(--fs-12);
}
</style>
