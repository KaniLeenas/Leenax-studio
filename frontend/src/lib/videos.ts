import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { MediaRef, TimelineDoc } from '../remotion/types'
import { api } from './api'
import type { Slide } from './slides'
import type { MediaAsset } from './types'

// ---- a plan (matches Videos.java) -----------------------------------------------------------------

export type VideoKind = 'LESSON' | 'QUIZ'
export type SceneKind = 'INTRO' | 'SLIDE' | 'OUTRO' | 'QUIZ_INTRO' | 'QUESTION' | 'SUMMARY'

export const SCENE_LABEL: Record<SceneKind, string> = {
  INTRO: 'Introduction', SLIDE: 'Slide', OUTRO: 'Ending', QUIZ_INTRO: 'Quiz introduction', QUESTION: 'Question', SUMMARY: 'Answers',
}

export interface VideoVoice {
  profileId?: string | null
  accountId?: string | null
  voiceId?: string | null
  model?: string | null
  speed?: number | null
  label?: string | null
}

export interface VideoQuestion {
  questionId?: string
  number?: number
  total?: number
  stem: string
  code?: { language?: string | null; text: string } | null
  options: string[]
  answer: number
  explanation?: string | null
}

export interface VideoScene {
  id: string
  kind: SceneKind
  title?: string | null
  lines?: string[] | null
  slide?: Slide | null
  question?: VideoQuestion | null
  keywords?: string[] | null
  narration?: string | null
  answerNarration?: string | null
  sizes?: Record<string, number> | null
}

export interface Narration {
  mediaId: string
  scriptHash: string
  segments: { sceneId: string; part: 'MAIN' | 'ANSWER'; start: number; duration: number }[]
  characters: number
  voiceLabel?: string | null
  madeAt?: string
}

export interface VideoPlan {
  kind: VideoKind
  topicId: string
  topicTitle?: string | null
  level?: string | null
  sourceVersionId?: string | null
  seoVersionId?: string | null
  title?: string | null
  language: 'EN' | 'TA'
  style: string
  aspect: '16:9' | '9:16'
  voice?: VideoVoice | null
  quiz?: { countdown: number } | null
  scenes: VideoScene[]
  narration?: Narration | null
  captions?: boolean | null
}

/** The characters that will be spoken: the narration and, for questions, the answers. */
export function characters(p: VideoPlan): number {
  return p.scenes.reduce((n, s) => n + (s.narration?.trim().length ?? 0) + (s.kind === 'QUESTION' ? (s.answerNarration?.trim().length ?? 0) : 0), 0)
}

/** Seconds of speech, roughly (about 14 characters a second in English, 11 in Tamil). */
export function speechSeconds(p: VideoPlan): number {
  return Math.round(characters(p) / (p.language === 'TA' ? 11 : 14))
}

export const minutes = (s: number) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`

// ---- server -------------------------------------------------------------------------------------

export interface VideoInfo {
  kind: VideoKind
  level?: string | null
  resourceId: string
  versionNo?: number | null
  status?: string | null
  /** CHECKED when this version has a render that passed its file checks; else the latest render's status. */
  renderStatus?: string | null
  running: boolean
  error?: string | null
}

export interface TopicVideos {
  topicId: string
  title: string
  depth: number
  approvedDecks: string[]
  mcqApproved: boolean
  seoApproved: boolean
  videos: VideoInfo[]
}

export interface VideosOverview {
  topicsApproved: boolean
  topics: TopicVideos[]
  canEdit: boolean
  rendererAvailable: boolean
}

export function useVideosOverview(projectId: string | undefined, poll = false) {
  return useQuery({
    queryKey: ['videos', projectId],
    queryFn: () => api.get<VideosOverview>(`/projects/${projectId}/videos`),
    enabled: !!projectId,
    refetchInterval: (q) => (poll || q.state.data?.topics.some((t) => t.videos.some((v) => v.running)) ? 4000 : false),
  })
}

export interface CreateVideo {
  kind: VideoKind
  topicId: string
  level?: string
  language: 'EN' | 'TA'
  aspect: '16:9' | '9:16'
  countdown?: number
  questions?: number
}

export function useCreateVideo(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateVideo) => api.post<{ resourceId: string }>(`/projects/${projectId}/videos`, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['videos', projectId] }),
  })
}

export function useNarrate(resourceId: string, projectId?: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (versionId: string) => api.post(`/videos/${resourceId}/narration`, { versionId }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['videos', projectId] }),
  })
}

export interface Validation {
  ok: boolean
  checkedAt?: string
  problems: string[]
  found: { width?: number; height?: number; durationSeconds?: number; fps?: number; frames?: number; sizeBytes?: number; audioCodec?: string; meanVolumeDb?: number }
  expected?: { width: number; height: number; durationSeconds: number; fps: number; audio: boolean }
}

export interface VideoRender {
  renderJobId: string
  versionId: string
  versionNo?: number | null
  forThisContent: boolean
  status: 'QUEUED' | 'RENDERING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  progress: number
  error?: string | null
  output?: MediaAsset | null
  validation?: Validation | null
  timelineId?: string | null
  createdAt: string
  completedAt?: string | null
}

export function useVideoRenders(resourceId: string, versionId?: string) {
  return useQuery({
    queryKey: ['video-renders', resourceId, versionId],
    queryFn: () => api.get<VideoRender[]>(`/videos/${resourceId}/renders${versionId ? `?versionId=${versionId}` : ''}`),
    enabled: !!resourceId,
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'QUEUED' || r.status === 'RENDERING') ? 3000 : false),
  })
}

export function useStartRender(resourceId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { versionId: string; resolution: number; quality?: 'DRAFT' | 'STANDARD' | 'HIGH' }) => api.post<VideoRender>(`/videos/${resourceId}/renders`, v),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['video-renders', resourceId] })
      qc.invalidateQueries({ queryKey: ['reviews'] })
    },
  })
}

export interface VideoPreview {
  document: TimelineDoc
  durationSeconds: number
  media: Record<string, MediaAsset>
}

export function useVideoPreview(resourceId: string, versionId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['video-preview', resourceId, versionId],
    queryFn: () => api.get<VideoPreview>(`/videos/${resourceId}/preview?versionId=${versionId}`),
    enabled: !!versionId && enabled,
    retry: false,
  })
}

export const mediaRefs = (m: Record<string, MediaAsset>): Record<string, MediaRef> =>
  Object.fromEntries(Object.values(m).map((a) => [a.id, { id: a.id, kind: a.kind as MediaRef['kind'], url: a.url, durationMs: a.durationMs, width: a.width, height: a.height, filename: a.filename }]))
