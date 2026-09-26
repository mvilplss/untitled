export interface MenuItem {
  /** unique key, used as v-for key and tooltip */
  key: string
  label: string
  /** single-char glyph (rendered as text, like the prototype) */
  icon: string
  /** vue-router path; undefined means the item is disabled */
  path?: string
  /** badge text shown on the right (kept static since the data is mocked) */
  badge?: string
  /** tooltip when disabled */
  disabledTip?: string
}

export interface MenuGroup {
  key: string
  label: string
  items: MenuItem[]
}

export const SIDE_MENU: MenuGroup[] = [
  {
    key: 'workspace',
    label: '工作台',
    items: [
      { key: 'digital-humans', label: '部门数字人', icon: '◈', path: '/digital-humans', badge: '24' },
      { key: 'conversations', label: '对话记录',     icon: '◎', path: '/chat' },
      { key: 'tasks',         label: '定时任务',     icon: '▤', path: '/tasks' },
      { key: 'usage',         label: '用量分析',     icon: '▲', disabledTip: '该模块开发中' },
    ],
  },
  {
    key: 'assets',
    label: '资产',
    items: [
      { key: 'skills',      label: '技能市场', icon: '✦', path: '/skills', badge: '68' },
      { key: 'tools',       label: '工具中心', icon: '⚙', disabledTip: '该模块开发中' },
      { key: 'packages',    label: '数字人包', icon: '❐', disabledTip: '该模块开发中' },
    ],
  },
  {
    key: 'governance',
    label: '治理',
    items: [
      { key: 'approval',  label: '发布审批', icon: '⛨', disabledTip: '该模块开发中' },
      { key: 'audit',     label: '审计日志', icon: '≡', disabledTip: '该模块开发中' },
      { key: 'risk',      label: '风险告警', icon: '⚑', disabledTip: '该模块开发中' },
    ],
  },
  {
    key: 'system',
    label: '系统',
    items: [
      { key: 'providers', label: '模型供应商', icon: '⚒', disabledTip: '该模块开发中' },
      { key: 'dws',       label: '钉钉 DWS',  icon: '✉', path: '/dws' },
      { key: 'settings',  label: '设置',       icon: '☰', disabledTip: '该模块开发中' },
    ],
  },
]
