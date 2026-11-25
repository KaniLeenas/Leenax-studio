import type { Emotion } from './types'

export const EMOTIONS: { id: Emotion; label: string }[] = [
  { id: 'NEUTRAL', label: 'Natural' },
  { id: 'STORYTELLING', label: 'Storytelling' },
  { id: 'DOCUMENTARY', label: 'Documentary' },
  { id: 'PROFESSIONAL', label: 'Professional' },
  { id: 'HAPPY', label: 'Happy' },
  { id: 'EXCITED', label: 'Excited' },
  { id: 'SAD', label: 'Sad' },
  { id: 'ANGRY', label: 'Angry' },
]

const PAUSE = /\[pause(?:\s+(\d+(?:\.\d+)?)\s*s?)?]/gi

/**
 * Characters actually spoken, a rough length estimate and the text for a short preview.
 * Tamil narration runs at roughly 12 characters a second, English at about 15.
 */
export function scriptStats(text: string) {
  const spoken = text.replace(PAUSE, ' ').replace(/\s+/g, ' ').trim()
  let pauses = 0
  for (const m of text.matchAll(PAUSE)) pauses += m[1] ? Math.min(10, Number(m[1])) : 0.5
  const tamil = (spoken.match(/[஀-௿]/g)?.length ?? 0) > spoken.length / 3
  const seconds = spoken.length / (tamil ? 12 : 15) + pauses
  const minutes = seconds < 60 ? '<1' : String(Math.round(seconds / 60))
  return { characters: spoken.length, minutes, previewText: previewSlice(text) }
}

/** First sentences up to 280 characters, cut at a sentence or word boundary. */
export function previewSlice(text: string, max = 280): string {
  const t = text.trim()
  if (t.length <= max) return t
  const cut = t.slice(0, max)
  const sentence = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? '), cut.lastIndexOf('। '))
  if (sentence > max / 3) return cut.slice(0, sentence + 1)
  const space = cut.lastIndexOf(' ')
  return space > 0 ? cut.slice(0, space) : cut
}

/** Speakers in order of first appearance, from lines like “Name: text” (same rules as the server). */
export function detectSpeakers(script: string): string[] {
  const seen = new Map<string, string>()
  for (const line of script.split(/\r?\n/)) {
    const m = /^\s*([^:：]{1,40})[:：]\s*(.+)$/.exec(line)
    if (!m || m[1].includes('[')) continue
    const name = m[1].trim()
    const key = name.toLowerCase()
    if (!seen.has(key)) seen.set(key, name)
  }
  return [...seen.values()]
}

/** Inserts text at the textarea's cursor (or the end) and returns the new value. */
export function insertAtCursor(el: HTMLTextAreaElement | null, value: string, insert: string): string {
  if (!el) return value + insert
  const start = el.selectionStart ?? value.length
  const end = el.selectionEnd ?? value.length
  const next = value.slice(0, start) + insert + value.slice(end)
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + insert.length, start + insert.length)
  })
  return next
}
