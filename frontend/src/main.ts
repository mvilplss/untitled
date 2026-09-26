import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import { router } from './router'
import './style.css'

// 启动期副作用：清理老 chat:* localStorage key（一次性迁移）
migrateClearLegacyLocalStorage()

// unplugin-auto-import + unplugin-vue-components 已自动注册
// element-plus 组件；这里只做必要的工作：pinia / router / icons / 工具类消息。
const app = createApp(App)

for (const [key, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, comp as never)
}

app.use(createPinia())
app.use(router)

app.config.globalProperties.$message = ElMessage
app.config.globalProperties.$confirm = ElMessageBox.confirm
app.config.globalProperties.$alert = ElMessageBox.alert
app.config.globalProperties.$prompt = ElMessageBox.prompt

app.mount('#app')

/**
 * 一次性清理老 chat:* localStorage key，避免残留干扰新逻辑。
 * 只在 main.ts 启动时跑一次；不进 store 工厂函数以保持 store 的纯函数性质。
 */
function migrateClearLegacyLocalStorage(): void {
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i)
      if (k && k.startsWith('chat:')) localStorage.removeItem(k)
    }
  } catch {
    // 忽略
  }
}
