import { formatAmount } from './format'
import type { AiAccount, AiModelInfo, GenerationJob, GenerationMode, OptionItem, Pricing } from './types'

export function ratiosFor(model: AiModelInfo | undefined, mode: GenerationMode): OptionItem[] {
  const map = model?.options.aspectRatios
  return map?.[mode] ?? map?.ALL ?? []
}

export function durationChoices(model: AiModelInfo | undefined): number[] {
  const d = model?.options.durations
  if (!d) return []
  if (d.values) return d.values
  if (d.min != null && d.max != null) {
    const out: number[] = []
    for (let s = d.min; s <= d.max; s++) out.push(s)
    return out
  }
  return []
}

export function isActive(j: GenerationJob) {
  return j.status === 'QUEUED' || j.status === 'PROCESSING'
}

/** Groups variations generated together (same request) into one "take". */
export function groupJobs(jobs: GenerationJob[]) {
  const groups = new Map<string, GenerationJob[]>()
  for (const j of jobs) {
    const list = groups.get(j.groupId) ?? []
    list.push(j)
    groups.set(j.groupId, list)
  }
  return [...groups.values()].map((list) => list.sort((a, b) => a.variationIndex - b.variationIndex))
}

export function statusLine(j: GenerationJob): string {
  if (j.status === 'QUEUED') return j.attempts > 0 ? 'Waiting to retry' : 'Queued'
  if (j.status === 'PROCESSING') {
    if (j.progress != null && j.progress > 0) return `Generating, ${Math.round(j.progress * 100)}%`
    return 'Generating'
  }
  if (j.status === 'FAILED') return 'Failed'
  if (j.status === 'CANCELLED') return 'Cancelled'
  return 'Ready'
}

export function describePricing(p: Pricing): string {
  const rates = p.rates ?? []
  if (rates.length === 0) return 'No price set'
  const unit = p.unit ?? 'CREDITS'
  const parts = rates.map((r) => {
    const tag = [r.resolution, r.audio === true ? 'with sound' : r.audio === false ? 'silent' : undefined].filter(Boolean).join(', ')
    return `${formatAmount(r.perSecond, unit)}/s${tag ? ` (${tag})` : ''}`
  })
  return parts.join(', ') + (p.minimum ? `, minimum ${formatAmount(p.minimum, unit)}` : '')
}

export const ACCOUNT_STATUS_TEXT: Record<AiAccount['status'], string> = {
  ACTIVE: 'Connected',
  UNVERIFIED: 'Not checked',
  INVALID: 'Key rejected',
  DISABLED: 'Switched off',
}

export function accountStatusTone(s: AiAccount['status']) {
  return s === 'ACTIVE' ? 'done' : s === 'INVALID' ? 'progress' : 'muted'
}

const NAMED_RATIOS: [string, number][] = [
  ['16:9', 16 / 9], ['9:16', 9 / 16], ['1:1', 1], ['4:3', 4 / 3], ['3:4', 3 / 4], ['21:9', 21 / 9], ['9:21', 9 / 21],
]

/** "1920:1080" → "16:9"; "auto_720p" and already-named ratios pass through. */
export function prettyRatio(value?: string): string | undefined {
  if (!value) return undefined
  const m = /^(\d+):(\d+)$/.exec(value)
  if (!m) return value
  const w = Number(m[1])
  const h = Number(m[2])
  if (w <= 32 && h <= 32) return value
  const r = w / h
  const [name, ratio] = NAMED_RATIOS.reduce((best, cur) => (Math.abs(cur[1] - r) < Math.abs(best[1] - r) ? cur : best))
  return Math.abs(ratio - r) < 0.08 ? name : value
}
