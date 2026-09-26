/**
 * 轻量 SSE 解析器，配合 fetch POST + ReadableStream 使用。
 * 浏览器 EventSource 不支持 POST body，因此用 fetch + 手动解析 SSE 帧。
 */

export interface SSEHandlers {
  [eventName: string]: (data: string) => void
}

/**
 * 解析 fetch response 的 SSE 流，按事件名分发到 handlers。
 * 流结束或出错时调用 onDone / onError。
 */
export async function parseSSEStream(
  response: Response,
  handlers: SSEHandlers,
  signal?: AbortSignal,
): Promise<void> {
  if (!response.body) {
    handlers.onError?.('SSE response body is empty')
    return
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      if (signal?.aborted) break
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sepIdx: number
      while ((sepIdx = buffer.indexOf('\n\n')) >= 0) {
        const rawBlock = buffer.slice(0, sepIdx)
        buffer = buffer.slice(sepIdx + 2)
        dispatchBlock(rawBlock, handlers)
      }
    }
    // flush remaining
    if (buffer.trim()) dispatchBlock(buffer, handlers)
  } catch (e: unknown) {
    if (!signal?.aborted) {
      const msg = e instanceof Error ? e.message : String(e)
      handlers.onError?.(msg)
    }
  } finally {
    try { reader.releaseLock() } catch { /* ignore */ }
  }
}

function dispatchBlock(block: string, handlers: SSEHandlers): void {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // SSE spec: "data: " (with space) — strip optional leading space
      const raw = line.slice(5)
      dataLines.push(raw.startsWith(' ') ? raw.slice(1) : raw)
    }
  }
  const data = dataLines.join('\n')
  const handler = handlers[event]
  if (handler) {
    handler(data)
  }
}
