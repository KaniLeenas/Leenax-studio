export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i++
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[i]}`
}

/** Editing-style timecode: m:ss, or h:mm:ss for long media. */
export function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return ''
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

export function formatRelative(iso: string, now: Date = new Date()): string {
  const then = new Date(iso)
  const diff = (now.getTime() - then.getTime()) / 1000
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`
  if (diff < 86400 * 7) {
    const d = Math.floor(diff / 86400)
    return d === 1 ? 'yesterday' : `${d} days ago`
  }
  return then.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatResolution(w?: number, h?: number): string {
  if (!w || !h) return ''
  const short = Math.min(w, h)
  if (short >= 2160) return '4K'
  if (short >= 1440) return '1440p'
  if (short >= 1080) return '1080p'
  if (short >= 720) return '720p'
  return `${w}×${h}`
}

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  ARCHIVED: 'Archived',
}

export const RATIO_LABEL: Record<string, string> = {
  LANDSCAPE: '16:9 landscape',
  PORTRAIT: '9:16 vertical',
  SQUARE: '1:1 square',
}

export const LANGUAGE_LABEL: Record<string, string> = {
  EN: 'English',
  TA: 'தமிழ் Tamil',
}

export const UNIT_LABEL: Record<string, string> = {
  CREDITS: 'credits',
  USD: 'USD',
  SECONDS: 'seconds',
  UNITS: 'units',
}

/** "1,250 credits", "$12.40", "66.5 units" */
export function formatAmount(amount?: number | null, unit?: string | null): string {
  if (amount == null || !Number.isFinite(amount)) return '—'
  if (unit === 'USD') return `$${amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  const n = amount.toLocaleString(undefined, { maximumFractionDigits: 2 })
  return unit ? `${n} ${UNIT_LABEL[unit] ?? unit.toLowerCase()}` : n
}

export function formatDate(iso?: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}
