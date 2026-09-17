import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as skillApi from '@/api/skills'
import type { SkillInfo } from '@/types/api'

export const useSkillsStore = defineStore('skills', () => {
  const list = ref<SkillInfo[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      list.value = await skillApi.listSkills()
    } finally {
      loading.value = false
    }
  }

  async function uploadZip(file: File) {
    const created = await skillApi.uploadSkillZip(file)
    await fetchList()
    return created
  }

  async function uploadMarkdown(markdown: string) {
    const created = await skillApi.uploadSkillMarkdown(markdown)
    await fetchList()
    return created
  }

  async function uploadFolder(files: File[]) {
    const created = await skillApi.uploadSkillFolder(files)
    await fetchList()
    return created
  }

  async function remove(name: string) {
    await skillApi.deleteSkill(name)
    list.value = list.value.filter(s => s.name !== name)
  }

  async function get(name: string): Promise<SkillInfo> {
    return skillApi.getSkill(name)
  }

  return { list, loading, fetchList, uploadZip, uploadMarkdown, uploadFolder, remove, get }
})