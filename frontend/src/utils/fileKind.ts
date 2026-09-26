/**
 * 沙箱文件预览分类器与渲染策略。
 *
 * <p>目标：
 * <ul>
 *   <li>类型安全：分类与渲染由 TypeScript 联合类型驱动</li>
 *   <li>关注点分离：分类器 / 渲染策略 与 Vue 视图解耦</li>
 *   <li>可测试：classify 是纯函数，可被 vitest 直接覆盖</li>
 *   <li>可扩展：新增类型只需在 RULES 加一条 + 在视图注册渲染分支</li>
 * </ul>
 *
 * <p>iframe sandbox 安全策略：HTML / SVG 含 {@code <script>} 时可能引发 XSS，
 * 因此渲染到 {@code <iframe sandbox="">} 中：
 * <ul>
 *   <li>禁止脚本执行（防 XSS / Agent 生成恶意内容）</li>
 *   <li>禁止表单提交（防数据外泄）</li>
 *   <li>禁止 top-level navigation（防钓鱼跳转）</li>
 *   <li>禁止外部资源加载（CDN link / script 被拦截）</li>
 * </ul>
 * 影响：HTML 里若引用外部 CSS/JS，预览中不生效；用户只看到 HTML 结构，
 * 适合「布局 + 文本内容」级别的快速预览。
 */

export type FileKind = 'image' | 'markdown' | 'html' | 'svg' | 'code'

export interface FileKindInfo {
  /** 决定渲染分支 */
  kind: FileKind
  /** 是否使用 iframe 渲染（隔离脚本/表单/外部资源） */
  useFrame: boolean
  /** iframe sandbox 属性值；null 表示不走 iframe */
  sandbox: '' | null
}

const RULES: ReadonlyArray<{ exts: readonly string[]; info: FileKindInfo }> = [
  // 图片：走 el-image，img 标签对 SVG 内嵌脚本天然禁用，无需 sandbox
  {
    exts: ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp'],
    info: { kind: 'image', useFrame: false, sandbox: null },
  },
  // SVG：可含 <script>，与 HTML 同级别风险，走 iframe sandbox
  {
    exts: ['.svg'],
    info: { kind: 'svg', useFrame: true, sandbox: '' },
  },
  // Markdown：走 marked 渲染（v-html）
  {
    exts: ['.md', '.markdown'],
    info: { kind: 'markdown', useFrame: false, sandbox: null },
  },
  // HTML：走 iframe sandbox
  {
    exts: ['.html', '.htm'],
    info: { kind: 'html', useFrame: true, sandbox: '' },
  },
]

/** 取文件扩展名（小写、含点）。 */
export function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i).toLowerCase()
}

/**
 * 按扩展名分类。未匹配时返回 code（走 highlight.js 渲染）。
 * <p>扩展名取文件名的最后一个点之后（archive.tar.gz → .gz）。
 */
export function classify(name: string): FileKindInfo {
  const e = extOf(name)
  for (const r of RULES) {
    if ((r.exts as readonly string[]).includes(e)) return r.info
  }
  return { kind: 'code', useFrame: false, sandbox: null }
}

/**
 * 文件 / 目录图标（emoji）。配合 el-tree 的 render-content slot 使用。
 * @param name   文件或目录名
 * @param isDir  是否目录
 * @param isOpen 目录是否展开（仅目录区分；null 表示不确定，按 closed 显示）
 */
export function iconFor(name: string, isDir: boolean, isOpen: boolean | null = null): string {
  if (isDir) return isOpen ? '📂' : '📁'
  const e = extOf(name)
  // 图片
  if (['.png','.jpg','.jpeg','.gif','.webp','.svg','.ico','.bmp'].includes(e)) return '🖼'
  // PDF
  if (e === '.pdf') return '📕'
  // Markdown / Word
  if (['.md','.markdown','.doc','.docx'].includes(e)) return '📘'
  // 表格
  if (['.xls','.xlsx','.csv'].includes(e)) return '📊'
  // 演示
  if (['.ppt','.pptx'].includes(e)) return '📈'
  // HTML / XML
  if (['.html','.htm','.xml'].includes(e)) return '🌐'
  // 文本
  if (['.txt','.log'].includes(e)) return '📃'
  // 代码 / 配置
  if ([
    '.js','.mjs','.cjs','.ts','.tsx','.jsx','.vue',
    '.py','.java','.go','.rs','.c','.cpp','.h','.hpp',
    '.rb','.php','.swift','.kt','.scala',
    '.sh','.bash','.zsh',
    '.yaml','.yml','.json','.toml','.ini','.conf','.env',
  ].includes(e)) return '⚙️'
  // 压缩包
  if (['.zip','.tar','.gz','.tgz','.bz2','.7z','.rar','.jar','.war'].includes(e)) return '📦'
  // 音频
  if (['.mp3','.wav','.flac','.ogg','.m4a'].includes(e)) return '🎵'
  // 视频
  if (['.mp4','.mov','.avi','.mkv','.webm'].includes(e)) return '🎬'
  // 默认
  return '📄'
}
