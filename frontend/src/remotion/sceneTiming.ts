import { Easing, interpolate } from 'remotion'

/** 0 → 1 between `at` and `at + dur`, eased. */
export function ease(t: number, at: number, dur: number) {
  return interpolate(t, [at, at + dur], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic) })
}

/** When each of n items appears while the narration runs: spread over its first 70%. */
export function appearTimes(n: number, start: number, duration: number): number[] {
  if (n <= 0) return []
  const span = Math.max(0.6, duration * 0.7)
  return Array.from({ length: n }, (_, i) => start + (i * span) / n)
}

