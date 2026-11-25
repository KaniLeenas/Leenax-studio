import type { Cue } from './types'

/** 75.5 → "1:15.500"; hours appear only when needed. */
export function formatCueTime(seconds: number): string {
  const ms = Math.round(Math.max(0, seconds) * 1000)
  const h = Math.floor(ms / 3_600_000)
  const m = Math.floor((ms % 3_600_000) / 60_000)
  const s = Math.floor((ms % 60_000) / 1000)
  const rest = String(ms % 1000).padStart(3, '0')
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}.${rest}`
}

/** Accepts "1:15.5", "01:01:15,250", "75.5" or "75". Returns undefined if unreadable. */
export function parseCueTime(value: string): number | undefined {
  const v = value.trim().replace(',', '.')
  if (!/^(\d+:){0,2}\d+(\.\d+)?$/.test(v)) return undefined
  const parts = v.split(':').map(Number)
  let total = 0
  for (const p of parts) total = total * 60 + p
  return Math.round(total * 1000) / 1000
}

function vttTime(seconds: number): string {
  const ms = Math.round(Math.max(0, seconds) * 1000)
  const h = String(Math.floor(ms / 3_600_000)).padStart(2, '0')
  const m = String(Math.floor((ms % 3_600_000) / 60_000)).padStart(2, '0')
  const s = String(Math.floor((ms % 60_000) / 1000)).padStart(2, '0')
  return `${h}:${m}:${s}.${String(ms % 1000).padStart(3, '0')}`
}

/** WebVTT for the live preview, built from the cues being edited (unsaved changes included). */
export function toVtt(cues: Cue[]): string {
  return (
    'WEBVTT\n\n' +
    cues
      .filter((c) => c.text.trim())
      .map((c) => `${vttTime(c.start)} --> ${vttTime(c.end)}\n${c.text.replace(/\n\s*\n+/g, '\n').replace(/-->/g, '->').trim()}`)
      .join('\n\n') +
    '\n'
  )
}

/** Moves every cue; nothing goes before 0. */
export function shiftCues(cues: Cue[], by: number): Cue[] {
  return cues.map((c) => {
    const start = Math.max(0, round(c.start + by))
    return { ...c, start, end: Math.max(start + 0.2, round(c.end + by)) }
  })
}

export function activeCueIndex(cues: Cue[], time: number): number {
  return cues.findIndex((c) => time >= c.start && time < c.end)
}

/** A new empty cue right after `index` (or at the start), 2 seconds long, not overlapping the next one. */
export function insertCueAfter(cues: Cue[], index: number): Cue[] {
  const prev = cues[index]
  const next = cues[index + 1]
  const start = prev ? prev.end : 0
  const end = next ? Math.max(start + 0.5, Math.min(start + 2, next.start)) : start + 2
  const out = [...cues]
  out.splice(index + 1, 0, { start: round(start), end: round(end), text: '' })
  return out
}

/** Issues to fix before saving: empty text, end before start, overlaps. */
export function cueProblems(cues: Cue[]): Map<number, string> {
  const problems = new Map<number, string>()
  cues.forEach((c, i) => {
    if (c.end <= c.start) problems.set(i, 'Ends before it starts')
    else if (i > 0 && c.start < cues[i - 1].end - 0.001) problems.set(i, 'Overlaps the previous subtitle')
    else if (c.text.split('\n').some((l) => l.length > 50)) problems.set(i, 'Long line: may be hard to read on phones')
  })
  return problems
}

const round = (v: number) => Math.round(v * 1000) / 1000

export const SUBTITLE_LANGUAGES: { code: string; label: string }[] = [
  { code: 'ta', label: 'Tamil' },
  { code: 'en', label: 'English' },
  { code: 'si', label: 'Sinhala' },
  { code: 'hi', label: 'Hindi' },
  { code: 'ml', label: 'Malayalam' },
  { code: 'te', label: 'Telugu' },
  { code: 'kn', label: 'Kannada' },
  { code: 'ms', label: 'Malay' },
  { code: 'fr', label: 'French' },
  { code: 'de', label: 'German' },
  { code: 'es', label: 'Spanish' },
  { code: 'ar', label: 'Arabic' },
  { code: 'zh', label: 'Chinese' },
  { code: 'ja', label: 'Japanese' },
]

export const languageLabel = (code: string) => SUBTITLE_LANGUAGES.find((l) => l.code === code)?.label ?? code
