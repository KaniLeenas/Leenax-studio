import type { SocialPlatform } from './publish'

// ---- types (mirror backend AnalyticsDtos / SystemStatusService) ----------------------------------

export interface Overview {
  days: number
  from: string
  to: string
  timeZone: string
  costs: {
    totals: { unit: string; amount: number }[]
    daily: { date: string; unit: string; amount: number }[]
    byProvider: { provider: string; capability: string; unit?: string; amount: number; calls: number }[]
    calls: number
    failed: number
    unknownCost: number
  }
  publishing: {
    posts: number
    published: number
    failed: number
    upcoming: number
    byPlatform: { platform: SocialPlatform; published: number; failed: number }[]
    daily: { date: string; platform: SocialPlatform; count: number }[]
    recentFailures: { postId: string; title: string; platform: SocialPlatform; accountName: string; error?: string; at: string }[]
  }
  performance: {
    byPlatform: { platform: SocialPlatform; posts: number; withNumbers: number; views?: number; likes?: number; comments?: number; shares?: number }[]
    posts: TopPost[]
    lastCollectedAt?: string
  }
}

export interface TopPost {
  postId: string
  targetId: string
  title: string
  platform: SocialPlatform
  accountName: string
  url?: string
  views?: number
  likes?: number
  comments?: number
  shares?: number
  publishedAt?: string
  collectedAt?: string
  note?: string
}

export type ServiceState = 'OK' | 'WARNING' | 'DOWN' | 'OFF'

export interface SystemStatus {
  checkedAt: string
  uptime: string
  javaVersion: string
  schemaVersion?: string
  services: { name: string; state: ServiceState; detail: string }[]
  queues: { name: string; waiting: number; running: number; failedToday: number }[]
  mediaFiles: number
  mediaBytes: number
  databaseBytes?: number
  lastBackup?: { finishedAt?: string; ok: boolean; databaseBytes?: number; mediaBytes?: number; folder?: string; error?: string }
  backupDisk?: { path: string; totalBytes: number; freeBytes: number }
  warnings: string[]
}

export interface AuditEntry {
  id: string
  action: string
  entityType?: string
  entityId?: string
  ipAddress?: string
  details: Record<string, unknown>
  createdAt: string
  /** Who did it (administrators see everyone's activity). */
  userName?: string
}

// ---- chart colours: one fixed slot per platform (validated categorical order) ----

/** Colour follows the platform, never its rank, so filtering never repaints a series. */
export const PLATFORM_SERIES: Record<SocialPlatform, string> = {
  YOUTUBE: 'var(--series-1)',
  FACEBOOK: 'var(--series-2)',
  INSTAGRAM: 'var(--series-3)',
  TIKTOK: 'var(--series-4)',
}

// ---- helpers ---------------------------------------------------------------------------------------

/** Every calendar day from `from` (inclusive) to `to` (inclusive), as YYYY-MM-DD in the given zone. */
export function dayRange(fromIso: string, toIso: string, timeZone: string): string[] {
  const fmt = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
  const days: string[] = [fmt.format(new Date(fromIso))]
  const end = fmt.format(new Date(toIso))
  if (days[0] === end) return days
  let t = new Date(fromIso).getTime() + 12 * 3600_000 // stepping from mid-day avoids DST edges
  for (let i = 0; i < 400; i++) {
    const d = fmt.format(new Date(t))
    if (!days.includes(d)) days.push(d)
    if (d === end) break
    t += 24 * 3600_000
  }
  return days
}

/** Rows of {date, values per key} with zeros filled in, for stacked columns. */
export function pivot<K extends string>(days: string[], rows: { date: string; key: K; value: number }[], keys: K[]) {
  return days.map((date) => {
    const values = Object.fromEntries(keys.map((k) => [k, 0])) as Record<K, number>
    for (const r of rows) if (r.date === date) values[r.key] += r.value
    return { date, values }
  })
}

/** Clean axis ticks: 0 and up to 4 round steps reaching max. */
export function niceTicks(max: number, count = 4): number[] {
  if (max <= 0) return [0, 1]
  const raw = max / count
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? 10 * mag
  const ticks: number[] = []
  for (let v = 0; v <= max + step * 0.001 || ticks.length < 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6)
  if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step)
  return ticks
}

/** 0 / 950 / 9,500 / 12K / 3.4M, one style per axis. */
export function compact(n: number): string {
  if (Math.abs(n) < 10_000) return n.toLocaleString(undefined, { maximumFractionDigits: n % 1 ? 2 : 0 })
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n)
}

export const UNIT_NAME: Record<string, string> = { USD: 'US dollars', CREDITS: 'credits', UNITS: 'units', CHARACTERS: 'characters' }

export function formatMoney(amount: number, unit?: string): string {
  if (unit === 'USD') return amount.toLocaleString(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
  return `${amount.toLocaleString(undefined, { maximumFractionDigits: 2 })} ${(unit ?? '').toLowerCase()}`.trim()
}

export function shortDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

/** "2 days 3 h" from an ISO-8601 duration like PT51h12m. */
export function formatUptime(iso: string): string {
  const m = /PT(?:(\d+)H)?(?:(\d+)M)?/.exec(iso)
  const h = Number(m?.[1] ?? 0)
  const min = Number(m?.[2] ?? 0)
  if (h >= 24) return `${Math.floor(h / 24)} d ${h % 24} h`
  if (h > 0) return `${h} h ${min} min`
  return `${min} min`
}

/** Plain words for audit actions. */
export function describeAction(action: string): string {
  const map: Record<string, string> = {
    LOGIN_SUCCEEDED: 'Signed in',
    LOGIN_FAILED: 'Failed sign-in (wrong email or password)',
    LOGIN_PASSWORD_OK_MFA_REQUIRED: 'Password accepted, waiting for the authenticator code',
    LOGIN_MFA_FAILED: 'Wrong authenticator code at sign-in',
    MFA_ENABLED: 'Two-step sign-in turned on',
    MFA_DISABLED: 'Two-step sign-in turned off',
    MFA_DISABLE_REJECTED: 'Attempt to turn off two-step sign-in refused',
    MFA_SETUP_REJECTED: 'Two-step set-up refused (wrong password)',
    MFA_CODES_REJECTED: 'New recovery codes refused (wrong password)',
    MFA_RECOVERY_CODES_REPLACED: 'New recovery codes made',
    MFA_RECOVERY_CODE_USED: 'Signed in with a recovery code',
    PASSWORD_CHANGED: 'Password changed',
    PASSWORD_CHANGE_REJECTED: 'Password change refused (wrong current password)',
    OWNER_CREATED: 'First administrator created',
    USER_INVITED: 'Someone was invited',
    ACCOUNT_ACTIVATED: 'Account activated',
    ACTIVATION_LINK_ISSUED: 'Activation link made',
    RESET_LINK_ISSUED: 'Password reset link made',
    PASSWORD_RESET: 'Password reset through a link',
    USER_ROLE_CHANGED: 'Role changed',
    USER_DEACTIVATED: 'Account deactivated',
    USER_REACTIVATED: 'Account reactivated',
    SEAT_LIMIT_CHANGED: 'Seat limit changed',
    PROJECT_MEMBER_ADDED: 'Added to a project',
    PROJECT_MEMBER_ROLE: 'Project role changed',
    PROJECT_MEMBER_REMOVED: 'Removed from a project',
    VOICE_SHARED: 'Voice shared with the team',
    VOICE_UNSHARED: 'Voice made private',
    SETUP_REJECTED: 'Set-up attempt with a wrong token',
    SOCIAL_CONNECT_STARTED: 'Started connecting a social account',
    SOCIAL_ACCOUNT_CONNECTED: 'Social account connected',
    SOCIAL_ACCOUNT_DISCONNECTED: 'Social account disconnected',
    AI_ACCOUNT_ADDED: 'AI account added',
    AI_ACCOUNT_DELETED: 'AI account deleted',
    AI_ACCOUNT_KEY_REPLACED: 'AI account key replaced',
    POST_CREATED: 'Post created',
    POST_UPDATED: 'Post edited',
    POST_PUBLISHED: 'Published to a platform',
    POST_FAILED: 'Publishing failed',
    POST_CANCELLED: 'Post cancelled',
    POST_RESCHEDULED: 'Post moved',
    POST_RETRIED: 'Publishing retried',
    POST_DELETED: 'Post removed',
    MEDIA_DELETED: 'File deleted',
    PROJECT_CREATED: 'Project created',
    PROJECT_DELETED: 'Project deleted',
  }
  return map[action] ?? action.charAt(0) + action.slice(1).toLowerCase().replace(/_/g, ' ')
}
