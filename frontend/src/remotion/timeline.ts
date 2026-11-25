import type { Clip, CompositionProps, MediaClip, MediaRef, TextClip, TimelineDoc, Track, TrackKind } from './types'

export const MIN_CLIP = 0.1
export const MAX_DURATION = 3 * 60 * 60

let counter = 0
export const uid = (prefix: string) => `${prefix}${Date.now().toString(36)}${(counter++).toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`

const r3 = (v: number) => Math.round(v * 1000) / 1000

export const FORMATS = [
  { id: 'landscape', label: 'Landscape 16:9 (YouTube)', width: 1920, height: 1080 },
  { id: 'portrait', label: 'Vertical 9:16 (Shorts, Reels, TikTok)', width: 1080, height: 1920 },
  { id: 'square', label: 'Square 1:1', width: 1080, height: 1080 },
  { id: 'feed', label: 'Portrait 4:5 (Instagram feed)', width: 1080, height: 1350 },
] as const

export function newTrack(kind: TrackKind, n = 1): Track {
  const names = { video: 'Video', audio: 'Audio', text: 'Text' }
  return { id: uid('t'), kind, name: `${names[kind]} ${n}`, muted: false, hidden: false, locked: false, volume: 1, clips: [] }
}

export function newTimeline(width: number, height: number, fps = 30): TimelineDoc {
  return { version: 1, width, height, fps, background: '#000000', tracks: [newTrack('text'), newTrack('video'), newTrack('audio')] }
}

/** Last moment anything plays; at least 1 second so the player always has a frame. */
export function timelineDuration(doc: TimelineDoc): number {
  let end = 0
  for (const t of doc.tracks) for (const c of t.clips) end = Math.max(end, c.start + c.duration)
  return Math.max(1, r3(end))
}

export const toFrames = (seconds: number, fps: number) => Math.max(0, Math.round(seconds * fps))

/** Seconds of the source file this clip can still use (undefined for images and text). */
export function sourceLength(clip: MediaClip, media?: MediaRef): number | undefined {
  if (clip.kind === 'IMAGE' || !media?.durationMs) return undefined
  return media.durationMs / 1000
}

export function maxDuration(clip: Clip, media?: MediaRef): number {
  if (clip.type !== 'media') return MAX_DURATION
  const len = sourceLength(clip, media)
  return len === undefined ? MAX_DURATION : r3(Math.max(MIN_CLIP, (len - clip.trimStart) / clip.speed))
}

export function newMediaClip(media: MediaRef, start: number, fallbackImageSeconds = 5): MediaClip {
  const duration = media.kind === 'IMAGE' ? fallbackImageSeconds : Math.max(MIN_CLIP, (media.durationMs ?? 5000) / 1000)
  return {
    id: uid('c'),
    type: 'media',
    mediaId: media.id,
    kind: media.kind,
    start: r3(start),
    duration: r3(duration),
    trimStart: 0,
    speed: 1,
    volume: 1,
    fadeIn: 0,
    fadeOut: 0,
    fit: 'cover',
    x: 0,
    y: 0,
    scale: 1,
    rotation: 0,
    opacity: 1,
    filters: { brightness: 1, contrast: 1, saturation: 1, blur: 0 },
    motion: 'none',
    transitionIn: { type: 'none', duration: 0.5 },
    transitionOut: { type: 'none', duration: 0.5 },
  }
}

type TextPreset = { id: string; label: string; clip: Partial<Omit<TextClip, 'style'>> & { style: Partial<TextClip['style']> } }

export const TEXT_PRESETS: TextPreset[] = [
  {
    id: 'title',
    label: 'Big title',
    clip: { text: 'தலைப்பு / Title', y: 0.45, animationIn: 'pop', animationOut: 'fade', style: { fontSize: 120, fontWeight: 800, stroke: '#000000', strokeWidth: 6 } },
  },
  {
    id: 'lower-third',
    label: 'Lower third',
    clip: { text: 'Kani · Leenax Studio', x: 0.3, y: 0.82, animationIn: 'slide-up', animationOut: 'fade', style: { fontSize: 54, fontWeight: 700, background: '#f5a524', color: '#14182e', align: 'left', width: 0.5, strokeWidth: 0 } },
  },
  {
    id: 'caption',
    label: 'Caption',
    clip: { text: 'Subtitle text', y: 0.86, animationIn: 'none', animationOut: 'none', style: { fontSize: 56, fontWeight: 700, background: 'rgba(0,0,0,0.55)', strokeWidth: 0, width: 0.86 } },
  },
]

export function newTextClip(start: number, preset = TEXT_PRESETS[0], duration = 4): TextClip {
  return {
    id: uid('c'),
    type: 'text',
    start: r3(start),
    duration,
    text: 'Text',
    x: 0.5,
    y: 0.5,
    animationIn: 'fade',
    animationOut: 'fade',
    ...preset.clip,
    style: {
      fontFamily: 'Anek Tamil Variable',
      fontSize: 80,
      fontWeight: 700,
      color: '#ffffff',
      stroke: '#000000',
      strokeWidth: 0,
      background: '',
      align: 'center',
      width: 0.8,
      shadow: true,
      ...preset.clip.style,
    },
  }
}

// ---- edits (pure; return a new document) -------------------------------------------

export function mapClips(doc: TimelineDoc, fn: (c: Clip, t: Track) => Clip | null): TimelineDoc {
  return { ...doc, tracks: doc.tracks.map((t) => ({ ...t, clips: t.clips.map((c) => fn(c, t)).filter((c): c is Clip => c !== null) })) }
}

export function findClip(doc: TimelineDoc, id: string): { clip: Clip; track: Track } | undefined {
  for (const track of doc.tracks) {
    const clip = track.clips.find((c) => c.id === id)
    if (clip) return { clip, track }
  }
  return undefined
}

export function updateClip(doc: TimelineDoc, id: string, patch: Partial<Clip>): TimelineDoc {
  return mapClips(doc, (c) => (c.id === id ? ({ ...c, ...patch } as Clip) : c))
}

export function removeClips(doc: TimelineDoc, ids: Set<string>, ripple = false): TimelineDoc {
  if (!ripple) return mapClips(doc, (c) => (ids.has(c.id) ? null : c))
  return {
    ...doc,
    tracks: doc.tracks.map((t) => {
      const removed = t.clips.filter((c) => ids.has(c.id)).sort((a, b) => a.start - b.start)
      if (removed.length === 0) return t
      const kept = t.clips.filter((c) => !ids.has(c.id))
      return {
        ...t,
        clips: kept.map((c) => {
          const shift = removed.filter((r) => r.start < c.start).reduce((s, r) => s + r.duration, 0)
          return shift ? { ...c, start: r3(Math.max(0, c.start - shift)) } : c
        }),
      }
    }),
  }
}

/** Cuts a clip in two at `time`. Media clips keep playing from the right point in the source. */
export function splitClip(doc: TimelineDoc, id: string, time: number): TimelineDoc {
  const found = findClip(doc, id)
  if (!found) return doc
  const c = found.clip
  const offset = r3(time - c.start)
  if (offset < MIN_CLIP || c.duration - offset < MIN_CLIP) return doc
  const left: Clip = { ...c, duration: offset }
  const right: Clip =
    c.type === 'media'
      ? { ...c, id: uid('c'), start: r3(time), duration: r3(c.duration - offset), trimStart: r3(c.trimStart + offset * c.speed) }
      : { ...c, id: uid('c'), start: r3(time), duration: r3(c.duration - offset) }
  if (left.type === 'media' && right.type === 'media') {
    // fades and transitions belong to the outer edges only
    left.fadeOut = 0
    left.transitionOut = { ...left.transitionOut, type: 'none' }
    right.fadeIn = 0
    right.transitionIn = { ...right.transitionIn, type: 'none' }
  }
  return {
    ...doc,
    tracks: doc.tracks.map((t) => (t.id !== found.track.id ? t : { ...t, clips: t.clips.flatMap((x) => (x.id === id ? [left, right] : [x])) })),
  }
}

/** Trims the left edge: moves the start and, for media, skips into the source. */
export function trimLeft(clip: Clip, newStart: number, media?: MediaRef): Clip {
  const end = clip.start + clip.duration
  let start = Math.min(newStart, end - MIN_CLIP)
  start = Math.max(0, start)
  if (clip.type === 'media' && clip.kind !== 'IMAGE') {
    // can't reveal more than the beginning of the source
    const earliest = clip.start - clip.trimStart / clip.speed
    start = Math.max(start, earliest)
    return { ...clip, start: r3(start), duration: r3(end - start), trimStart: r3(Math.max(0, clip.trimStart + (start - clip.start) * clip.speed)) }
  }
  void media
  return { ...clip, start: r3(start), duration: r3(end - start) }
}

export function trimRight(clip: Clip, newEnd: number, media?: MediaRef): Clip {
  const duration = Math.min(Math.max(MIN_CLIP, newEnd - clip.start), maxDuration(clip, media))
  return { ...clip, duration: r3(duration) }
}

/** Changes speed and keeps the same part of the source (so the clip gets shorter or longer). */
export function setSpeed(clip: MediaClip, speed: number): MediaClip {
  const s = Math.min(4, Math.max(0.25, speed))
  return { ...clip, speed: s, duration: r3((clip.duration * clip.speed) / s) }
}

/**
 * Snap a time to nearby clip edges, the playhead or zero, if within `threshold` seconds.
 * Returns the snapped time and the edge it snapped to (for drawing a guide).
 */
export function snap(time: number, edges: number[], threshold: number): { time: number; edge?: number } {
  let best: number | undefined
  for (const e of edges) if (Math.abs(e - time) <= threshold && (best === undefined || Math.abs(e - time) < Math.abs(best - time))) best = e
  return best === undefined ? { time } : { time: best, edge: best }
}

export function snapEdges(doc: TimelineDoc, exclude: Set<string>, playhead: number): number[] {
  const edges = [0, playhead]
  for (const t of doc.tracks) for (const c of t.clips) if (!exclude.has(c.id)) edges.push(c.start, r3(c.start + c.duration))
  return edges
}

/**
 * Places a clip on a track without overlapping others: if it would overlap,
 * it goes to the nearest free gap at or after the requested time.
 */
export function freeStart(track: Track, clipId: string, start: number, duration: number): number {
  const others = track.clips.filter((c) => c.id !== clipId).sort((a, b) => a.start - b.start)
  let s = Math.max(0, start)
  for (const o of others) {
    const oEnd = o.start + o.duration
    if (s + duration <= o.start + 1e-6) break
    if (s < oEnd - 1e-6) s = oEnd
  }
  return r3(s)
}

export function canHold(track: Track, clip: Clip): boolean {
  if (clip.type === 'text') return track.kind === 'text'
  if (clip.type === 'scene') return track.kind === 'video'
  if (clip.kind === 'AUDIO') return track.kind === 'audio'
  return track.kind === 'video'
}

export function trackKindFor(clip: Clip): TrackKind {
  return clip.type === 'text' ? 'text' : clip.type === 'scene' ? 'video' : clip.kind === 'AUDIO' ? 'audio' : 'video'
}

/** Moves a clip to (track, start), avoiding overlaps. Refuses tracks of the wrong kind. */
export function moveClip(doc: TimelineDoc, id: string, trackId: string, start: number): TimelineDoc {
  const found = findClip(doc, id)
  const target = doc.tracks.find((t) => t.id === trackId)
  if (!found || !target || !canHold(target, found.clip) || target.locked) return doc
  const s = freeStart(target, id, start, found.clip.duration)
  const moved = { ...found.clip, start: s }
  return {
    ...doc,
    tracks: doc.tracks.map((t) => {
      let clips = t.clips.filter((c) => c.id !== id)
      if (t.id === trackId) clips = [...clips, moved].sort((a, b) => a.start - b.start)
      return { ...t, clips }
    }),
  }
}

/** Adds a clip at `start` on the first suitable unlocked track (creating one if needed). */
export function addClip(doc: TimelineDoc, clip: Clip, preferredTrackId?: string): TimelineDoc {
  const kind = trackKindFor(clip)
  let tracks = doc.tracks
  let target = tracks.find((t) => t.id === preferredTrackId && t.kind === kind && !t.locked) ?? tracks.find((t) => t.kind === kind && !t.locked)
  if (!target) {
    target = newTrack(kind, tracks.filter((t) => t.kind === kind).length + 1)
    tracks = kind === 'audio' ? [...tracks, target] : [target, ...tracks]
  }
  const placed = { ...clip, start: freeStart(target, clip.id, clip.start, clip.duration) }
  return { ...doc, tracks: tracks.map((t) => (t.id === target!.id ? { ...t, clips: [...t.clips, placed].sort((a, b) => a.start - b.start) } : t)) }
}

export function duplicateClip(doc: TimelineDoc, id: string): { doc: TimelineDoc; newId?: string } {
  const found = findClip(doc, id)
  if (!found) return { doc }
  const copy = { ...found.clip, id: uid('c'), start: r3(found.clip.start + found.clip.duration) }
  return { doc: addClip(doc, copy, found.track.id), newId: copy.id }
}

/** Subtitle cues become caption text clips on a new text track. */
export function captionsTrack(cues: { start: number; end: number; text: string }[], name: string): Track {
  const base = newTextClip(0, TEXT_PRESETS[2])
  return {
    ...newTrack('text'),
    name,
    clips: cues
      .filter((c) => c.text.trim() && c.end > c.start)
      .map((c) => ({ ...base, id: uid('c'), start: r3(c.start), duration: r3(c.end - c.start), text: c.text, caption: true })),
  }
}

/** Every media id used, so the server can sign links for exactly these files. */
export function mediaIds(doc: TimelineDoc): string[] {
  const ids = new Set<string>()
  for (const t of doc.tracks) for (const c of t.clips) if (c.type === 'media') ids.add(c.mediaId)
  return [...ids]
}

/** Opacity/volume multiplier for fades, given seconds into the clip. */
export function fadeEnvelope(t: number, duration: number, fadeIn: number, fadeOut: number): number {
  let v = 1
  if (fadeIn > 0 && t < fadeIn) v = Math.min(v, Math.max(0, t / fadeIn))
  if (fadeOut > 0 && t > duration - fadeOut) v = Math.min(v, Math.max(0, (duration - t) / fadeOut))
  return v
}

export function defaultProps(): CompositionProps {
  return { timeline: newTimeline(1920, 1080), media: {} }
}
