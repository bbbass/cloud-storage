import axios from 'axios'

const USER_ID_KEY = 'cs.demo.userId'
const DEVICE_ID_KEY = 'cs.demo.deviceId'
const DEFAULT_USER_ID = '1'
const DEFAULT_DEVICE_ID = 'demo-device'
const BIG_ID_PATTERN = /"(uploadId|fileId|objectId|parentId|taskId|userId|id)"\s*:\s*(\d{16,})/g

export function getDemoIdentity() {
  return {
    userId: localStorage.getItem(USER_ID_KEY) || DEFAULT_USER_ID,
    deviceId: localStorage.getItem(DEVICE_ID_KEY) || DEFAULT_DEVICE_ID
  }
}

export function saveDemoIdentity({ userId, deviceId }) {
  localStorage.setItem(USER_ID_KEY, String(userId || '').trim() || DEFAULT_USER_ID)
  localStorage.setItem(DEVICE_ID_KEY, String(deviceId || '').trim() || DEFAULT_DEVICE_ID)
}

/**
 * 后端主键是雪花 Long，JSON number 进入浏览器会超过 Number.MAX_SAFE_INTEGER。
 * 前端只把已知 ID 字段转成字符串，避免 uploadId / fileId / taskId 精度丢失。
 */
function parseJsonKeepingBigIds(data) {
  if (typeof data !== 'string' || data.length === 0) {
    return data
  }
  try {
    return JSON.parse(data.replace(BIG_ID_PATTERN, '"$1":"$2"'))
  } catch (e) {
    return data
  }
}

function createBusinessError(body, status) {
  const error = new Error(body?.message || `请求失败(code=${body?.code})`)
  error.code = body?.code
  error.httpStatus = status
  return error
}

function normalizeError(error) {
  const response = error.response
  const body = response?.data
  const normalized = createBusinessError(body, response?.status)
  normalized.message = body?.message || error.message || '请求失败'
  normalized.retryAfter = Number(response?.headers?.['retry-after']) || undefined
  normalized.raw = error
  return normalized
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  transformResponse: [parseJsonKeepingBigIds]
})

http.interceptors.request.use((config) => {
  const identity = getDemoIdentity()
  config.headers = config.headers || {}
  if (typeof config.headers.set === 'function') {
    config.headers.set('X-User-Id', identity.userId)
    config.headers.set('X-Device-Id', identity.deviceId)
  } else {
    config.headers['X-User-Id'] = identity.userId
    config.headers['X-Device-Id'] = identity.deviceId
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(createBusinessError(body, response.status))
    }
    return body
  },
  (error) => Promise.reject(normalizeError(error))
)

export default http
