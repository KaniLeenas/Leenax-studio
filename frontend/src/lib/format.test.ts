import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatRelative, formatResolution } from './format'

describe('format', () => {
  it('formats bytes', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(250 * 1024 * 1024)).toBe('250 MB')
    expect(formatBytes(3.2 * 1024 ** 3)).toBe('3.2 GB')
  })

  it('formats durations as timecode', () => {
    expect(formatDuration(0)).toBe('0:00')
    expect(formatDuration(65_400)).toBe('1:05')
    expect(formatDuration(3_725_000)).toBe('1:02:05')
    expect(formatDuration(undefined)).toBe('')
  })

  it('names common resolutions', () => {
    expect(formatResolution(1920, 1080)).toBe('1080p')
    expect(formatResolution(1080, 1920)).toBe('1080p')
    expect(formatResolution(3840, 2160)).toBe('4K')
    expect(formatResolution(640, 360)).toBe('640×360')
  })

  it('formats relative times', () => {
    const now = new Date('2026-11-19T12:00:00Z')
    expect(formatRelative('2026-11-19T11:59:30Z', now)).toBe('just now')
    expect(formatRelative('2026-11-19T11:15:00Z', now)).toBe('45 min ago')
    expect(formatRelative('2026-11-18T11:00:00Z', now)).toBe('yesterday')
  })
})
