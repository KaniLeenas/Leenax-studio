import type { AiModelInfo, JobStatus, Language, MediaAsset, VideoFormat } from './types'
import { durationChoices } from './generation'

// ---- scripts ---------------------------------------------------------------------------------

export type VideoType =
  | 'YOUTUBE_LONG'
  | 'YOUTUBE_SHORT'
  | 'REEL'
  | 'TIKTOK'
  | 'TUTORIAL'
  | 'VLOG'
  | 'DOCUMENTARY'
  | 'CINEMATIC'
  | 'ENTERTAINMENT'
  | 'OTHER'

export const VIDEO_TYPES: { id: VideoType; label: string; minutes: number }[] = [
  { id: 'YOUTUBE_LONG', label: 'YouTube video', minutes: 10 },
  { id: 'YOUTUBE_SHORT', label: 'YouTube Short', minutes: 1 },
  { id: 'REEL', label: 'Instagram or Facebook Reel', minutes: 1 },
  { id: 'TIKTOK', label: 'TikTok', minutes: 1 },
  { id: 'TUTORIAL', label: 'Tutorial', minutes: 6 },
  { id: 'VLOG', label: 'Vlog', minutes: 8 },
  { id: 'DOCUMENTARY', label: 'Documentary', minutes: 15 },
  { id: 'CINEMATIC', label: 'Cinematic story', minutes: 3 },
  { id: 'ENTERTAINMENT', label: 'Entertainment', minutes: 5 },
  { id: 'OTHER', label: 'Something else', minutes: 3 },
]

export const videoTypeLabel = (id?: string) => VIDEO_TYPES.find((t) => t.id === id)?.label ?? 'Video'

export interface Writer {
  accountId: string
  model: string
}

export interface Idea {
  title: string
  hook: string
  angle: string
}

export interface PlatformText {
  title?: string
  description?: string
  caption?: string
  hashtags?: string[]
}

export interface Script {
  id: string
  projectId?: string
  parentId?: string
  title: string
  language: Language
  videoType: VideoType
  idea?: string
  body: string
  sections: Record<string, unknown>
  metadata: Record<string, PlatformText>
  versionNo: number
  wordCount: number
  spokenMinutes: number
  createdAt: string
  updatedAt: string
}

/** Platforms the post-text writer knows (the publisher's platform codes). */
export const TEXT_PLATFORMS: { id: string; label: string }[] = [
  { id: 'YOUTUBE', label: 'YouTube' },
  { id: 'INSTAGRAM', label: 'Instagram' },
  { id: 'TIKTOK', label: 'TikTok' },
  { id: 'FACEBOOK', label: 'Facebook' },
  { id: 'LINKEDIN', label: 'LinkedIn' },
  { id: 'X', label: 'X' },
  { id: 'PINTEREST', label: 'Pinterest' },
  { id: 'THREADS', label: 'Threads' },
  { id: 'REDDIT', label: 'Reddit' },
  { id: 'TELEGRAM', label: 'Telegram' },
  { id: 'SNAPCHAT', label: 'Snapchat' },
  { id: 'WHATSAPP', label: 'WhatsApp Channels' },
]

/** The words a narrator would say: drops [INTRO]-style markers and (Visual: …) notes. */
export function spokenText(body: string): string {
  return body
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !/^\[[^\]]*\]$/.test(l) && !/^\(.*\)$/.test(l))
    .join(' ')
}

/** Speaking pace matches the server: 150 words a minute in English, 110 in Tamil. */
export function spokenMinutes(body: string, language: Language): number {
  const words = spokenText(body).split(/\s+/).filter(Boolean).length
  return words / (language === 'TA' ? 110 : 150)
}

export function formatMinutes(min: number): string {
  if (min <= 0) return '0 s'
  const s = Math.round(min * 60)
  if (s < 60) return `${s} s`
  return `${Math.floor(s / 60)} min${s % 60 ? ` ${s % 60} s` : ''}`
}

/** Post text with hashtags, ready to paste or prefill in the publisher. */
export function postText(t?: PlatformText): string {
  if (!t) return ''
  const body = [t.caption || t.description || ''].filter(Boolean).join('\n\n')
  const tags = (t.hashtags ?? []).map((h) => `#${h}`).join(' ')
  return [body, tags].filter(Boolean).join('\n\n')
}

// ---- characters --------------------------------------------------------------------------------

export interface Character {
  id: string
  projectId?: string
  name: string
  description?: string
  appearance?: string
  clothing?: string
  voiceProfileId?: string
  voiceName?: string
  references: MediaAsset[]
  createdAt: string
  updatedAt: string
}

export interface CharacterInput {
  name: string
  description?: string
  appearance?: string
  clothing?: string
  voiceProfileId?: string
  projectId?: string
  referenceMediaIds: string[]
}

// ---- storyboards ---------------------------------------------------------------------------------

export const ANGLES: { id: string; label: string }[] = [
  { id: 'WIDE', label: 'Wide' },
  { id: 'MEDIUM', label: 'Medium' },
  { id: 'CLOSE_UP', label: 'Close-up' },
  { id: 'EXTREME_CLOSE_UP', label: 'Extreme close-up' },
  { id: 'OVER_THE_SHOULDER', label: 'Over the shoulder' },
  { id: 'LOW_ANGLE', label: 'Low angle' },
  { id: 'HIGH_ANGLE', label: 'High angle' },
  { id: 'AERIAL', label: 'Aerial' },
  { id: 'POV', label: 'Point of view' },
  { id: 'TOP_DOWN', label: 'Top-down' },
]

export const MOVES: { id: string; label: string }[] = [
  { id: 'STATIC', label: 'Still' },
  { id: 'PAN', label: 'Pan' },
  { id: 'TILT', label: 'Tilt' },
  { id: 'DOLLY_IN', label: 'Push in' },
  { id: 'DOLLY_OUT', label: 'Pull out' },
  { id: 'TRACKING', label: 'Tracking' },
  { id: 'HANDHELD', label: 'Handheld' },
  { id: 'CRANE', label: 'Crane' },
  { id: 'ORBIT', label: 'Orbit' },
  { id: 'ZOOM_IN', label: 'Zoom in' },
  { id: 'ZOOM_OUT', label: 'Zoom out' },
]

export const angleLabel = (id?: string) => ANGLES.find((a) => a.id === id)?.label
export const moveLabel = (id?: string) => MOVES.find((a) => a.id === id)?.label

export interface DialogueLine {
  character: string
  line: string
}

export interface SceneVideoJob {
  id: string
  status: JobStatus
  progress?: number
  errorMessage?: string
  provider: string
  model: string
}

export interface Scene {
  id: string
  position: number
  title: string
  description: string
  narration: string
  dialogue: DialogueLine[]
  durationSeconds: number
  cameraAngle?: string
  cameraMovement?: string
  lighting?: string
  composition?: string
  soundNotes?: string
  characterIds: string[]
  imagePrompt?: string
  videoPrompt?: string
  suggestedImagePrompt: string
  suggestedVideoPrompt: string
  image?: MediaAsset
  video?: MediaAsset
  dialogueAudio?: MediaAsset
  videoJob?: SceneVideoJob
  references: MediaAsset[]
}

export interface Storyboard {
  id: string
  projectId?: string
  scriptId?: string
  title: string
  aspectRatio: VideoFormat
  language: Language
  style?: string
  narration?: MediaAsset
  narrationParts: { sceneId: string; start: number; length: number }[]
  timelineId?: string
  totalSeconds: number
  scenes: Scene[]
  createdAt: string
  updatedAt: string
}

export interface StoryboardSummary {
  id: string
  projectId?: string
  scriptId?: string
  title: string
  aspectRatio: VideoFormat
  language: Language
  sceneCount: number
  imagesReady: number
  videosReady: number
  totalSeconds: number
  cover?: MediaAsset
  timelineId?: string
  updatedAt: string
}

/** Fields of a scene the editor sends; only those present change. */
export interface SceneInput {
  title?: string
  description?: string
  narration?: string
  dialogue?: DialogueLine[]
  durationSeconds?: number
  cameraAngle?: string
  cameraMovement?: string
  lighting?: string
  composition?: string
  soundNotes?: string
  characterIds?: string[]
  imagePrompt?: string
  videoPrompt?: string
  referenceMediaIds?: string[]
  imageMediaId?: string
  videoMediaId?: string
}

export interface ExportResult {
  timelineId: string
  totalSeconds: number
  notes: string[]
}

/** "Name: line" per row ↔ dialogue lines. Rows without a name are skipped. */
export function parseDialogue(text: string): DialogueLine[] {
  const out: DialogueLine[] = []
  for (const raw of text.split('\n')) {
    const m = raw.match(/^\s*([^:]{1,40}):\s*(.+)$/)
    if (m && m[1].trim() && m[2].trim()) out.push({ character: m[1].trim(), line: m[2].trim() })
  }
  return out
}

export const dialogueText = (d: DialogueLine[]) => d.map((l) => `${l.character}: ${l.line}`).join('\n')

export const isVideoRunning = (j?: SceneVideoJob) => !!j && (j.status === 'QUEUED' || j.status === 'PROCESSING')

/** The closest length the video model accepts, so a 7.5 s scene asks for 8 s rather than failing. */
export function nearestDuration(model: AiModelInfo | undefined, seconds: number): number | undefined {
  const choices = durationChoices(model)
  if (choices.length === 0) return undefined
  // Ties go to the longer length, so narration isn't cut short.
  return choices.reduce((best, c) => (Math.abs(c - seconds) <= Math.abs(best - seconds) ? c : best), choices[0])
}

export interface BoardProgress {
  scenes: number
  images: number
  videos: number
  running: number
  failed: number
  voiced: boolean
}

export function boardProgress(b: Storyboard): BoardProgress {
  return {
    scenes: b.scenes.length,
    images: b.scenes.filter((s) => s.image).length,
    videos: b.scenes.filter((s) => s.video).length,
    running: b.scenes.filter((s) => isVideoRunning(s.videoJob)).length,
    failed: b.scenes.filter((s) => s.videoJob?.status === 'FAILED' && !s.video).length,
    voiced: !!b.narration,
  }
}

export function formatSeconds(s: number): string {
  const r = Math.round(s)
  if (r < 60) return `${r} s`
  return `${Math.floor(r / 60)}:${String(r % 60).padStart(2, '0')}`
}

export const FORMATS: { id: VideoFormat; label: string }[] = [
  { id: 'LANDSCAPE', label: 'Landscape 16:9' },
  { id: 'PORTRAIT', label: 'Vertical 9:16' },
  { id: 'SQUARE', label: 'Square 1:1' },
]

export const FORMAT_LABEL: Record<VideoFormat, string> = { LANDSCAPE: '16:9', PORTRAIT: '9:16', SQUARE: '1:1' }
export const FORMAT_CLASS: Record<VideoFormat, string> = { LANDSCAPE: 'aspect-video', PORTRAIT: 'aspect-[9/16]', SQUARE: 'aspect-square' }

// ---- project workflow ----------------------------------------------------------------------------

export interface WorkflowInput {
  scripts: number
  storyboards: StoryboardSummary[]
  characters: number
  timelines: number
  renders: number
  thumbnails: number
  posts: number
  published: number
}

export interface WorkflowStep {
  id: string
  label: string
  done: boolean
  detail: string
  to: string
}

/** Where a project stands along idea → script → storyboard → … → publish, with where to go next. */
export function workflowSteps(projectId: string, w: WorkflowInput): WorkflowStep[] {
  const scenes = w.storyboards.reduce((n, b) => n + b.sceneCount, 0)
  const images = w.storyboards.reduce((n, b) => n + b.imagesReady, 0)
  const videos = w.storyboards.reduce((n, b) => n + b.videosReady, 0)
  const exported = w.storyboards.some((b) => b.timelineId) || w.timelines > 0
  const q = `?project=${projectId}`
  const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`
  return [
    { id: 'script', label: 'Idea and script', done: w.scripts > 0, detail: w.scripts ? plural(w.scripts, 'script') : 'Find an idea and write it', to: `/create/scripts${q}` },
    { id: 'characters', label: 'Characters', done: w.characters > 0, detail: w.characters ? plural(w.characters, 'character') : 'Optional: keep people looking the same', to: `/create/characters${q}` },
    { id: 'storyboard', label: 'Storyboard', done: scenes > 0, detail: scenes ? plural(scenes, 'scene') : 'Split the script into scenes', to: `/create/storyboard${q}` },
    { id: 'visuals', label: 'Scene images and video', done: scenes > 0 && images + videos > 0, detail: scenes ? `${images} of ${plural(scenes, 'image')} · ${plural(videos, 'video')}` : 'After the storyboard', to: `/create/storyboard${q}` },
    { id: 'edit', label: 'Edit', done: exported, detail: exported ? plural(w.timelines, 'timeline') : 'Send the storyboard to the editor', to: '/edit/timeline' },
    { id: 'render', label: 'Render', done: w.renders > 0, detail: w.renders ? plural(w.renders, 'finished video') : 'Export from the editor', to: '/edit/timeline' },
    { id: 'thumbnail', label: 'Thumbnail', done: w.thumbnails > 0, detail: w.thumbnails ? plural(w.thumbnails, 'design') : 'Design one in Thumbnail studio', to: '/create/thumbnails' },
    { id: 'publish', label: 'Publish', done: w.published > 0, detail: w.posts ? `${w.published} of ${plural(w.posts, 'post')} published` : 'Post or schedule it', to: '/publish/new' },
  ]
}
