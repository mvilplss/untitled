/** 两位补零（数字人侧栏角标 / 列表序号） */
export function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

/** 三位补零（数字人库头部统计） */
export function pad3(n: number): string {
  if (n < 10) return `00${n}`
  if (n < 100) return `0${n}`
  return `${n}`
}

/**
 * 把后端 unix 秒级时间戳格式化为"X秒前 / X分钟前 / X天前 / 完整日期"。
 * ts=0 或未传入返回'从未'。
 */
export function formatRelative(ts: number): string {
  if (!ts) return '从未'
  const sec = Math.floor(Date.now() / 1000 - ts)
  if (sec < 60) return `${sec}秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}小时前`
  const day = Math.floor(hr / 24)
  if (day < 30) return `${day}天前`
  return new Date(ts * 1000).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai' })
}