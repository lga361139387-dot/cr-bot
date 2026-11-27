export const STATUS_LABEL: Record<string, string> = {
  PENDING: '待评审',
  RUNNING: '评审中',
  SUCCESS: '已完成',
  FAILED: '失败'
}

export const SEVERITY_LABEL: Record<string, string> = {
  info: '提示',
  low: '低',
  medium: '中',
  high: '高危'
}

export const SEVERITY_COLOR: Record<string, string> = {
  info: '#9ca3af',
  low: '#3b82f6',
  medium: '#f59e0b',
  high: '#ef4444'
}

export const BUSINESS_LABEL: Record<string, string> = {
  matched: '相符',
  unmatched: '不相符',
  skipped: '未比对'
}

export function statusText(status?: string) {
  if (!status) return '-'
  return STATUS_LABEL[status] || status
}

export function severityText(severity?: string) {
  if (!severity) return '未评级'
  return SEVERITY_LABEL[severity] || severity
}

export function severityClass(severity?: string) {
  if (severity === 'high' || severity === 'medium' || severity === 'low' || severity === 'info') {
    return 'badge-' + severity
  }
  return 'badge-none'
}

export function statusClass(status?: string) {
  if (status === 'SUCCESS') return 'badge-done'
  if (status === 'FAILED') return 'badge-failed'
  if (status === 'RUNNING') return 'badge-low'
  return 'badge-none'
}

export function businessText(status?: string) {
  if (!status) return '-'
  return BUSINESS_LABEL[status] || status
}

export function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatTokens(value?: number | null) {
  if (value == null) return '-'
  return value.toLocaleString('zh-CN')
}

export function eventText(eventType?: string) {
  if (eventType === 'push') return 'Push'
  if (eventType === 'merge_request') return 'Merge Request'
  return eventType || '-'
}
