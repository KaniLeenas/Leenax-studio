import type { Motion, TextAnimation, Transition, TransitionType } from './types'

export interface Look {
  opacity: number
  /** CSS transform fragments, applied in order. */
  transform: string[]
  filter: string[]
  clipPath?: string
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))
const ease = (p: number) => 1 - Math.pow(1 - clamp01(p), 3) // ease-out cubic

export function emptyLook(): Look {
  return { opacity: 1, transform: [], filter: [] }
}

/**
 * Look of a clip `t` seconds in, for its in/out transitions.
 * In: progress 0→1 over the first `duration`; out: 1→0 over the last.
 */
export function transitionLook(t: number, clipDuration: number, tIn: Transition, tOut: Transition): Look {
  const look = emptyLook()
  if (tIn.type !== 'none' && tIn.duration > 0 && t < tIn.duration) apply(look, tIn.type, ease(t / tIn.duration), 'in')
  const outStart = clipDuration - tOut.duration
  if (tOut.type !== 'none' && tOut.duration > 0 && t > outStart) apply(look, tOut.type, ease((clipDuration - t) / tOut.duration), 'out')
  return look
}

function apply(look: Look, type: TransitionType, p: number, dir: 'in' | 'out') {
  const away = (1 - p) * 100
  switch (type) {
    case 'fade':
      look.opacity *= p
      break
    case 'slide-left':
      look.transform.push(`translateX(${dir === 'in' ? away : -away}%)`)
      break
    case 'slide-right':
      look.transform.push(`translateX(${dir === 'in' ? -away : away}%)`)
      break
    case 'slide-up':
      look.transform.push(`translateY(${dir === 'in' ? away : -away}%)`)
      break
    case 'zoom':
      look.transform.push(`scale(${0.6 + 0.4 * p})`)
      look.opacity *= p
      break
    case 'wipe':
      look.clipPath = dir === 'in' ? `inset(0 ${away}% 0 0)` : `inset(0 0 0 ${away}%)`
      break
    case 'blur':
      look.filter.push(`blur(${(1 - p) * 24}px)`)
      look.opacity *= Math.min(1, p * 1.5)
      break
    case 'none':
      break
  }
}

/** Slow "Ken Burns" movement over the whole clip, progress 0→1. */
export function motionTransform(motion: Motion, progress: number): string[] {
  const p = clamp01(progress)
  switch (motion) {
    case 'zoom-in':
      return [`scale(${1 + 0.15 * p})`]
    case 'zoom-out':
      return [`scale(${1.15 - 0.15 * p})`]
    case 'pan-left':
      return ['scale(1.12)', `translateX(${4 - 8 * p}%)`]
    case 'pan-right':
      return ['scale(1.12)', `translateX(${-4 + 8 * p}%)`]
    default:
      return []
  }
}

export const TEXT_ANIMATION_SECONDS = 0.45

/** Text in/out animation. `visibleChars` is set for the typewriter effect. */
export function textLook(t: number, duration: number, aIn: TextAnimation, aOut: TextAnimation, length: number): Look & { visibleChars?: number } {
  const look: Look & { visibleChars?: number } = emptyLook()
  const d = Math.min(TEXT_ANIMATION_SECONDS, duration / 3)
  if (aIn !== 'none' && t < (aIn === 'typewriter' ? Math.min(duration * 0.6, length * 0.05) : d)) {
    const span = aIn === 'typewriter' ? Math.min(duration * 0.6, length * 0.05) : d
    textApply(look, aIn, t / span, length)
  }
  if (aOut !== 'none' && aOut !== 'typewriter' && t > duration - d) textApply(look, aOut, (duration - t) / d, length)
  return look
}

function textApply(look: Look & { visibleChars?: number }, a: TextAnimation, raw: number, length: number) {
  const p = ease(raw)
  switch (a) {
    case 'fade':
      look.opacity *= p
      break
    case 'slide-up':
      look.opacity *= p
      look.transform.push(`translateY(${(1 - p) * 40}px)`)
      break
    case 'pop': {
      // overshoot: 0.6 → 1.08 → 1
      const x = clamp01(raw)
      const s = x < 0.7 ? 0.6 + (0.48 * x) / 0.7 : 1.08 - (0.08 * (x - 0.7)) / 0.3
      look.transform.push(`scale(${s})`)
      look.opacity *= Math.min(1, x * 2)
      break
    }
    case 'typewriter':
      look.visibleChars = Math.floor(clamp01(raw) * length)
      break
    case 'none':
      break
  }
}

export function lookStyle(look: Look): React.CSSProperties {
  return {
    opacity: look.opacity,
    transform: look.transform.length ? look.transform.join(' ') : undefined,
    filter: look.filter.length ? look.filter.join(' ') : undefined,
    clipPath: look.clipPath,
  }
}
