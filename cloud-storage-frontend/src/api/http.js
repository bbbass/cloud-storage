import axios from 'axios'

// 统一响应体 Result<T>：code=0 才算成功，其余抛出错误信息（见 AGENTS.md 3.1）。
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(new Error(body.message || `请求失败(code=${body.code})`))
    }
    return body
  },
  (error) => Promise.reject(error)
)

export default http
