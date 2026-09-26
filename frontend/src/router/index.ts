import {
  createRouter,
  createWebHashHistory,
  createWebHistory,
  type RouterHistory,
  type RouteRecordRaw,
} from 'vue-router'
import { inElectron } from '@/utils/env'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/digital-humans',
  },
  {
    path: '/digital-humans',
    name: 'digital-humans',
    component: () => import('@/views/AgentsView.vue'),
    meta: { title: '数字人管理' },
  },
  {
    path: '/digital-humans/:id',
    name: 'digital-human-detail',
    component: () => import('@/views/AgentDetailView.vue'),
    meta: { title: '数字人详情' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话' },
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/views/TasksView.vue'),
    meta: { title: '定时任务' },
  },
  {
    path: '/skills',
    name: 'skills',
    component: () => import('@/views/SkillsView.vue'),
    meta: { title: '技能管理' },
  },
  {
    path: '/dws',
    name: 'dws',
    component: () => import('@/views/DwsWorkspaceView.vue'),
    meta: { title: '钉钉 DWS' },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/digital-humans',
  },
]

// Electron 跑在 file:// 下，HTML5 history 会丢失上下文；切到 hash 模式。
// Web / vite dev server 下继续用 HTML5 history，URL 更干净。
const createHistory = (): RouterHistory =>
  inElectron ? createWebHashHistory() : createWebHistory()

export const router = createRouter({
  history: createHistory(),
  routes,
})