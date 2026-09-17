import { http } from './client'
import type { SkillInfo } from '@/types/api'

export const listSkills = (): Promise<SkillInfo[]> =>
  http.get<SkillInfo[]>('/skills').then(r => r.data)

export const getSkill = (name: string): Promise<SkillInfo> =>
  http.get<SkillInfo>(`/skills/${encodeURIComponent(name)}`).then(r => r.data)

export const deleteSkill = (name: string): Promise<void> =>
  http.delete<void>(`/skills/${encodeURIComponent(name)}`).then(() => undefined)

export const uploadSkillZip = async (file: File): Promise<SkillInfo> => {
  const form = new FormData()
  form.append('file', file)
  const res = await http.post<SkillInfo>('/skills', form)
  return res.data
}

export const uploadSkillMarkdown = (markdown: string): Promise<SkillInfo> =>
  http
    .post<SkillInfo>('/skills', { markdown }, { headers: { 'Content-Type': 'application/json' } })
    .then(r => r.data)

export const uploadSkillFolder = (files: File[]): Promise<SkillInfo> => {
  const form = new FormData()
  for (const file of files) {
    // webkitRelativePath 在 webkitdirectory 模式下携带完整相对路径
    // FormData.append 的第三参数会作为 multipart filename 头发给后端
    form.append('file', file, file.webkitRelativePath || file.name)
  }
  return http.post<SkillInfo>('/skills/folder', form).then(r => r.data)
}