import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as taskApi from '@/api/tasks'
import type { ScheduledTask, TaskRun } from '@/types/api'

export const useTasksStore = defineStore('tasks', () => {
  const list = ref<ScheduledTask[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      list.value = await taskApi.listTasks()
    } finally {
      loading.value = false
    }
  }

  async function get(id: string): Promise<ScheduledTask> {
    return taskApi.getTask(id)
  }

  async function create(req: taskApi.TaskUpsertRequest) {
    const created = await taskApi.createTask(req)
    await fetchList()
    return created
  }

  async function update(id: string, req: taskApi.TaskUpsertRequest) {
    const updated = await taskApi.updateTask(id, req)
    await fetchList()
    return updated
  }

  async function remove(id: string) {
    await taskApi.deleteTask(id)
    list.value = list.value.filter(t => t.id !== id)
  }

  async function runNow(id: string): Promise<string> {
    const { runId } = await taskApi.runTaskNow(id)
    return runId
  }

  async function fetchRuns(id: string, limit = 100): Promise<TaskRun[]> {
    return taskApi.listTaskRuns(id, limit)
  }

  return { list, loading, fetchList, get, create, update, remove, runNow, fetchRuns }
})