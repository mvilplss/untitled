import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElButton,
  ElInput,
  ElSelect,
  ElOption,
  ElForm,
  ElFormItem,
  ElCheckbox,
  ElCheckboxGroup,
  ElRadioGroup,
  ElRadioButton,
  ElLoading,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import { router } from './router'
import './style.css'

const app = createApp(App)

for (const [key, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, comp as never)
}

const components = [
  ElButton,
  ElInput,
  ElSelect,
  ElOption,
  ElForm,
  ElFormItem,
  ElCheckbox,
  ElCheckboxGroup,
  ElRadioGroup,
  ElRadioButton,
]
components.forEach(c => app.use(c))
app.use(ElLoading)

app.use(createPinia())
app.use(router)

app.config.globalProperties.$message = ElMessage
app.config.globalProperties.$confirm = ElMessageBox.confirm
app.config.globalProperties.$alert = ElMessageBox.alert
app.config.globalProperties.$prompt = ElMessageBox.prompt

app.mount('#app')
