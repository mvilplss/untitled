export type ToolGroup =
  | 'filesystem'
  | 'shell'
  | 'web'
  | 'memory'
  | 'session'
  | 'plan'

export interface ToolOption {
  name: string
  group: ToolGroup
  label: string
  description: string
}

export interface ToolGroupMeta {
  key: ToolGroup
  label: string
  description: string
}