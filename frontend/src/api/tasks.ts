import { http } from './client'
import type { ScheduledTask, TaskRun } from '@/types/api'

export interface TaskUpsertRequest {
  id: string
  name: string
  agentId: string
  prompt: string
  skills?: string[]
  cron: string
  enabled: boolean
}

export const listTasks = (): Promise<ScheduledTask[]> =>
  http.get<ScheduledTask[]>('/tasks').then(r => r.data)

export const getTask = (id: string): Promise<ScheduledTask> =>
  http.get<ScheduledTask>(`/tasks/${encodeURIComponent(id)}`).then(r => r.data)

export const createTask = (req: TaskUpsertRequest): Promise<ScheduledTask> =>
  http.post<ScheduledTask>('/tasks', req).then(r => r.data)

export const updateTask = (id: string, req: TaskUpsertRequest): Promise<ScheduledTask> =>
  http.put<ScheduledTask>(`/tasks/${encodeURIComponent(id)}`, req).then(r => r.data)

export const deleteTask = (id: string): Promise<void> =>
  http.delete<void>(`/tasks/${encodeURIComponent(id)}`).then(() => undefined)

export const runTaskNow = (id: string): Promise<{ runId: string }> =>
  http.post<{ runId: string }>(`/tasks/${encodeURIComponent(id)}/run`).then(r => r.data)

export const listTaskRuns = (id: string, limit = 100): Promise<TaskRun[]> =>
  http.get<TaskRun[]>(`/tasks/${encodeURIComponent(id)}/runs`, {
    params: { limit },
  }).then(r => r.data)