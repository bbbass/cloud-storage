import http from './http'

export function fetchHealth() {
  return http.get('/health')
}
