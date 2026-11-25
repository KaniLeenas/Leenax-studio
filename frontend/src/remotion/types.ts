/**
 * The editor's timeline document. Shared by the browser preview (Remotion Player)
 * and the server renderer, so what you see is exactly what is exported.
 * Times are in seconds; the composition converts them to frames.
 */

export type TrackKind = 'video' | 'audio' | 'text'

export type TransitionType = 'none' | 'fade' | 'slide-left' | 'slide-right' | 'slide-up' | 'zoom' | 'wipe' | 'blur'
export type TextAnimation = 'none' | 'fade' | 'slide-up' | 'pop' | 'typewriter'
export type Motion = 'none' | 'zoom-in' | 'zoom-out' | 'pan-left' | 'pan-right'
export type Fit = 'cover' | 'contain'

export interface Transition {
  type: TransitionType
  duration: number
}

export interface Filters {
  /** 1 = unchanged */
  brightness: number
  contrast: number
  saturation: number
  /** px at design size */
  blur: number
}

interface ClipBase {
  id: string
  /** Position on the timeline. */
  start: number
  /** Length on the timeline (already accounts for speed). */
  duration: number
}

/** Video, image or audio from the media library. */
export interface MediaClip extends ClipBase {
  type: 'media'
  mediaId: string
  kind: 'VIDEO' | 'IMAGE' | 'AUDIO'
  /** Seconds skipped at the start of the source file. */
  trimStart: number
  /** 0.25–4. A 10 s source at speed 2 lasts 5 s on the timeline. */
  speed: number
  /** 0–2 (1 = original). */
  volume: number
  fadeIn: number
  fadeOut: number
  // picture (video and image only)
  fit: Fit
  /** Centre offset as a fraction of the frame (−1…1). */
  x: number
  y: number
  scale: number
  rotation: number
  opacity: number
  filters: Filters
  motion: Motion
  transitionIn: Transition
  transitionOut: Transition
}

export interface TextStyle {
  fontFamily: string
  fontSize: number
  fontWeight: 400 | 700 | 800
  color: string
  stroke: string
  strokeWidth: number
  background: string
  align: 'left' | 'center' | 'right'
  /** Box width as a fraction of the frame width. */
  width: number
  shadow: boolean
}

export interface TextClip extends ClipBase {
  type: 'text'
  text: string
  style: TextStyle
  /** Centre of the text box as a fraction of the frame (0…1). */
  x: number
  y: number
  animationIn: TextAnimation
  animationOut: TextAnimation
  /** Set for clips made from a subtitle track, so they can be restyled together. */
  caption?: boolean
}

/**
 * A lesson or quiz scene (E8), drawn by the video templates (SceneLayer). Made by the server
 * from an approved plan; times inside it are seconds from the scene's start.
 */
export interface SceneClip extends ClipBase {
  type: 'scene'
  scene: SceneData
}

export interface SceneQuestion {
  number?: number
  total?: number
  stem: string
  code?: { language?: string | null; text: string } | null
  options: string[]
  answer: number
  explanation?: string | null
}

export interface SceneData {
  kind: 'INTRO' | 'SLIDE' | 'OUTRO' | 'QUIZ_INTRO' | 'QUESTION' | 'SUMMARY'
  style: string
  aspect: '16:9' | '9:16'
  language?: string
  captions?: boolean
  title?: string | null
  lines?: string[] | null
  /** A presentation slide (the shape of remotion/slideDesign.ts Slide), with its diagram's drawing as figure.svg. */
  slide?: import('./slideDesign').Slide & { figure?: { kind: 'MERMAID' | 'MATH'; source: string; svg?: string } | null }
  imageMediaId?: string
  sizes?: Record<string, number> | null
  question?: SceneQuestion | null
  keywords?: string[] | null
  speakStart?: number
  speakDuration?: number
  countdownStart?: number
  countdown?: number
  revealAt?: number
  answerStart?: number
  answerDuration?: number
  number?: number
  total?: number
}

export type Clip = MediaClip | TextClip | SceneClip

export interface Track {
  id: string
  kind: TrackKind
  name: string
  muted: boolean
  hidden: boolean
  locked: boolean
  volume: number
  clips: Clip[]
}

export interface TimelineDoc {
  version: 1
  width: number
  height: number
  fps: number
  background: string
  /** Index 0 is the top track and is drawn in front. */
  tracks: Track[]
}

/** What the composition needs to know about each media file. */
export interface MediaRef {
  id: string
  kind: 'VIDEO' | 'IMAGE' | 'AUDIO'
  url: string
  durationMs?: number
  width?: number
  height?: number
  filename?: string
}

export interface CompositionProps extends Record<string, unknown> {
  timeline: TimelineDoc
  media: Record<string, MediaRef>
  /**
   * Export size. The timeline is laid out at its own size and scaled to this,
   * so a 1080p timeline exports sharply at 720p or 4K. Defaults to the timeline size.
   */
  output?: { width: number; height: number }
}
