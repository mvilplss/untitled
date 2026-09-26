<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getBailianRag,
  upsertBailianRag,
  deleteBailianRag,
} from '@/api/agents'
import type { BailianRagConfig } from '@/types/api'

const props = defineProps<{
  agentId: string
  isEdit: boolean
}>()

const emit = defineEmits<{
  'change': [cfg: BailianRagConfig]
}>()

const MASK = '***'

/** 客户端占位符检测（与服务端 BailianRagService.looksLikePlaceholder 对齐） */
function looksLikePlaceholder(value: string | undefined): boolean {
  if (!value) return false
  const v = value.trim()
  if (!v) return false
  const low = v.toLowerCase()
  if (
    low.includes('replace') ||
    low.includes('your_') ||
    low.includes('your-') ||
    low.includes('xxxx') ||
    low.includes('<') ||
    low.includes('todo') ||
    low.includes('placeholder') ||
    low.includes('sample') ||
    low.includes('dummy') ||
    low.includes('fake')
  ) return true
  // 单一字符重复（>=6 字符，如 'aaaaaa'）
  const first = v.charAt(0)
  let allSame = true
  for (let i = 1; i < v.length; i++) {
    if (v.charAt(i) !== first) { allSame = false; break }
  }
  return allSame && v.length >= 6
}

function emptyRag(): BailianRagConfig {
  return {
    enabled: false,
    accessKeyId: '',
    accessKeySecret: '',
    workspaceId: '',
    indexId: '',
    endpoint: '',
    limit: 5,
    scoreThreshold: 0.3,
    enableRerank: false,
    rerankModel: '',
    rerankMinScore: 0.3,
    rerankTopN: 5,
    enableRewrite: false,
    rewriteModel: '',
  }
}

const rag = ref<BailianRagConfig>(emptyRag())
const showSecret = ref(false)
const ragSaving = ref(false)
const ragLoading = ref(false)
const showAdvanced = ref(false)

function reset() {
  rag.value = emptyRag()
  showSecret.value = false
  showAdvanced.value = false
}

async function load(agentId: string) {
  if (!agentId) return
  ragLoading.value = true
  try {
    const cfg = await getBailianRag(agentId)
    rag.value = {
      enabled: cfg.enabled,
      accessKeyId: cfg.accessKeyId ?? '',
      accessKeySecret: cfg.accessKeySecret ?? '',
      workspaceId: cfg.workspaceId ?? '',
      indexId: cfg.indexId ?? '',
      endpoint: cfg.endpoint ?? '',
      limit: cfg.limit ?? 5,
      scoreThreshold: cfg.scoreThreshold ?? 0.3,
      enableRerank: cfg.enableRerank ?? false,
      rerankModel: cfg.rerankModel ?? '',
      rerankMinScore: cfg.rerankMinScore ?? 0.3,
      rerankTopN: cfg.rerankTopN ?? 5,
      enableRewrite: cfg.enableRewrite ?? false,
      rewriteModel: cfg.rewriteModel ?? '',
    }
  } catch {
    reset()
  } finally {
    ragLoading.value = false
  }
}

const secretMasked = computed(() => rag.value.accessKeySecret === MASK)
const hasRequiredFields = computed(() =>
  !!rag.value.accessKeyId?.trim()
  && !!rag.value.accessKeySecret?.trim()
  && !!rag.value.workspaceId?.trim()
  && !!rag.value.indexId?.trim()
)
const canEnable = computed(() => !rag.value.enabled || hasRequiredFields.value)

const dirty = computed(() => {
  if (!props.isEdit) return rag.value.enabled
  if (rag.value.enabled) return true
  return hasRequiredFields.value
})

const statusMeta = computed(() => {
  if (ragLoading.value) return { text: '加载中…', tone: 'mute' as const }
  if (!rag.value.enabled) return { text: '○ 未启用', tone: 'mute' as const }
  if (!hasRequiredFields.value) return { text: '● 配置不完整', tone: 'err' as const }
  return { text: '● 已启用', tone: 'ok' as const }
})

async function save() {
  if (!props.agentId) return
  if (rag.value.enabled && !hasRequiredFields.value) {
    ElMessage.error('启用 RAG 需填写 AccessKeyId / AccessKeySecret / WorkspaceId / IndexId')
    return
  }
  // 客户端预检：明显的占位符在保存前提示，避免发起必败的百炼请求
  if (rag.value.enabled && rag.value.accessKeySecret && rag.value.accessKeySecret !== MASK) {
    const sk = rag.value.accessKeySecret.trim()
    if (looksLikePlaceholder(sk)) {
      ElMessage.error('AccessKeySecret 看起来是占位符（如 REPLACE_ME / xxxx / your-key），请填入真实密钥')
      return
    }
  }
  if (rag.value.enabled && rag.value.accessKeyId) {
    const ak = rag.value.accessKeyId.trim()
    if (looksLikePlaceholder(ak)) {
      ElMessage.error('AccessKeyId 看起来是占位符，请填入阿里云控制台真实 AccessKey ID')
      return
    }
  }
  ragSaving.value = true
  try {
    const cfg: BailianRagConfig = {
      enabled: rag.value.enabled,
      accessKeyId: rag.value.accessKeyId?.trim() || undefined,
      accessKeySecret: secretMasked.value ? MASK : (rag.value.accessKeySecret?.trim() || undefined),
      workspaceId: rag.value.workspaceId?.trim() || undefined,
      indexId: rag.value.indexId?.trim() || undefined,
      endpoint: rag.value.endpoint?.trim() || undefined,
      limit: rag.value.limit,
      scoreThreshold: rag.value.scoreThreshold,
      enableRerank: rag.value.enableRerank,
      rerankModel: rag.value.rerankModel?.trim() || undefined,
      rerankMinScore: rag.value.enableRerank ? rag.value.rerankMinScore : undefined,
      rerankTopN: rag.value.enableRerank ? rag.value.rerankTopN : undefined,
      enableRewrite: rag.value.enableRewrite,
      rewriteModel: rag.value.enableRewrite ? (rag.value.rewriteModel?.trim() || undefined) : undefined,
    }
    const result = await upsertBailianRag(props.agentId, cfg)
    ElMessage.success(rag.value.enabled ? '百炼 RAG 已启用' : '配置已保存')
    rag.value = {
      enabled: result.enabled,
      accessKeyId: result.accessKeyId ?? '',
      accessKeySecret: result.accessKeySecret ?? '',
      workspaceId: result.workspaceId ?? '',
      indexId: result.indexId ?? '',
      endpoint: result.endpoint ?? '',
      limit: result.limit ?? 5,
      scoreThreshold: result.scoreThreshold ?? 0.3,
      enableRerank: result.enableRerank ?? false,
      rerankModel: result.rerankModel ?? '',
      rerankMinScore: result.rerankMinScore ?? 0.3,
      rerankTopN: result.rerankTopN ?? 5,
      enableRewrite: result.enableRewrite ?? false,
      rewriteModel: result.rewriteModel ?? '',
    }
    emit('change', rag.value)
  } catch {
    /* axios interceptor 已 ElMessage */
  } finally {
    ragSaving.value = false
  }
}

async function disable() {
  if (!props.agentId) return
  try {
    await deleteBailianRag(props.agentId)
    ElMessage.success('百炼 RAG 已停用')
    reset()
    emit('change', rag.value)
  } catch {
    /* */
  }
}

function onToggleEnabled(v: string | number | boolean) {
  rag.value.enabled = Boolean(v)
  emit('change', rag.value)
}

function getRagSnapshot(): BailianRagConfig {
  return rag.value
}

defineExpose({ load, reset, getRagSnapshot })
</script>

<template>
  <section>
    <header class="brs-head">
      <span class="brs-head__title mono">// 百炼知识库 (RAG)</span>
      <span class="brs-head__meta mono">
        <span
          :class="`brs-status brs-status--${statusMeta.tone}`"
        >{{ statusMeta.text }}</span>
      </span>
    </header>

    <div class="brs-hint mono">
      为该数字人接入阿里云百炼知识库。启用后会把 retrieve_knowledge 工具注入 Toolkit，让 Agent 自主决定何时检索。
      未填写的 AK / Secret / Endpoint 会回退到 <code>application.yml</code> 全局默认。
      建议在系统提示词中提示 Agent 使用检索工具回答知识类问题。
    </div>

    <details class="brs-howto mono">
      <summary>如何获取 workspaceId / endpoint / AccessKey？</summary>
      <ol>
        <li>
          <b>workspaceId</b>：百炼控制台 → 顶部左侧「业务空间」切换器，或
          <b>业务空间管理</b> 页面里的「业务空间 ID」列（形如
          <code>llm-m1f291rtxvur98yz</code> 或 <code>ws-xxxx</code>）。
          它与阿里云主账号 ID、AccessKey ID 都不同。
        </li>
        <li>
          <b>endpoint</b>：同一个「业务空间管理」列表里的「API Host」列
          （形如 <code>llm-m1f291rtxvur98yz.cn-beijing.m...aliyuncs.com</code>）。
          <b>专属 workspace 必须填</b>；默认业务空间可留空走全局默认
          <code>bailian.cn-beijing.aliyuncs.com</code>。
        </li>
        <li>
          <b>accessKeyId / Secret</b>：阿里云主账号 → 右上角头像 → AccessKey 管理。
          <b>Secret 只在创建时显示一次</b>，务必当场复制保存。推荐用 RAM 子账号 + 最小权限。
        </li>
        <li>
          <b>indexId</b>：数据接入 → 知识管理 → 列表里的「ID」字段（形如
          <code>qbec2poy49</code>），与 workspaceId 是一对多关系。
        </li>
      </ol>
    </details>

    <div class="brs-grid">
      <div class="brs-field brs-field--full">
        <label class="brs-field__label mono">启用百炼 RAG</label>
        <el-switch
          :model-value="rag.enabled"
          :disabled="!canEnable && !rag.enabled"
          @update:model-value="onToggleEnabled"
        />
      </div>

      <div class="brs-field">
        <label class="brs-field__label mono">AccessKeyId</label>
        <el-input
          v-model="rag.accessKeyId"
          placeholder="阿里云访问密钥 ID，留空走全局"
          :disabled="!rag.enabled"
          maxlength="64"
        />
      </div>

      <div class="brs-field">
        <label class="brs-field__label mono">AccessKeySecret</label>
        <el-input
          v-model="rag.accessKeySecret"
          :type="showSecret ? 'text' : 'password'"
          placeholder="阿里云访问密钥 Secret，留空走全局"
          :disabled="!rag.enabled"
          maxlength="128"
        >
          <template #append>
            <button
              class="brs-eye mono"
              type="button"
              @click="showSecret = !showSecret"
            >
              {{ showSecret ? '隐藏' : '显示' }}
            </button>
          </template>
        </el-input>
        <div v-if="isEdit && secretMasked" class="brs-field__hint mono">
          已保存当前密钥；如需修改请直接输入新值，否则提交时沿用旧值
        </div>
      </div>

      <div class="brs-field">
        <label class="brs-field__label mono">WorkspaceId</label>
        <el-input
          v-model="rag.workspaceId"
          placeholder="百炼工作空间 ID（必填）"
          :disabled="!rag.enabled"
          maxlength="64"
        />
      </div>

      <div class="brs-field">
        <label class="brs-field__label mono">IndexId</label>
        <el-input
          v-model="rag.indexId"
          placeholder="百炼知识库索引 ID（必填）"
          :disabled="!rag.enabled"
          maxlength="64"
        />
      </div>

      <div class="brs-field brs-field--full">
        <button
          class="brs-advanced-toggle mono"
          type="button"
          :disabled="!rag.enabled"
          @click="showAdvanced = !showAdvanced"
        >
          {{ showAdvanced ? '收起高级 ▴' : '展开高级 ▾' }}
        </button>
      </div>

      <template v-if="showAdvanced">
        <div class="brs-field brs-field--full">
          <label class="brs-field__label mono">Endpoint</label>
          <el-input
            v-model="rag.endpoint"
            placeholder="留空走百炼默认（一般不用填）"
            :disabled="!rag.enabled"
            maxlength="256"
          />
        </div>

        <div class="brs-field">
          <label class="brs-field__label mono">检索 TopK</label>
          <el-input-number
            v-model="rag.limit"
            :min="1"
            :max="50"
            :disabled="!rag.enabled"
            controls-position="right"
          />
        </div>

        <div class="brs-field">
          <label class="brs-field__label mono">相似度阈值</label>
          <el-input-number
            v-model="rag.scoreThreshold"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            :disabled="!rag.enabled"
            controls-position="right"
          />
        </div>

        <div class="brs-field brs-field--full">
          <div class="brs-advanced-row">
            <el-checkbox v-model="rag.enableRerank" :disabled="!rag.enabled">
              启用重排序（提高精度，延迟↑）
            </el-checkbox>
            <el-checkbox v-model="rag.enableRewrite" :disabled="!rag.enabled">
              启用查询改写（多轮对话，延迟↑）
            </el-checkbox>
          </div>
        </div>

        <template v-if="rag.enableRerank">
          <div class="brs-field">
            <label class="brs-field__label mono">Rerank 模型</label>
            <el-input
              v-model="rag.rerankModel"
              placeholder="留空走百炼默认（如 gte-rerank-hybrid）"
              :disabled="!rag.enabled"
              maxlength="128"
            />
          </div>
          <div class="brs-field">
            <label class="brs-field__label mono">Rerank 最小分</label>
            <el-input-number
              v-model="rag.rerankMinScore"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              :disabled="!rag.enabled"
              controls-position="right"
            />
          </div>
          <div class="brs-field">
            <label class="brs-field__label mono">Rerank TopN</label>
            <el-input-number
              v-model="rag.rerankTopN"
              :min="1"
              :max="50"
              :disabled="!rag.enabled"
              controls-position="right"
            />
          </div>
        </template>

        <template v-if="rag.enableRewrite">
          <div class="brs-field brs-field--full">
            <label class="brs-field__label mono">Query Rewrite 模型</label>
            <el-input
              v-model="rag.rewriteModel"
              placeholder="留空走百炼默认（如 conv-rewrite-qwen-1.8b）"
              :disabled="!rag.enabled"
              maxlength="128"
            />
          </div>
        </template>
      </template>
    </div>

    <footer v-if="isEdit" class="brs-foot">
      <button
        v-if="rag.enabled"
        class="btn-ghost mono"
        type="button"
        @click="disable"
      >
        停用 RAG
      </button>
      <span class="brs-foot-spacer"></span>
      <button
        class="btn-primary"
        type="button"
        :disabled="ragSaving || !dirty || (rag.enabled && !hasRequiredFields)"
        @click="save"
      >
        {{ ragSaving ? '保存中' : (rag.enabled ? '保存' : '启用并启动') }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.brs-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.brs-head__title {
  font-size: 13px;
  color: var(--text);
  font-weight: 600;
}
.brs-head__meta {
  font-size: 11px;
  color: var(--text-mute);
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.brs-status--ok { color: var(--ok); }
.brs-status--warn { color: var(--warn); }
.brs-status--err { color: var(--err); }
.brs-status--mute { color: var(--text-faint); }

.brs-hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-bottom: 12px;
}
.brs-hint code {
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--bg-surface);
  border: 1px solid var(--border-soft);
  padding: 1px 5px;
  border-radius: 3px;
}

.brs-howto {
  font-size: 11px;
  color: var(--text-mute);
  line-height: 1.55;
  margin-bottom: 12px;
  border: 1px dashed var(--border-soft);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  background: var(--bg-surface);
}
.brs-howto summary {
  cursor: pointer;
  color: var(--text);
  font-weight: 600;
  font-size: 12px;
  user-select: none;
}
.brs-howto summary:hover {
  color: var(--primary);
}
.brs-howto ol {
  margin: 8px 0 0 0;
  padding-left: 20px;
}
.brs-howto li {
  margin-bottom: 6px;
}
.brs-howto code {
  font-family: var(--font-mono);
  font-size: 10.5px;
  background: var(--bg);
  border: 1px solid var(--border-soft);
  padding: 1px 5px;
  border-radius: 3px;
}
.brs-howto b {
  color: var(--text);
  font-weight: 600;
}

.brs-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 24px;
  row-gap: 14px;
  padding: 12px;
  border: 1px solid var(--border);
  background: var(--bg-surface);
}
.brs-field { display: flex; flex-direction: column; gap: 6px; }
.brs-field--full { grid-column: 1 / -1; }
.brs-field__label { font-size: 11px; color: var(--text-mute); }
.brs-field__hint {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.brs-advanced-toggle {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: 1px dashed var(--border);
  padding: 6px 12px;
  cursor: pointer;
  align-self: flex-start;
}
.brs-advanced-toggle:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--text-mute);
}
.brs-advanced-toggle:disabled { opacity: 0.5; cursor: not-allowed; }

.brs-advanced-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.brs-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.brs-foot-spacer { flex: 1; }

.brs-eye {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: 1px solid var(--border);
  padding: 4px 10px;
  cursor: pointer;
}
.brs-eye:hover { color: var(--text); border-color: var(--text-mute); }
</style>