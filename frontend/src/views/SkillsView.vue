<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSkillsStore } from '@/stores/skills'
import type { SkillInfo } from '@/types/api'

const store = useSkillsStore()

type UploadMode = 'zip' | 'folder' | 'markdown'
const uploadMode = ref<UploadMode>('zip')
const markdownInput = ref('')
const uploading = ref(false)
const drawerVisible = ref(false)
const drawerLoading = ref(false)
const drawerSkill = ref<SkillInfo | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
const folderInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

const canSubmit = computed(() => {
  if (uploadMode.value === 'markdown') return markdownInput.value.trim().length > 0
  return false
})

onMounted(() => store.fetchList())

function validateAndSubmit(file: File) {
  const name = file.name.toLowerCase()
  if (!name.endsWith('.zip') && !name.endsWith('.skill')) {
    ElMessage.warning('仅支持 .zip 或 .skill 压缩包')
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.warning('文件超过 50MB，请先压缩')
    return
  }
  uploading.value = true
  store.uploadZip(file)
    .then(created => ElMessage.success(`已上传：${created.name}`))
    .catch((e: any) => ElMessage.error(e?.response?.data?.error || e?.message || '上传失败'))
    .finally(() => {
      uploading.value = false
      if (fileInput.value) fileInput.value.value = ''
    })
}

function onFilePicked(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (file) validateAndSubmit(file)
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) validateAndSubmit(file)
}

function onDragEnter(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
}

function onDragLeave(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
}

function openPicker() { fileInput.value?.click() }
function openFolderPicker() { folderInput.value?.click() }

function onFolderPicked(e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files || files.length === 0) return
  submitFolder(Array.from(files))
}

function submitFolder(files: File[]) {
  uploading.value = true
  store.uploadFolder(files)
    .then(created => ElMessage.success(`已上传：${created.name}`))
    .catch((e: any) => ElMessage.error(e?.response?.data?.error || e?.message || '上传失败'))
    .finally(() => {
      uploading.value = false
      if (folderInput.value) folderInput.value.value = ''
    })
}

async function handleMarkdownSubmit() {
  const md = markdownInput.value.trim()
  if (!md) {
    ElMessage.warning('请先填写 SKILL.md 内容')
    return
  }
  uploading.value = true
  try {
    const created = await store.uploadMarkdown(md)
    ElMessage.success(`已上传：${created.name}`)
    markdownInput.value = ''
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleView(row: SkillInfo) {
  drawerSkill.value = row
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    drawerSkill.value = await store.get(row.name)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || e?.message || '加载失败')
  } finally {
    drawerLoading.value = false
  }
}

async function handleDelete(row: SkillInfo) {
  try {
    await ElMessageBox.confirm(
      `确认删除技能 "${row.name}"？此操作不可撤销。`,
      '确认操作',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await store.remove(row.name)
  ElMessage.success(`技能 "${row.name}" 已删除`)
}

function refresh() { store.fetchList() }

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function formatTime(ts: number): string {
  if (!ts) return '—'
  return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' })
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

function pad3(n: number): string {
  if (n < 10) return `00${n}`
  if (n < 100) return `0${n}`
  return `${n}`
}

const exampleMarkdown = `---
name: my-skill
description: 一句话说明这个 skill 是做什么的
version: 1.0
tags: [example]
---

# 详细说明

告诉模型如何调用这个 skill。
可以包含示例代码、参考文档、注意事项。
`

const total = computed(() => store.list.length)
</script>

<template>
  <div class="skills-view">
    <header class="skills-view__head">
      <div>
        <div class="skills-view__crumb mono">
          // 技能库 · 共 {{ pad3(total) }} 条记录
        </div>
        <h1 class="skills-view__title">技能库</h1>
        <div class="skills-view__sub mono">
          持久化于 <code>.agentscope/skills/</code> — 每个文件夹必须包含
          <code>SKILL.md</code>
        </div>
      </div>
      <div class="skills-view__actions">
        <button class="btn-ghost mono" :disabled="store.loading" @click="refresh">
          <span class="btn-ghost__caret">↻</span>
          {{ store.loading ? '刷新中' : '刷新' }}
        </button>
      </div>
    </header>

    <section class="skills-uploader">
      <div class="skills-uploader__tabs">
        <button
          v-for="t in (['zip', 'folder', 'markdown'] as const)"
          :key="t"
          class="skills-uploader__tab mono"
          :class="{ 'is-active': uploadMode === t }"
          @click="uploadMode = t"
        >
          {{ t === 'zip' ? '压缩包' : t === 'folder' ? '文件夹' : 'Markdown' }}
        </button>
      </div>

      <div class="skills-uploader__panel">
        <div v-if="uploadMode === 'zip'">
          <input
            ref="fileInput"
            type="file"
            accept=".zip,.skill"
            style="display: none"
            @change="onFilePicked"
          />
          <div
            class="drop-row"
            :class="{ 'drop-row--active': isDragging, 'drop-row--busy': uploading }"
            @click="openPicker"
            @dragenter="onDragEnter"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <span class="drop-row__caret mono">{{ uploading ? '…' : '↓' }}</span>
            <span class="drop-row__text">
              将 <strong>.zip</strong> 或 <strong>.skill</strong> 拖到此处
              <span class="drop-row__sep">—</span>
              或点击选择文件
            </span>
            <span class="drop-row__hint mono">
              需包含 SKILL.md · 最大 50MB
            </span>
          </div>
        </div>

        <div v-else-if="uploadMode === 'folder'">
          <input
            ref="folderInput"
            type="file"
            webkitdirectory
            directory
            multiple
            style="display: none"
            @change="onFolderPicked"
          />
          <div
            class="drop-row"
            :class="{ 'drop-row--active': isDragging, 'drop-row--busy': uploading }"
            @click="openFolderPicker"
          >
            <span class="drop-row__caret mono">{{ uploading ? '…' : '↓' }}</span>
            <span class="drop-row__text">
              点击选择包含 <strong>SKILL.md</strong> 的文件夹
            </span>
            <span class="drop-row__hint mono">
              保留目录结构 · Firefox 暂不支持
            </span>
          </div>
        </div>

        <div v-else>
          <textarea
            v-model="markdownInput"
            class="md-input mono"
            rows="14"
            :placeholder="exampleMarkdown"
          ></textarea>
          <div class="md-bar">
            <button class="btn-primary" :disabled="!canSubmit || uploading" @click="handleMarkdownSubmit">
              <span class="btn-primary__caret">+</span>
              {{ uploading ? '创建中' : '创建技能' }}
            </button>
            <span class="md-bar__hint mono">
              YAML frontmatter 中必须包含 name / description，否则服务端会拒绝。
            </span>
          </div>
        </div>
      </div>
    </section>

    <section class="skills-table">
      <div class="skills-table__head mono">
        <div class="skills-table__col skills-table__col--name">名称</div>
        <div class="skills-table__col skills-table__col--desc">描述</div>
        <div class="skills-table__col skills-table__col--num">资源数</div>
        <div class="skills-table__col skills-table__col--num">大小</div>
        <div class="skills-table__col skills-table__col--time">修改时间</div>
        <div class="skills-table__col skills-table__col--act"></div>
      </div>

      <div v-if="store.loading && !store.list.length" class="skills-table__loading mono">
        正在加载技能…
      </div>

      <div v-else-if="!store.list.length" class="skills-table__empty">
        <div class="skills-table__empty-mark mono">// 暂无记录</div>
        <div class="skills-table__empty-line mono">
          请在上方上传技能以开始使用
        </div>
      </div>

      <article
        v-for="row in store.list"
        :key="row.name"
        class="skills-table__row"
        @click="handleView(row)"
      >
        <div class="skills-table__col skills-table__col--name">
          <div class="skills-row__name mono">{{ row.name }}</div>
        </div>
        <div class="skills-table__col skills-table__col--desc">
          <div class="skills-row__desc">{{ row.description || '—' }}</div>
        </div>
        <div class="skills-table__col skills-table__col--num mono">
          {{ pad2(row.resourceCount) }}
        </div>
        <div class="skills-table__col skills-table__col--num mono">
          {{ formatBytes(row.sizeBytes) }}
        </div>
        <div class="skills-table__col skills-table__col--time mono">
          {{ formatTime(row.modifiedAt) }}
        </div>
        <div class="skills-table__col skills-table__col--act">
          <button class="row-action" @click.stop="handleView(row)">查看</button>
          <button class="row-action row-action--danger" @click.stop="handleDelete(row)">
            删除
          </button>
        </div>
      </article>
    </section>

    <!-- drawer -->
    <transition name="drawer">
      <div v-if="drawerVisible" class="drawer-mask" @click.self="drawerVisible = false">
        <div class="drawer" role="dialog" aria-modal="true">
          <header class="drawer__head">
            <div>
              <div class="drawer__crumb mono">// 技能详情</div>
              <div class="drawer__title mono">{{ drawerSkill?.name || '—' }}</div>
            </div>
            <button class="btn-ghost mono drawer__close" @click="drawerVisible = false">
              关闭 ×
            </button>
          </header>
          <div class="drawer__body" v-loading="drawerLoading">
            <template v-if="drawerSkill">
              <dl class="kv">
                <dt class="kv__key mono">描述</dt>
                <dd>{{ drawerSkill.description || '—' }}</dd>
                <dt class="kv__key mono">资源数</dt>
                <dd class="mono">{{ drawerSkill.resourceCount }}</dd>
                <dt class="kv__key mono">大小</dt>
                <dd class="mono">{{ formatBytes(drawerSkill.sizeBytes) }}</dd>
                <dt class="kv__key mono">修改时间</dt>
                <dd class="mono">{{ formatTime(drawerSkill.modifiedAt) }}</dd>
                <template v-if="drawerSkill.extraMetadata && Object.keys(drawerSkill.extraMetadata).length">
                  <dt class="kv__key mono">元数据</dt>
                  <dd><pre class="kv__pre mono">{{ JSON.stringify(drawerSkill.extraMetadata, null, 2) }}</pre></dd>
                </template>
              </dl>

              <div class="drawer__section-head mono">// SKILL.md</div>
              <pre class="drawer__md mono">{{ drawerSkill.content }}</pre>
            </template>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.skills-view {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ----- head ----- */
.skills-view__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}
.skills-view__crumb {
  font-size: 11px;
  color: var(--txt2);
  margin-bottom: 6px;
}
.skills-view__title {
  margin: 0 0 6px;
  font-size: var(--fs-19);
  font-weight: 650;
  letter-spacing: 0.2px;
  color: var(--txt);
}
.skills-view__sub {
  font-size: 12px;
  color: var(--txt2);
}
.skills-view__sub code {
  font-family: var(--font-mono);
  background: var(--primary-soft);
  border: 1px solid var(--line);
  padding: 0 4px;
  border-radius: 4px;
  color: var(--txt);
  font-size: 11px;
}
.skills-view__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* ----- buttons (shared) ----- */
.btn-primary {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--primary);
  border: 1px solid var(--primary);
  padding: 7px 14px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: background-color 0.1s ease, border-color 0.1s ease;
}
.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-primary__caret { font-family: var(--font-mono); font-size: 14px; }

.btn-ghost {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--txt2);
  background: var(--primary-soft);
  border: 1px solid transparent;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color 0.1s ease, border-color 0.1s ease, background-color 0.1s ease;
}
.btn-ghost:hover:not(:disabled) {
  color: var(--primary);
  background: var(--primary-tint);
}
.btn-ghost:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-ghost__caret { font-size: 13px; }

/* ----- uploader ----- */
.skills-uploader { display: flex; flex-direction: column; }
.skills-uploader__tabs {
  display: flex;
  gap: 20px;
  border-bottom: 1px solid var(--border);
}
.skills-uploader__tab {
  font-family: var(--font-mono);
  font-size: 12px;
  background: transparent;
  border: none;
  padding: 8px 0;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  color: var(--txt2);
  cursor: pointer;
  transition: color 0.1s ease, border-color 0.1s ease;
}
.skills-uploader__tab:hover { color: var(--txt); }
.skills-uploader__tab.is-active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}
.skills-uploader__panel { padding: 16px 0 0; }

.drop-row {
  display: grid;
  grid-template-columns: 32px 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 14px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  cursor: pointer;
  transition: border-color 0.1s ease, background-color 0.1s ease;
  background: var(--panel);
}
.drop-row:hover { border-color: var(--txt2); }
.drop-row--active {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.drop-row--busy { opacity: 0.5; cursor: wait; }
.drop-row__caret {
  font-size: 18px;
  color: var(--primary);
  text-align: center;
}
.drop-row__text {
  font-size: 13px;
  color: var(--txt);
}
.drop-row__text strong {
  color: var(--primary);
  font-weight: 600;
}
.drop-row__sep {
  color: var(--txt3);
  margin: 0 4px;
}
.drop-row__hint {
  font-size: 11px;
  color: var(--txt3);
  text-align: right;
}

.md-input {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  resize: vertical;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--txt);
  padding: 12px;
  background: var(--panel);
  outline: none;
  transition: border-color 0.1s ease;
}
.md-input:focus { border-color: var(--primary); }
.md-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.md-bar__hint {
  font-size: 11px;
  color: var(--txt2);
}

/* ----- table ----- */
.skills-table { display: flex; flex-direction: column; }

.skills-table__head,
.skills-table__row {
  display: grid;
  grid-template-columns:
    minmax(0, 1.4fr)
    minmax(0, 2.4fr)
    minmax(0, 0.7fr)
    minmax(0, 0.7fr)
    minmax(0, 1.1fr)
    minmax(0, 0.9fr);
  gap: 16px;
  align-items: start;
  padding: 12px 0;
}
.skills-table__head {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 10px 0;
  color: var(--txt2);
  align-items: center;
  font-size: 11px;
}
.skills-table__row {
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
  transition: background-color 0.08s ease;
  position: relative;
}
.skills-table__row:hover { background: var(--bg); }
.skills-table__row::before {
  content: '';
  position: absolute;
  left: -24px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: transparent;
}
.skills-table__row:hover::before { background: var(--primary); }

.skills-table__col { min-width: 0; }
.skills-table__col--num {
  text-align: right;
  font-size: 12px;
  color: var(--txt);
  padding-top: 2px;
}
.skills-table__col--time {
  font-size: 11px;
  color: var(--txt2);
  padding-top: 3px;
}
.skills-table__col--act {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 14px;
  padding-top: 2px;
}

.skills-row__name {
  font-size: 13px;
  color: var(--txt);
  font-weight: 500;
}
.skills-row__desc {
  font-size: 13px;
  color: var(--txt2);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.row-action {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--txt2);
  background: transparent;
  border: none;
  padding: 2px 0;
  cursor: pointer;
  transition: color 0.1s ease;
}
.row-action:hover { color: var(--txt); }
.row-action--danger:hover { color: var(--red); }

.skills-table__loading {
  padding: 56px 0;
  color: var(--txt2);
  font-size: 13px;
  text-align: center;
}
.skills-table__empty {
  padding: 64px 24px;
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  background: var(--panel);
}
.skills-table__empty-mark {
  font-size: 13px;
  color: var(--txt3);
  margin-bottom: 8px;
}
.skills-table__empty-line {
  font-size: 13px;
  color: var(--txt2);
}

/* ----- drawer ----- */
.drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(24,27,32,.42);
  z-index: var(--z-overlay);
  display: flex;
  justify-content: flex-end;
}
.drawer {
  width: min(640px, 100%);
  height: 100%;
  background: var(--bg);
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}
.drawer__head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  background: var(--panel);
}
.drawer__crumb {
  font-size: 11px;
  color: var(--txt2);
  margin-bottom: 4px;
}
.drawer__title {
  font-size: 18px;
  font-weight: 650;
  color: var(--txt);
  letter-spacing: 0;
}
.drawer__close { flex-shrink: 0; }
.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.drawer__section-head {
  margin: 20px 0 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
  font-size: 11px;
  color: var(--txt2);
}
.drawer__md {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 12px 16px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--txt);
}

.kv {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 4px 16px;
  margin: 0;
}
.kv__key {
  color: var(--txt2);
  padding: 4px 0;
  font-size: 11px;
}
.kv dd {
  margin: 0;
  padding: 4px 0;
  color: var(--txt);
  border-bottom: 1px solid var(--line-soft);
  font-size: 13px;
}
.kv__pre { margin: 0; font-size: 11px; }

.drawer-enter-active, .drawer-leave-active { transition: opacity 0.12s ease; }
.drawer-enter-active .drawer, .drawer-leave-active .drawer { transition: transform 0.18s ease; }
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
.drawer-enter-from .drawer, .drawer-leave-to .drawer { transform: translateX(100%); }
</style>
