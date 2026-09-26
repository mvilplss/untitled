<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'
import {
  buildSandboxRawUrl,
  getSandboxStatus,
  listSandboxFiles,
  readSandboxFileContent,
  wakeSandbox,
} from '@/api/sandboxFiles'
import { classify, extOf, iconFor } from '@/utils/fileKind'
import type {
  SandboxFileContentResponse,
  SandboxFileEntry,
  SandboxStatusResponse,
} from '@/types/api'

interface Props {
  modelValue: boolean
  agentId: string
  userId: string
  sessionId?: string
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v),
})

// ============================================================
// 状态
// ============================================================
const treeRef = ref<any>()
const status = ref<SandboxStatusResponse | null>(null)
const statusLoading = ref(false)
const waking = ref(false)

const selectedPath = ref<string>('')        // 当前选中的文件 / 目录
const selectedEntry = ref<SandboxFileEntry | null>(null)

const previewLoading = ref(false)
const previewText = ref<string>('')
const previewEncoding = ref<string>('')
const previewSize = ref<number>(0)
const previewTruncated = ref(false)
const previewTooLarge = ref(false)
const previewError = ref<string>('')

// iframe（HTML / SVG 预览）状态
const iframeLoading = ref(false)
const iframeError = ref('')

function onIframeLoad() { iframeLoading.value = false }
function onIframeError() {
  iframeLoading.value = false
  iframeError.value = '预览加载失败（sandbox 可能拦截了外部资源）'
}

const defaultProps = { children: 'children', label: 'name', isLeaf: 'isLeaf' }

const rootPath = computed(() => status.value?.workspaceRoot || '/workspace')

// ============================================================
// 生命周期：打开 → 探测状态 → 装入根
// ============================================================
async function refreshStatus() {
  if (!props.agentId || !props.userId) return
  statusLoading.value = true
  try {
    status.value = await getSandboxStatus(props.agentId, props.userId)
  } catch {
    status.value = null
  } finally {
    statusLoading.value = false
  }
}

async function onWake() {
  if (waking.value) return
  waking.value = true
  try {
    status.value = await wakeSandbox(props.agentId, props.userId, props.sessionId)
    // 唤醒成功后重置树并重新装入根
    await nextTick()
    reloadTree()
  } catch {
    // 错误已由 axios 拦截器弹出
  } finally {
    waking.value = false
  }
}

function reloadTree() {
  selectedPath.value = ''
  selectedEntry.value = null
  clearPreview()
  if (!treeRef.value) return
  const root = { name: rootPath.value, path: rootPath.value, isDirectory: true, isLeaf: false }
  // 幂等：根已存在（destroy-on-close 失效 / HMR 边缘 / 同轮多触发）时不重复 append，
  // 否则会因 node-key 冲突导致 nodesMap 覆盖 + 树渲染异常。
  if (treeRef.value.getNode(rootPath.value)) {
    treeRef.value.updateKeyChildren(rootPath.value, [])
  } else {
    treeRef.value.append(root)
  }
}

watch(
  () => props.modelValue,
  v => {
    if (v) {
      refreshStatus().then(() => {
        reloadTree()
      })
    }
  },
)

onMounted(() => {
  if (props.modelValue) {
    refreshStatus().then(() => reloadTree())
  }
})

// ============================================================
// 树懒加载
// ============================================================
async function loadNode(node: any, resolve: (entries: SandboxFileEntry[]) => void) {
  // Element Plus 在 lazy 初始化时会对「合成根节点」(level 0、data 无 path) 调一次 load。
  // 必须 resolve([])，否则 /workspace 的子项会被挂到树顶层，与手动 append 的根节点重复。
  const path = node?.data?.path
  if (typeof path !== 'string' || !path) {
    resolve([])
    return
  }
  try {
    const resp = await listSandboxFiles(props.agentId, props.userId, path, props.sessionId)
    const entries = resp.entries.map(e => ({ ...e, isLeaf: !e.isDirectory }))
    resolve(entries)
  } catch (e: any) {
    // 容器未运行：节点作为空目录展示；上游 status bar 已经显示唤醒条
    resolve([])
  }
}

async function onNodeClick(data: SandboxFileEntry) {
  // Element Plus el-tree 的 node-click 事件签名是 (data, node, nodeInstance, event)：
  // 第一参数就是节点数据本身（不是 Node 实例）。详见 tree.mjs:62 emit 校验。
  if (!data) return
  selectedPath.value = data.path
  selectedEntry.value = data
  if (data.isDirectory) return
  loadPreview(data)
}

// ============================================================
// 预览加载
// ============================================================
async function loadPreview(entry: SandboxFileEntry) {
  clearPreview()
  previewLoading.value = true
  const info = classify(entry.name)
  try {
    if (info.kind === 'image') {
      // 图片直接走 raw 端点，不再额外请求 content
      previewEncoding.value = 'image'
      return
    }
    if (info.useFrame) {
      // HTML / SVG 走 iframe sandbox 渲染，无需读内容
      previewEncoding.value = 'iframe'
      return
    }
    const resp = await readSandboxFileContent(props.agentId, props.userId, entry.path, {
      sessionId: props.sessionId,
      offset: 0,
      limit: 2000,
    })
    previewEncoding.value = resp.encoding
    previewSize.value = resp.size
    previewTruncated.value = resp.truncated
    previewTooLarge.value = resp.tooLarge
    previewText.value = resp.content
  } catch (e: any) {
    previewError.value = e?.message || '读取失败'
  } finally {
    previewLoading.value = false
  }
}

function clearPreview() {
  previewLoading.value = false
  previewText.value = ''
  previewEncoding.value = ''
  previewSize.value = 0
  previewTruncated.value = false
  previewTooLarge.value = false
  previewError.value = ''
  iframeLoading.value = true
  iframeError.value = ''
}

// ============================================================
// 预览渲染
// ============================================================
const renderedMarkdown = computed(() => {
  if (previewEncoding.value !== 'utf-8') return ''
  if (!selectedEntry.value) return ''
  if (classify(selectedEntry.value.name).kind !== 'markdown') return ''
  marked.setOptions({ breaks: true, gfm: true })
  return marked.parse(previewText.value || '') as string
})

const renderedCode = computed(() => {
  if (previewEncoding.value !== 'utf-8') return ''
  if (!selectedEntry.value) return ''
  if (classify(selectedEntry.value.name).kind !== 'code') return ''
  try {
    return hljs.highlightAuto(previewText.value || '').value
  } catch {
    return escapeHtml(previewText.value || '')
  }
})

const languageHint = computed(() => {
  if (!selectedEntry.value) return ''
  const lang = hljs.getLanguage(extOf(selectedEntry.value.name).slice(1))
  return lang?.name || 'text'
})

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const rawUrl = computed(() => {
  if (!selectedEntry.value || selectedEntry.value.isDirectory) return ''
  return buildSandboxRawUrl(props.agentId, props.userId, selectedEntry.value.path, {
    sessionId: props.sessionId,
    download: false,
  })
})

const downloadUrl = computed(() => {
  if (!selectedEntry.value || selectedEntry.value.isDirectory) return ''
  return buildSandboxRawUrl(props.agentId, props.userId, selectedEntry.value.path, {
    sessionId: props.sessionId,
    download: true,
  })
})

/** 哪些类型适合"新窗口打开预览"——iframe 受 sandbox 限制时，用户可换浏览器全宽 */
const canOpenRaw = computed(() => {
  if (!selectedEntry.value || selectedEntry.value.isDirectory) return false
  return classify(selectedEntry.value.name).useFrame
})

function fmtSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let n = bytes
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${units[i]}`
}

function shortPath(p: string) {
  if (!p) return ''
  if (p.length <= 64) return p
  return '…' + p.slice(p.length - 61)
}
</script>

<template>
  <el-drawer
    v-model="open"
    direction="rtl"
    size="60%"
    :with-header="false"
    destroy-on-close
  >
    <div class="sfb">
      <!-- 顶部状态栏 -->
      <header class="sfb__bar">
        <div class="sfb__bar-left">
          <span class="sfb__title">沙箱文件浏览</span>
          <span class="sfb__path mono">{{ rootPath }}</span>
        </div>
        <div class="sfb__bar-right">
          <span
            class="sfb__status"
            :class="status?.running ? 'sfb__status--ok' : 'sfb__status--off'"
            v-loading="statusLoading"
          >
            <span class="sfb__status-dot"></span>
            {{ status?.running ? '运行中' : '未启动' }}
          </span>
          <el-button
            v-if="!status?.running"
            size="small"
            type="primary"
            :loading="waking"
            @click="onWake"
          >
            启动沙箱
          </el-button>
          <el-button size="small" :loading="statusLoading" @click="refreshStatus">刷新</el-button>
          <el-button text size="small" @click="open = false">关闭</el-button>
        </div>
      </header>

      <div v-if="waking" class="sfb__wake-hint mono">
        // 首次启动约 5-30 秒（构建容器 + 投影宿主 workspace）…
      </div>

      <!-- 主体：左树 + 右预览 -->
      <div class="sfb__body">
        <aside class="sfb__tree">
          <el-tree
            ref="treeRef"
            lazy
            :load="loadNode"
            :props="defaultProps"
            node-key="path"
            :default-expanded-keys="[rootPath]"
            expand-on-click-node
            empty-text="// 无可访问内容"
            @node-click="onNodeClick"
          >
            <template #default="{ node, data }">
              <span class="sfb__node">
                <span class="sfb__node-icon">{{ iconFor(data.name, !!data.isDirectory, node.expanded) }}</span>
                <span class="sfb__node-name mono">{{ data.name }}</span>
              </span>
            </template>
          </el-tree>
        </aside>

        <section class="sfb__preview">
          <div v-if="!selectedEntry" class="sfb__placeholder mono">
            // 从左侧选择一个文件
          </div>
          <div v-else-if="selectedEntry.isDirectory" class="sfb__placeholder mono">
            // 目录 · {{ shortPath(selectedEntry.path) }}
          </div>
          <div v-else v-loading="previewLoading" class="sfb__preview-body">
            <header class="sfb__preview-head">
              <div class="sfb__preview-title">{{ selectedEntry.name }}</div>
              <div class="sfb__preview-meta mono">
                <span>{{ languageHint }}</span>
                <span class="sfb__dot">·</span>
                <span>{{ fmtSize(selectedEntry.size || previewSize) }}</span>
                <span class="sfb__dot">·</span>
                <a
                  v-if="canOpenRaw"
                  :href="rawUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="sfb__open-new mono"
                  title="在新窗口打开预览（浏览器全宽）"
                  >↗ 新窗口</a
                >
                <a
                  :href="downloadUrl"
                  :download="selectedEntry.name"
                  class="sfb__dl"
                  >下载</a
                >
              </div>
            </header>

            <el-alert
              v-if="previewError"
              type="error"
              :title="previewError"
              :closable="false"
              show-icon
            />

            <el-alert
              v-else-if="previewTooLarge"
              type="warning"
              title="文件超过 2MB 预览阈值，请使用上方下载按钮查看完整内容"
              :closable="false"
              show-icon
            />

            <div v-else-if="classify(selectedEntry.name).kind === 'image'" class="sfb__image">
              <el-image
                :src="rawUrl"
                :preview-src-list="[rawUrl]"
                :fit="'contain'"
                style="max-width: 100%"
              />
            </div>

            <div
              v-else-if="classify(selectedEntry.name).kind === 'markdown'"
              class="sfb__md"
              v-html="renderedMarkdown"
            ></div>

            <div
              v-else-if="classify(selectedEntry.name).useFrame"
              class="sfb__html"
              :aria-busy="iframeLoading"
            >
              <div v-if="iframeLoading" class="sfb__html-loading mono">// 加载中...</div>
              <iframe
                v-show="!iframeLoading && !iframeError"
                :src="rawUrl"
                class="sfb__html-frame"
                sandbox=""
                referrerpolicy="no-referrer"
                :title="`预览 ${selectedEntry.name}`"
                @load="onIframeLoad"
                @error="onIframeError"
              ></iframe>
              <el-alert
                v-if="iframeError"
                type="warning"
                :title="iframeError"
                :closable="false"
                show-icon
              />
            </div>

            <pre v-else class="sfb__code"><code v-html="renderedCode"></code></pre>

            <div v-if="previewTruncated" class="sfb__truncated mono">
              // 已截断至 2000 行 — 完整内容请下载查看
            </div>
          </div>
        </section>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.sfb {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg);
}
.sfb__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
  gap: 16px;
}
.sfb__bar-left,
.sfb__bar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sfb__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--txt);
}
.sfb__path {
  font-size: 11px;
  color: var(--txt3);
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--bg);
}
.sfb__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--txt2);
  padding: 2px 10px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--bg);
}
.sfb__status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--txt3);
}
.sfb__status--ok {
  color: var(--green);
  border-color: var(--green-soft);
  background: var(--green-soft);
}
.sfb__status--ok .sfb__status-dot {
  background: var(--green);
}
.sfb__status--off {
  color: var(--txt3);
}
.sfb__wake-hint {
  padding: 6px 20px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 11px;
  border-bottom: 1px solid var(--line);
}
.sfb__body {
  display: grid;
  grid-template-columns: 320px 1fr;
  grid-template-rows: 1fr;
  flex: 1 1 0;
  min-height: 0;
  background: var(--bg);
}
.sfb__tree {
  border-right: 1px solid var(--line);
  background: var(--panel);
  overflow: auto;
  padding: 8px 8px 16px;
}
.sfb__tree :deep(.el-tree-node__content) {
  height: 28px;
  font-size: 12px;
}
.sfb__node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 28px;
}
.sfb__node-icon {
  font-size: 13px;
  filter: grayscale(0.2);
  flex-shrink: 0;
  width: 18px;
  text-align: center;
}
.sfb__node-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sfb__preview {
  overflow: auto;
  padding: 16px 20px;
  height: 100%;
  min-height: 0;
}
.sfb__placeholder {
  color: var(--txt3);
  font-size: 12px;
  padding: 20px 0;
}
.sfb__preview-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.sfb__preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line);
}
.sfb__preview-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--txt);
  font-family: var(--font-mono);
}
.sfb__preview-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--txt3);
}
.sfb__dot {
  color: var(--line);
}
.sfb__dl {
  color: var(--primary);
  text-decoration: none;
}
.sfb__dl:hover {
  text-decoration: underline;
}
.sfb__open-new {
  color: var(--primary);
  text-decoration: none;
  padding: 1px 6px;
  border: 1px solid var(--primary-soft);
  border-radius: 4px;
  font-size: 11px;
}
.sfb__open-new:hover {
  background: var(--primary-soft);
}
.sfb__image {
  display: flex;
  justify-content: center;
  padding: 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.sfb__md {
  padding: 12px 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  font-size: 13px;
  line-height: 1.6;
}
.sfb__md :deep(pre) {
  background: var(--bg);
  padding: 8px 12px;
  border-radius: 4px;
  overflow-x: auto;
}
.sfb__md :deep(code) {
  font-family: var(--font-mono);
  background: var(--bg);
  padding: 1px 4px;
  border-radius: 3px;
}
.sfb__code {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.55;
  overflow-x: auto;
  margin: 0;
}
.sfb__code code {
  background: transparent;
  padding: 0;
  font-family: inherit;
}
.sfb__truncated {
  color: var(--txt3);
  font-size: 11px;
  text-align: center;
  padding: 6px 0;
  border-top: 1px dashed var(--line);
}

/* HTML / SVG iframe 预览 */
.sfb__html {
  flex: 1 1 0;
  min-height: 320px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: #fff;
}
.sfb__html-frame {
  flex: 1 1 0;
  width: 100%;
  border: 0;
  background: #fff;
}
.sfb__html-loading {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--txt3);
  border-bottom: 1px solid var(--line-soft);
}
</style>
