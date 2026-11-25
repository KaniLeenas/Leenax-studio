import { describe, expect, it } from 'vitest'
import { textLook, transitionLook } from './effects'
import {
  addClip,
  captionsTrack,
  fadeEnvelope,
  findClip,
  freeStart,
  mediaIds,
  moveClip,
  newMediaClip,
  newTextClip,
  newTimeline,
  removeClips,
  setSpeed,
  snap,
  splitClip,
  timelineDuration,
  trimLeft,
  trimRight,
} from './timeline'
import type { MediaClip, MediaRef } from './types'

const video: MediaRef = { id: 'm1', kind: 'VIDEO', url: 'x', durationMs: 10_000 }
const music: MediaRef = { id: 'm2', kind: 'AUDIO', url: 'y', durationMs: 60_000 }

function withVideo() {
  const doc = newTimeline(1920, 1080)
  return addClip(doc, newMediaClip(video, 0))
}

describe('timeline editing', () => {
  it('puts clips on the right kind of track and never overlaps them', () => {
    let doc = withVideo()
    doc = addClip(doc, newMediaClip(video, 3)) // would overlap → goes after the first
    doc = addClip(doc, newMediaClip(music, 0))
    doc = addClip(doc, newTextClip(1))
    const videoTrack = doc.tracks.find((t) => t.kind === 'video')!
    expect(videoTrack.clips.map((c) => c.start)).toEqual([0, 10])
    expect(doc.tracks.find((t) => t.kind === 'audio')!.clips).toHaveLength(1)
    expect(doc.tracks.find((t) => t.kind === 'text')!.clips).toHaveLength(1)
    expect(timelineDuration(doc)).toBe(60)
    expect(mediaIds(doc).sort()).toEqual(['m1', 'm2'])
  })

  it('splits a clip so the second half continues from the right point in the source', () => {
    let doc = withVideo()
    const id = doc.tracks.find((t) => t.kind === 'video')!.clips[0].id
    doc = { ...doc, tracks: doc.tracks.map((t) => ({ ...t, clips: t.clips.map((c) => (c.id === id ? { ...(c as MediaClip), speed: 2, duration: 5 } : c)) })) }
    doc = splitClip(doc, id, 2)
    const [a, b] = doc.tracks.find((t) => t.kind === 'video')!.clips as MediaClip[]
    expect([a.start, a.duration, a.trimStart]).toEqual([0, 2, 0])
    expect([b.start, b.duration, b.trimStart]).toEqual([2, 3, 4]) // 2 s at 2× = 4 s into the source
    expect(splitClip(doc, a.id, 0.05)).toBe(doc) // too close to the edge
  })

  it('trims within the source and keeps the end fixed when trimming the left edge', () => {
    const clip = newMediaClip(video, 5)
    const left = trimLeft(clip, 7) as MediaClip
    expect([left.start, left.duration, left.trimStart]).toEqual([7, 8, 2])
    expect((trimLeft(left, 0) as MediaClip).start).toBe(5) // can't reveal before the source starts
    expect(trimRight(clip, 100, video).duration).toBe(10) // can't run past the source end
    const img = newMediaClip({ id: 'i', kind: 'IMAGE', url: '' }, 0)
    expect(trimRight(img, 30).duration).toBe(30)
  })

  it('changes speed keeping the same footage', () => {
    const fast = setSpeed(newMediaClip(video, 0), 2)
    expect(fast.duration).toBe(5)
    expect(setSpeed(fast, 10).speed).toBe(4)
  })

  it('moves only to tracks of the same kind, into the next free gap', () => {
    let doc = withVideo()
    doc = addClip(doc, newMediaClip(video, 20))
    const [first, second] = doc.tracks.find((t) => t.kind === 'video')!.clips
    const audioTrack = doc.tracks.find((t) => t.kind === 'audio')!
    expect(moveClip(doc, first.id, audioTrack.id, 0)).toBe(doc)
    const moved = moveClip(doc, second.id, second.id && doc.tracks.find((t) => t.kind === 'video')!.id, 4)
    expect(findClip(moved, second.id)!.clip.start).toBe(10)
  })

  it('ripple delete closes the gap', () => {
    let doc = withVideo()
    doc = addClip(doc, newMediaClip(video, 10))
    const [a, b] = doc.tracks.find((t) => t.kind === 'video')!.clips
    const out = removeClips(doc, new Set([a.id]), true)
    expect(findClip(out, b.id)!.clip.start).toBe(0)
    expect(findClip(removeClips(doc, new Set([a.id])), b.id)!.clip.start).toBe(10)
  })

  it('snaps to nearby edges only', () => {
    expect(snap(4.96, [0, 5, 10], 0.1)).toEqual({ time: 5, edge: 5 })
    expect(snap(4.5, [0, 5, 10], 0.1)).toEqual({ time: 4.5 })
  })

  it('finds free space', () => {
    const track = withVideo().tracks.find((t) => t.kind === 'video')!
    expect(freeStart(track, 'new', 3, 2)).toBe(10)
  })

  it('turns subtitle cues into caption clips', () => {
    const t = captionsTrack([{ start: 1, end: 2.5, text: 'வணக்கம்' }, { start: 3, end: 3, text: 'bad' }], 'Tamil captions')
    expect(t.kind).toBe('text')
    expect(t.clips).toHaveLength(1)
    expect(t.clips[0]).toMatchObject({ start: 1, duration: 1.5, text: 'வணக்கம்', caption: true })
  })
})

describe('effects', () => {
  it('fades volume and opacity in and out', () => {
    expect(fadeEnvelope(0.5, 10, 1, 2)).toBe(0.5)
    expect(fadeEnvelope(5, 10, 1, 2)).toBe(1)
    expect(fadeEnvelope(9, 10, 1, 2)).toBe(0.5)
  })

  it('transitions run only at the edges', () => {
    const fade = { type: 'fade' as const, duration: 1 }
    expect(transitionLook(0, 5, fade, fade).opacity).toBe(0)
    expect(transitionLook(2, 5, fade, fade).opacity).toBe(1)
    expect(transitionLook(5, 5, fade, fade).opacity).toBe(0)
    expect(transitionLook(0.2, 5, { type: 'wipe', duration: 1 }, fade).clipPath).toMatch(/^inset/)
  })

  it('typewriter reveals text over time', () => {
    expect(textLook(0, 5, 'typewriter', 'none', 40).visibleChars).toBe(0)
    expect(textLook(4, 5, 'typewriter', 'none', 40).visibleChars).toBeUndefined()
  })
})
