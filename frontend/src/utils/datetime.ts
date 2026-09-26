const TZ = 'Asia/Shanghai'

const pad = (n: number): string => (n < 10 ? `0${n}` : `${n}`)

/** 取 Asia/Shanghai 时区的 H:M:S（24h，补零） */
export function fmtTime(ts: number | Date): string {
  const d = typeof ts === 'number' ? new Date(ts) : ts
  return [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map(n => pad(n))
    .join(':')
}

/** 取 Asia/Shanghai 时区的完整日期时间（24h） */
export function fmtDateTime(ts: number | Date): string {
  const d = typeof ts === 'number' ? new Date(ts) : ts
  return d.toLocaleString('zh-CN', { hour12: false, timeZone: TZ })
}

/** 取 Asia/Shanghai 时区的日期（不带时间） */
export function fmtDate(ts: number): string {
  return new Date(ts * 1000).toLocaleDateString('zh-CN', { timeZone: TZ })
}
