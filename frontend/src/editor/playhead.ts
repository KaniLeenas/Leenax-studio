import { useSyncExternalStore } from 'react'

/**
 * The playhead changes 30–60 times a second while playing. It lives outside React state
 * so only the few components that show it re-render, not the whole editor.
 */
export function createPlayhead() {
  let time = 0
  let playing = false
  const listeners = new Set<() => void>()
  const emit = () => listeners.forEach((l) => l())
  return {
    get: () => time,
    isPlaying: () => playing,
    set(t: number) {
      if (t !== time) {
        time = t
        emit()
      }
    },
    setPlaying(p: boolean) {
      if (p !== playing) {
        playing = p
        emit()
      }
    },
    subscribe(l: () => void) {
      listeners.add(l)
      return () => listeners.delete(l)
    },
  }
}

export type Playhead = ReturnType<typeof createPlayhead>

export function usePlayheadTime(p: Playhead) {
  return useSyncExternalStore(p.subscribe, p.get)
}

export function usePlaying(p: Playhead) {
  return useSyncExternalStore(p.subscribe, p.isPlaying)
}

/** 75.4 → "1:15.4" (tenths), 3725 → "1:02:05.0" */
export function formatTime(seconds: number, tenths = true): string {
  const s = Math.max(0, seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const secStr = tenths ? sec.toFixed(1).padStart(4, '0') : String(Math.floor(sec)).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${secStr}` : `${m}:${secStr}`
}

/** Ruler step: the smallest "nice" interval that leaves at least `minPx` between labels. */
export function rulerStep(pxPerSecond: number, minPx = 72): number {
  const steps = [0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800]
  return steps.find((s) => s * pxPerSecond >= minPx) ?? 3600
}
