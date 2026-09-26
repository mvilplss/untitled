<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DwsAuthPanel from '@/components/dws/DwsAuthPanel.vue'
import DwsProfilePicker from '@/components/dws/DwsProfilePicker.vue'
import { useDwsStore } from '@/stores/dws'
import UButton from '@/components/ui/UButton.vue'
import { ElMessage } from 'element-plus'
import * as dwsApi from '@/api/dws'

const store = useDwsStore()

const auditContent = ref<string>('')
const auditLoading = ref(false)

onMounted(async () => {
  await store.refreshStatus()
  await store.loadProfiles()
  await store.loadRegistry()
  await loadAudit()
})

async function loadAudit() {
  auditLoading.value = true
  try {
    const r = await dwsApi.getAudit(30)
    auditContent.value = r.content
  } catch (e) {
    auditContent.value = '（加载失败）'
  } finally {
    auditLoading.value = false
  }
}

function fmtAudit() {
  if (!auditContent.value) return ''
  return auditContent.value
}
</script>

<template>
  <div class="dws-ws">
    <header class="dws-ws__head">
      <div>
        <div class="dws-ws__h1">钉钉 DWS 工作区</div>
        <div class="dws-ws__sub">
          后端在服务器上集成
          <a href="https://github.com/DingTalk-Real-AI/dingtalk-workspace-cli" target="_blank" rel="noopener">
            dingtalk-workspace-cli (dws)
          </a>。
          每个本地账号独立授权、独立目录。
          授权完成后，Agent 会通过
          <code>dingtalk-dws</code> skill 在 Bash 工具里调用服务器端 dws，
          你只需在对话中直接说「查今天日程」「建待办」等即可。
        </div>
      </div>
      <div v-if="store.userId" class="dws-ws__chip">
        当前账号：<code>{{ store.userId }}</code>
      </div>
    </header>

    <DwsAuthPanel />

    <DwsProfilePicker />

    <section class="dws-ws__audit">
      <header class="dws-ws__audit-head">
        <div class="dws-ws__audit-title">审计日志（已脱敏）</div>
        <UButton variant="ghost" :disabled="auditLoading" @click="loadAudit">
          {{ auditLoading ? '加载中…' : '刷新' }}
        </UButton>
      </header>
      <pre class="dws-ws__audit-pre">{{ fmtAudit() || '（暂无记录）' }}</pre>
    </section>
  </div>
</template>

<style scoped>
.dws-ws {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.dws-ws__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 4px;
}
.dws-ws__h1 {
  font-size: var(--fs-19);
  font-weight: 650;
  color: var(--txt);
}
.dws-ws__sub {
  font-size: var(--fs-12);
  color: var(--txt2);
  margin-top: 3px;
  max-width: 760px;
  line-height: 1.5;
}
.dws-ws__sub a {
  color: var(--primary);
  text-decoration: none;
}
.dws-ws__sub a:hover { text-decoration: underline; }
.dws-ws__sub code {
  background: rgba(0,0,0,0.05);
  padding: 1px 5px;
  border-radius: 3px;
  font-family: var(--font-mono);
}

.dws-ws__chip {
  font-size: var(--fs-12);
  color: var(--txt2);
  padding: 6px 10px;
  background: #fafbfc;
  border: 1px solid var(--line);
  border-radius: 8px;
  white-space: nowrap;
}
.dws-ws__chip code {
  background: rgba(0,0,0,0.05);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  margin-left: 4px;
}

.dws-ws__audit {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px;
}
.dws-ws__audit-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.dws-ws__audit-title {
  font-size: var(--fs-14);
  font-weight: 650;
  color: var(--txt);
}
.dws-ws__audit-pre {
  margin: 0;
  padding: 12px;
  background: #fafbfc;
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  line-height: 1.55;
  max-height: 320px;
  overflow: auto;
  color: var(--txt);
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 6px;
}
</style>
