<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentsStore } from '@/stores/agents'
import Avatar from '@/components/ui/Avatar.vue'
import StatusDot from '@/components/ui/StatusDot.vue'
import TagBadge from '@/components/ui/TagBadge.vue'
import UButton from '@/components/ui/UButton.vue'
import type { AgentSpec } from '@/types/api'

const route = useRoute()
const router = useRouter()
const store = useAgentsStore()

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'chat',     label: '对话内容' },
  { key: 'task',     label: '工作任务' },
  { key: 'usage',    label: 'Token 消耗' },
  { key: 'spec',     label: '配置 · AgentSpec' },
]

const activeTab = ref<string>('overview')

const agent = computed<AgentSpec | undefined>(() => {
  const id = route.params.id as string
  return store.list.find((a) => a.id === id)
})

function back() {
  router.push('/digital-humans')
}

function openChat() {
  if (!agent.value) return
  router.push({ path: '/chat', query: { agent: agent.value.id } })
}

onMounted(async () => {
  if (!store.list.length) await store.fetchList()
})

watch(
  () => route.params.id,
  () => { activeTab.value = 'overview' },
)

function agentName(row: AgentSpec) {
  return row.name && row.name.trim() ? row.name : row.id
}

const skills = computed(() => agent.value?.skills ?? [])
const tools  = computed(() => agent.value?.tools ?? [])

// render tool meta: backend only returns tool name strings; classify by
// substring so the table is at least structurally meaningful.
function classifyTool(name: string) {
  const n = name.toLowerCase()
  let type: string = 'MCP'
  if (n.includes('http') || n.includes('api')) type = 'HTTP'
  else if (n.includes('db') || n.includes('sql') || n.includes('查询')) type = 'DB'
  else if (n.includes('rpa') || n.includes('remote') || n.includes('远程')) type = 'RPA'
  return { type, permission: n.includes('写') || n.includes('write') ? 'write' : 'read' }
}
</script>

<template>
  <div class="agent-detail">
    <!-- breadcrumb -->
    <div class="agent-detail__crumb">
      <span class="agent-detail__back" @click="back">‹ 返回部门数字人</span>
      <span class="agent-detail__crumb-sep">/</span>
      <span>部门数字人</span>
    </div>

    <!-- header -->
    <div v-if="agent" class="dhead">
      <Avatar :initial="(agent.name || agent.id).slice(0, 1)" size="lg" />
      <div class="dhead__who">
        <h2 class="dhead__name">
          {{ agentName(agent) }}
          <StatusDot :status="agent.status || 'online'" />
          <TagBadge :variant="agent.status === 'maintain' ? 'warning' : 'success'">
            {{ agent.status === 'maintain' ? '维护中' : '在线运行' }}
          </TagBadge>
          <TagBadge>未配置版本</TagBadge>
          <TagBadge variant="primary">部门可见</TagBadge>
        </h2>
        <div class="dhead__meta">
          <span>所属部门 <b>{{ agent.dept || '未指定' }}</b></span>
          <span>负责人 <b>{{ agent.owner || '未指定' }}</b></span>
          <span>ID <b class="mono">{{ agent.id }}</b></span>
          <span>技能 <b>{{ skills.length }}</b></span>
          <span>工具 <b>{{ tools.length }}</b></span>
          <span>可用性 <b>—</b></span>
        </div>
      </div>
      <div class="dhead__ops">
        <UButton disabled title="开发中">⬇ 下载数字人包</UButton>
        <UButton variant="primary" @click="openChat">开始对话</UButton>
      </div>
    </div>

    <div v-else class="dhead dhead--missing">
      <div class="dhead__missing">未找到数字人 <b>{{ route.params.id }}</b></div>
      <UButton @click="back">返回列表</UButton>
    </div>

    <!-- tabs -->
    <div v-if="agent" class="tabs">
      <div
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ 'tab--active': activeTab === t.key }"
        @click="activeTab = t.key"
      >{{ t.label }}</div>
    </div>

    <!-- tab body -->
    <div v-if="agent && activeTab === 'overview'" class="tab-body">
      <!-- capability -->
      <section class="sec">
        <div class="sec__head">
          能力范围 <span class="sec__cnt">边界决定是否拒答与转交</span>
        </div>
        <div class="capbox-grid">
          <div class="capbox capbox--do">
            <h4>✅ 能做什么</h4>
            <ul>
              <li>该数字人尚未显式声明能力边界</li>
            </ul>
          </div>
          <div class="capbox capbox--dont">
            <h4>⛔ 不做什么</h4>
            <ul>
              <li>该数字人尚未显式声明能力边界</li>
            </ul>
          </div>
        </div>
        <div class="capbox-foot">
          <span class="capbox-foot__label">擅长领域</span>
          <TagBadge>未设置</TagBadge>
          <span class="capbox-foot__policy">越界策略：<b>拒答并转交人工</b></span>
        </div>
      </section>

      <!-- skills -->
      <section class="sec">
        <div class="sec__head">
          拥有技能 <span class="sec__cnt">{{ skills.length }} 个 · 可一键启停与升级</span>
        </div>
        <div v-if="skills.length" class="skill-grid">
          <div v-for="s in skills" :key="s" class="skill-item">
            <div class="skill-item__head">
              <span>{{ s }}</span>
              <TagBadge variant="success">已启用</TagBadge>
            </div>
            <div class="skill-item__desc">该技能尚未配置详细描述。</div>
            <div class="skill-item__foot">
              <span class="mono">v—</span>
              <span>近期调用 — 次</span>
            </div>
          </div>
        </div>
        <div v-else class="skill-empty">尚未绑定任何技能。</div>
      </section>

      <!-- tools -->
      <section class="sec">
        <div class="sec__head">
          拥有工具 <span class="sec__cnt">{{ tools.length }} 个 · 写权限与不可逆操作需审批</span>
        </div>
        <table v-if="tools.length" class="tbl">
          <thead>
            <tr>
              <th>工具名称</th><th>类型</th><th>权限</th><th>副作用级别</th><th>调用限流</th><th>健康度</th><th>审批</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tools" :key="t">
              <td><b>{{ t }}</b></td>
              <td><TagBadge>{{ classifyTool(t).type }}</TagBadge></td>
              <td>{{ classifyTool(t).permission === 'read' ? '只读' : '读写' }}</td>
              <td>
                <TagBadge :variant="classifyTool(t).permission === 'read' ? 'success' : 'warning'">
                  {{ classifyTool(t).permission }}
                </TagBadge>
              </td>
              <td class="mono">—</td>
              <td>—</td>
              <td>—</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="skill-empty">尚未绑定任何工具。</div>
      </section>

      <!-- usage overview -->
      <section class="sec">
        <div class="sec__head">
          消耗概览 <span class="sec__cnt">单位 token / 元</span>
        </div>
        <div class="kpi-grid">
          <div class="kpi">
            <div class="kpi__l">今日 token</div>
            <div class="kpi__v">—</div>
            <div class="kpi__d kpi__d--mute">用量数据接入中</div>
          </div>
          <div class="kpi">
            <div class="kpi__l">本周 token</div>
            <div class="kpi__v">—</div>
            <div class="kpi__d kpi__d--mute">用量数据接入中</div>
          </div>
          <div class="kpi">
            <div class="kpi__l">本月 token</div>
            <div class="kpi__v">—</div>
            <div class="kpi__d"><span class="mono">≈ ¥—</span></div>
            <div class="bar"><i style="width: 0%" /></div>
          </div>
          <div class="kpi">
            <div class="kpi__l">预算使用率</div>
            <div class="kpi__v">—</div>
            <div class="kpi__d kpi__d--mute">限额 — · 阈值 80%</div>
          </div>
        </div>
        <div class="overview-foot">
          本月会话 <b>—</b> 次 · 完成任务 <b>—</b> 个 ·
          平均时延 <b>— ms</b> · 失败率 <b>— %</b>
        </div>
      </section>

      <!-- recent tasks placeholder -->
      <section class="sec">
        <div class="sec__head">最近工作任务</div>
        <table class="tbl">
          <thead>
            <tr>
              <th>任务</th><th>触发</th><th>状态</th><th>进度</th><th>产出物</th><th>消耗 token</th><th>耗时</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td colspan="7" class="tbl-empty">该模块开发中</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>

    <!-- placeholder tabs -->
    <div v-else-if="agent" class="tab-body">
      <div class="tab-placeholder">
        <div class="tab-placeholder__icon">⚒</div>
        <div class="tab-placeholder__title">{{ tabs.find(t => t.key === activeTab)?.label }}</div>
        <div class="tab-placeholder__sub">该模块开发中，后续接入</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-detail { display: flex; flex-direction: column; gap: 16px; }

.agent-detail__crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--txt2);
  font-size: var(--fs-13);
}
.agent-detail__back { cursor: pointer; }
.agent-detail__back:hover { color: var(--primary); }
.agent-detail__crumb-sep { color: var(--line); }

/* head */
.dhead {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px 20px;
  box-shadow: var(--shadow);
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.dhead__who { flex: 1; min-width: 0; }
.dhead__name {
  margin: 0;
  font-size: var(--fs-20);
  font-weight: 680;
  letter-spacing: 0.3px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.dhead__meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  color: var(--txt2);
  font-size: var(--fs-12);
  margin-top: 7px;
}
.dhead__meta b { color: var(--txt); font-weight: 600; }
.dhead__ops {
  display: flex;
  gap: 8px;
  align-items: center;
}
.dhead--missing {
  align-items: center;
  justify-content: space-between;
}
.dhead__missing { color: var(--txt2); font-size: var(--fs-13); }
.dhead__missing b { color: var(--txt); font-weight: 650; }

/* tabs */
.tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
}
.tab {
  padding: 9px 14px;
  font-size: var(--fs-13);
  color: var(--txt2);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color 0.12s ease, border-color 0.12s ease;
}
.tab:hover { color: var(--txt); }
.tab--active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}

.tab-body { display: flex; flex-direction: column; gap: 16px; }

/* section */
.sec {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 18px;
  box-shadow: var(--shadow);
}
.sec__head {
  font-size: var(--fs-13);
  font-weight: 650;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--txt);
}
.sec__cnt { color: var(--txt3); font-weight: 500; font-size: var(--fs-12); }

/* capability box */
.capbox-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.capbox {
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 13px 15px;
  background: #fcfcfd;
}
.capbox--do {
  background: #fbfefc;
  border-color: #d9efe2;
}
.capbox--dont {
  background: #fffbfb;
  border-color: #f4dcdd;
}
.capbox h4 {
  margin: 0 0 8px;
  font-size: var(--fs-12);
  font-weight: 650;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--txt);
}
.capbox ul { list-style: none; padding: 0; margin: 0; }
.capbox li {
  font-size: var(--fs-12);
  color: var(--txt2);
  padding: 3.5px 0 3.5px 15px;
  position: relative;
}
.capbox li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 12px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
}
.capbox--do li::before { background: var(--green); }
.capbox--dont li::before { background: var(--red); }
.capbox-foot {
  margin-top: 12px;
  display: flex;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
  font-size: var(--fs-12);
  color: var(--txt3);
}
.capbox-foot__label { font-size: var(--fs-12); color: var(--txt3); }
.capbox-foot__policy {
  margin-left: auto;
  font-size: var(--fs-12);
  color: var(--txt2);
}
.capbox-foot__policy b { color: var(--txt); }

/* skill grid */
.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(215px, 1fr));
  gap: 10px;
}
.skill-item {
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 11px 12px;
  background: #fff;
}
.skill-item__head {
  font-size: var(--fs-13);
  font-weight: 620;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}
.skill-item__desc {
  font-size: var(--fs-12);
  color: var(--txt2);
  margin: 5px 0 8px;
  height: 34px;
  overflow: hidden;
}
.skill-item__foot {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-11);
  color: var(--txt3);
  font-family: var(--font-mono);
}
.skill-empty {
  font-size: var(--fs-12);
  color: var(--txt3);
  padding: 14px 4px;
  border: 1px dashed var(--line);
  border-radius: 11px;
  text-align: center;
}

/* table */
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-12);
}
.tbl th {
  text-align: left;
  font-weight: 600;
  color: var(--txt3);
  font-size: var(--fs-11);
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.tbl td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--txt2);
}
.tbl tr:last-child td { border-bottom: none; }
.tbl td b { color: var(--txt); font-weight: 600; }
.tbl-empty {
  text-align: center;
  color: var(--txt3);
  font-size: var(--fs-12);
  padding: 18px 10px !important;
}

/* kpi */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.kpi {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 13px 15px;
  box-shadow: var(--shadow);
}
.kpi__l { font-size: var(--fs-12); color: var(--txt3); }
.kpi__v {
  font-size: 21px;
  font-weight: 680;
  letter-spacing: -0.3px;
  margin: 3px 0;
  color: var(--txt);
}
.kpi__d { font-size: var(--fs-11); }
.kpi__d--mute { color: var(--txt3); font-style: italic; }
.bar {
  height: 5px;
  border-radius: 3px;
  background: var(--line-soft);
  overflow: hidden;
  margin-top: 8px;
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--primary);
}
.overview-foot {
  margin-top: 12px;
  font-size: var(--fs-12);
  color: var(--txt2);
}
.overview-foot b { color: var(--txt); font-weight: 600; }

/* placeholder tab */
.tab-placeholder {
  background: #fff;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  padding: 64px 24px;
  text-align: center;
}
.tab-placeholder__icon {
  font-size: 28px;
  color: var(--txt3);
  margin-bottom: 12px;
}
.tab-placeholder__title {
  font-size: var(--fs-15);
  font-weight: 650;
  color: var(--txt);
}
.tab-placeholder__sub {
  margin-top: 6px;
  color: var(--txt3);
  font-size: var(--fs-13);
}
</style>
