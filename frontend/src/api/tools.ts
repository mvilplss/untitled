import type { ToolGroupMeta, ToolOption } from '@/types/tools'

export const TOOL_GROUPS: ToolGroupMeta[] = [
  {
    key: 'filesystem',
    label: '文件系统',
    description: '读写、搜索 workspace 内的文件',
  },
  {
    key: 'shell',
    label: 'Shell',
    description: '在沙箱中执行 shell 命令',
  },
  {
    key: 'web',
    label: 'Web',
    description: '抓取 URL 与联网搜索',
  },
  {
    key: 'memory',
    label: '长期记忆',
    description: '跨会话持久化事实 / 检索记忆',
  },
  {
    key: 'session',
    label: '会话检索',
    description: '搜索与浏览其它会话历史',
  },
  {
    key: 'plan',
    label: 'Plan 模式',
    description: '进入只读规划阶段，先勘察再执行',
  },
]

export const AGENT_TOOLS: ToolOption[] = [
  { name: 'read_file',   group: 'filesystem', label: '读取文件', description: '读取文件内容' },
  { name: 'write_file',  group: 'filesystem', label: '写入文件', description: '创建/覆盖文件' },
  { name: 'edit_file',   group: 'filesystem', label: '编辑文件', description: '按 old/new 字符串原地修改' },
  { name: 'grep_files',  group: 'filesystem', label: '关键字搜索', description: '按文本模式搜索文件内容' },
  { name: 'glob_files',  group: 'filesystem', label: '通配搜索', description: '按 glob 模式枚举文件' },
  { name: 'list_files',  group: 'filesystem', label: '列出目录', description: '列出指定目录的内容' },

  { name: 'execute',     group: 'shell',      label: 'Shell 执行', description: 'execute：运行 shell 命令' },

  { name: 'web_fetch',   group: 'web',        label: '抓取 URL', description: 'fetch 给定 URL 的内容' },
  { name: 'web_search',  group: 'web',        label: '联网搜索', description: '搜索引擎查询' },

  { name: 'memory_search', group: 'memory',   label: '记忆搜索', description: '在长期记忆中按关键字查找' },
  { name: 'memory_get',    group: 'memory',   label: '记忆读取', description: '按行号读取记忆文件' },
  { name: 'memory_save',   group: 'memory',   label: '记忆保存', description: '向长期记忆追加事实' },

  { name: 'session_search',  group: 'session', label: '会话搜索', description: '跨会话全文检索' },
  { name: 'session_list',    group: 'session', label: '会话列表', description: '列出 agent 的所有会话' },
  { name: 'session_history', group: 'session', label: '会话历史', description: '读取指定会话的消息历史' },

  { name: 'plan_enter', group: 'plan', label: '进入 Plan', description: '切换到只读规划阶段' },
  { name: 'plan_write', group: 'plan', label: '写入计划', description: '创建/覆盖当前 plan 文档' },
  { name: 'plan_exit',  group: 'plan', label: '退出 Plan', description: '提交计划进入 BUILD 阶段' },
]

export function toolsOfGroup(group: ToolGroupMeta['key']): ToolOption[] {
  return AGENT_TOOLS.filter(t => t.group === group)
}

export function isKnownTool(name: string): boolean {
  return AGENT_TOOLS.some(t => t.name === name)
}