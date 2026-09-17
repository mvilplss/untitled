import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as agentApi from '@/api/agents'
import type { AgentSpec } from '@/types/api'

export const useAgentsStore = defineStore('agents', () => {
  const list = ref<AgentSpec[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      list.value = await agentApi.listAgents()
    } finally {
      loading.value = false
    }
  }

  async function create(spec: AgentSpec) {
    const created = await agentApi.createAgent(spec)
    await fetchList()
    return created
  }

  async function update(id: string, spec: AgentSpec) {
    const updated = await agentApi.updateAgent(id, spec)
    await fetchList()
    return updated
  }

  async function remove(id: string) {
    await agentApi.deleteAgent(id)
    list.value = list.value.filter(a => a.id !== id)
  }

  function findById(id: string): AgentSpec | undefined {
    return list.value.find(a => a.id === id)
  }

  return { list, loading, fetchList, create, update, remove, findById }
})