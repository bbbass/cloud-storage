import http, { getDemoIdentity } from './http'

export function fetchHealth() {
  return http.get('/health')
}

export function fetchQuota() {
  return http.get('/quota')
}

export function initUpload(payload) {
  return http.post('/uploads', payload)
}

export function uploadPart(uploadId, partNo, chunk, options = {}) {
  // 分片接口收原始字节流；不传 contentType 查询参数，避开 Tomcat 对 %2F 的 400。
  return http.put(`/uploads/${uploadId}/parts/${partNo}`, chunk, {
    headers: { 'Content-Type': 'application/octet-stream' },
    params: { size: chunk.size },
    onUploadProgress: options.onUploadProgress,
    signal: options.signal
  })
}

export function completeUpload(uploadId) {
  return http.post(`/uploads/${uploadId}/complete`)
}

export function abortUpload(uploadId) {
  return http.delete(`/uploads/${uploadId}`)
}

export function fetchTasks() {
  return http.get('/tasks')
}

export function fetchTaskStats() {
  return http.get('/tasks/stats')
}

export function replayTask(taskId) {
  return http.post(`/tasks/${taskId}/replay`)
}

/**
 * EventSource 不能带自定义请求头，这里用 fetch 读 SSE 流，继续复用 X-User-Id / X-Device-Id。
 */
export async function streamUploadProgress(uploadId, handlers = {}, signal) {
  const identity = getDemoIdentity()
  const response = await fetch(`/api/v1/uploads/${uploadId}/progress`, {
    headers: {
      Accept: 'text/event-stream',
      'X-User-Id': identity.userId,
      'X-Device-Id': identity.deviceId
    },
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(`SSE 连接失败（HTTP ${response.status}）`)
  }

  handlers.onOpen?.()

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const dataLine = block.split('\n').find((line) => line.startsWith('data:'))
      if (dataLine) {
        const raw = dataLine.slice(5).trim()
        if (raw) {
          try {
            handlers.onEvent?.(JSON.parse(raw))
          } catch (e) {
            // 忽略坏事件，进度回退到分片上传响应的统计。
          }
        }
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}
