import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, readCookie, uploadFile } from './api'
import type {
  AudioOperation,
  Cue,
  ImageToolsStatus,
  Language,
  MediaAsset,
  PronunciationRule,
  SpeechResult,
  SubtitleTrack,
  ThumbnailDesign,
  ThumbnailDocument,
  ThumbnailSummary,
  TrackSummary,
  VoiceChoice,
  VoiceOption,
  VoiceProfile,
  VoiceStyle,
} from './types'

export const studioKeys = {
  voices: (accountId: string) => ['voice', 'voices', accountId] as const,
  profiles: ['voice', 'profiles'] as const,
  rules: ['voice', 'rules'] as const,
  consent: ['voice', 'consent'] as const,
  tracks: (mediaId: string) => ['subtitles', 'list', mediaId] as const,
  track: (id: string) => ['subtitles', 'track', id] as const,
  imageTools: ['images', 'tools'] as const,
  designs: (templates: boolean) => ['thumbnails', templates ? 'templates' : 'designs'] as const,
  design: (id: string) => ['thumbnails', 'design', id] as const,
}

/** New files land in the media library and dashboard counts. */
function useInvalidateMedia() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['media'] })
    qc.invalidateQueries({ queryKey: ['dashboard'] })
    qc.invalidateQueries({ queryKey: ['ai', 'accounts'] })
    qc.invalidateQueries({ queryKey: ['ai', 'usage'] })
  }
}

// ---- voice ---------------------------------------------------------------

export function useVoices(accountId?: string) {
  return useQuery({
    queryKey: studioKeys.voices(accountId ?? ''),
    queryFn: () => api.get<VoiceOption[]>(`/voice/voices?accountId=${accountId}`),
    enabled: !!accountId,
    staleTime: 10 * 60_000,
    retry: false,
  })
}

export function useVoiceProfiles() {
  return useQuery({ queryKey: studioKeys.profiles, queryFn: () => api.get<VoiceProfile[]>('/voice/profiles') })
}

export interface NarrationInput {
  voice: VoiceChoice
  text: string
  language?: Language
  style?: VoiceStyle
  title?: string
  projectId?: string
}

export function useNarrate() {
  const invalidate = useInvalidateMedia()
  return useMutation({ mutationFn: (body: NarrationInput) => api.post<SpeechResult>('/voice/narrations', body), onSuccess: invalidate })
}

export interface DialogueInput {
  script: string
  speakers: { name: string; profileId: string }[]
  language?: Language
  gapSeconds?: number
  title?: string
  projectId?: string
}

export function useDialogue() {
  const invalidate = useInvalidateMedia()
  return useMutation({ mutationFn: (body: DialogueInput) => api.post<SpeechResult>('/voice/dialogues', body), onSuccess: invalidate })
}

/** Returns an object URL for a short MP3 that is not saved. The caller revokes it. */
export async function previewVoice(body: { voice: VoiceChoice; text: string; language?: Language; style?: VoiceStyle }): Promise<string> {
  const token = readCookie('XSRF-TOKEN')
  const res = await fetch('/api/v1/voice/preview', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'audio/mpeg, application/json', ...(token ? { 'X-XSRF-TOKEN': token } : {}) },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `Preview failed (${res.status})`
    let code = 'ERROR'
    try {
      const err = await res.json()
      message = err.message ?? message
      code = err.code ?? code
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, code, message)
  }
  return URL.createObjectURL(await res.blob())
}

export function useSaveProfile() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id?: string; accountId?: string; voiceId?: string; name?: string; language?: Language; model?: string; style?: VoiceStyle; shared?: boolean }) =>
      id ? api.patch<VoiceProfile>(`/voice/profiles/${id}`, body) : api.post<VoiceProfile>('/voice/profiles', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: studioKeys.profiles }),
  })
}

export function useDeleteProfile() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete(`/voice/profiles/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: studioKeys.profiles })
      qc.invalidateQueries({ queryKey: ['voice', 'voices'] })
    },
  })
}

export function useConsentStatement() {
  return useQuery({ queryKey: studioKeys.consent, queryFn: () => api.get<{ statement: string }>('/voice/clone/consent'), staleTime: Infinity })
}

export interface CloneInput {
  accountId: string
  name: string
  description?: string
  language?: Language
  sampleMediaIds: string[]
  consentOwnVoice: boolean
  consentStatement: string
  removeBackgroundNoise: boolean
}

export function useCloneVoice() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CloneInput) => api.post<VoiceProfile>('/voice/clone', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: studioKeys.profiles })
      qc.invalidateQueries({ queryKey: ['voice', 'voices'] })
    },
  })
}

export function useRules() {
  return useQuery({ queryKey: studioKeys.rules, queryFn: () => api.get<PronunciationRule[]>('/voice/pronunciations') })
}

export function useSaveRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id?: string; term: string; replacement: string; language?: Language }) =>
      id ? api.put<PronunciationRule>(`/voice/pronunciations/${id}`, body) : api.post<PronunciationRule>('/voice/pronunciations', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: studioKeys.rules }),
  })
}

export function useDeleteRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete(`/voice/pronunciations/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: studioKeys.rules }),
  })
}

// ---- audio tools -----------------------------------------------------------

type AudioResult = { media: MediaAsset }

export function useAudioTool() {
  const invalidate = useInvalidateMedia()
  return useMutation({
    mutationFn: (
      a:
        | { tool: 'cleanup'; mediaId: string; operations: AudioOperation[] }
        | { tool: 'speed-pitch'; mediaId: string; speed: number; semitones: number }
        | { tool: 'mix'; voiceMediaId: string; musicMediaId: string; musicVolumeDb: number; duck: boolean; fadeSeconds: number; title?: string }
        | { tool: 'extract'; videoMediaId: string }
        | { tool: 'sound-effects'; accountId: string; prompt: string; durationSeconds?: number; loop: boolean },
    ) => {
      const { tool, ...body } = a
      return api.post<AudioResult>(`/audio/${tool}`, body)
    },
    onSuccess: invalidate,
  })
}

export function uploadRecording(file: File, onProgress: (f: number) => void) {
  return uploadFile<MediaAsset>('/audio/recordings', file, onProgress)
}

// ---- subtitles -------------------------------------------------------------

export function useTracks(mediaId?: string) {
  return useQuery({
    queryKey: studioKeys.tracks(mediaId ?? ''),
    queryFn: () => api.get<TrackSummary[]>(`/subtitles?mediaId=${mediaId}`),
    enabled: !!mediaId,
  })
}

export function useTrack(id?: string) {
  return useQuery({ queryKey: studioKeys.track(id ?? ''), queryFn: () => api.get<SubtitleTrack>(`/subtitles/${id}`), enabled: !!id })
}

function useInvalidateTracks() {
  const qc = useQueryClient()
  return (t?: SubtitleTrack) => {
    qc.invalidateQueries({ queryKey: ['subtitles', 'list'] })
    if (t) qc.setQueryData(studioKeys.track(t.id), t)
    qc.invalidateQueries({ queryKey: ['ai', 'usage'] })
  }
}

export function useTranscribe() {
  const invalidate = useInvalidateTracks()
  return useMutation({
    mutationFn: (body: { mediaId: string; accountId: string; language?: string; maxLineChars?: number }) =>
      api.post<SubtitleTrack>('/subtitles/transcribe', body),
    onSuccess: invalidate,
  })
}

export function useTranslateTrack() {
  const invalidate = useInvalidateTracks()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; accountId: string; model: string; targetLanguage: string }) =>
      api.post<SubtitleTrack>(`/subtitles/${id}/translate`, body),
    onSuccess: invalidate,
  })
}

export function useCreateTrack() {
  const invalidate = useInvalidateTracks()
  return useMutation({
    mutationFn: (body: { mediaId: string; language: string; label?: string; cues?: Cue[] }) => api.post<SubtitleTrack>('/subtitles', body),
    onSuccess: invalidate,
  })
}

export function useSaveTrack() {
  const invalidate = useInvalidateTracks()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; label?: string; language?: string; cues?: Cue[] }) =>
      api.patch<SubtitleTrack>(`/subtitles/${id}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteTrack() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete(`/subtitles/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['subtitles', 'list'] }),
  })
}

export const trackExportUrl = (id: string, format: 'srt' | 'vtt', inline = false) =>
  `/api/v1/subtitles/${id}/export?format=${format}${inline ? '&inline=true' : ''}`

// ---- images ----------------------------------------------------------------

export interface ImageInput {
  accountId: string
  model: string
  prompt: string
  size?: string
  aspectRatio?: string
  imageSize?: string
  count?: number
  quality?: string
  transparent: boolean
  references?: string[]
  projectId?: string
}

export function useGenerateImages() {
  const invalidate = useInvalidateMedia()
  return useMutation({
    mutationFn: (body: ImageInput) => api.post<{ images: MediaAsset[] }>('/images/generate', body),
    onSuccess: invalidate,
  })
}

export function useImageTools() {
  return useQuery({ queryKey: studioKeys.imageTools, queryFn: () => api.get<ImageToolsStatus>('/images/tools'), staleTime: 5 * 60_000 })
}

export function useImageTool() {
  const invalidate = useInvalidateMedia()
  return useMutation({
    mutationFn: (
      a:
        | { tool: 'remove-background'; mediaId: string }
        | { tool: 'resize'; mediaId: string; width: number; height: number; fit: 'COVER' | 'CONTAIN'; background?: string; format: 'png' | 'jpg' },
    ) => {
      const { tool, ...body } = a
      return api.post<{ images: MediaAsset[] }>(`/images/${tool}`, body)
    },
    onSuccess: invalidate,
  })
}

/** Same-origin URL for canvas use (cross-origin images would block export). */
export const mediaContentUrl = (id: string) => `/api/v1/media/${id}/content`

// ---- thumbnails ------------------------------------------------------------

export function useDesigns(templates = false) {
  return useQuery({ queryKey: studioKeys.designs(templates), queryFn: () => api.get<ThumbnailSummary[]>(`/thumbnails?templates=${templates}`) })
}

export function useDesign(id?: string) {
  return useQuery({ queryKey: studioKeys.design(id ?? ''), queryFn: () => api.get<ThumbnailDesign>(`/thumbnails/${id}`), enabled: !!id })
}

function useInvalidateDesigns() {
  const qc = useQueryClient()
  return (d?: ThumbnailDesign) => {
    qc.invalidateQueries({ queryKey: ['thumbnails', 'designs'] })
    qc.invalidateQueries({ queryKey: ['thumbnails', 'templates'] })
    if (d) qc.setQueryData(studioKeys.design(d.id), d)
  }
}

export interface DesignInput {
  name: string
  width: number
  height: number
  document: ThumbnailDocument
  template: boolean
  projectId?: string
}

export function useCreateDesign() {
  const invalidate = useInvalidateDesigns()
  return useMutation({ mutationFn: (body: DesignInput) => api.post<ThumbnailDesign>('/thumbnails', body), onSuccess: invalidate })
}

export function useUpdateDesign() {
  const invalidate = useInvalidateDesigns()
  return useMutation({
    mutationFn: ({ id, ...body }: Partial<DesignInput> & { id: string }) => api.patch<ThumbnailDesign>(`/thumbnails/${id}`, body),
    onSuccess: invalidate,
  })
}

export function useDuplicateDesign() {
  const invalidate = useInvalidateDesigns()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; name?: string; template?: boolean }) =>
      api.post<ThumbnailDesign>(`/thumbnails/${id}/duplicate`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteDesign() {
  const invalidate = useInvalidateDesigns()
  return useMutation({ mutationFn: (id: string) => api.delete(`/thumbnails/${id}`), onSuccess: () => invalidate() })
}

export function useExportDesign() {
  const invalidate = useInvalidateDesigns()
  const invalidateMedia = useInvalidateMedia()
  return useMutation({
    mutationFn: ({ id, blob, filename }: { id: string; blob: Blob; filename: string }) =>
      uploadFile<ThumbnailDesign>(`/thumbnails/${id}/export`, new File([blob], filename, { type: blob.type }), () => {}),
    onSuccess: (d) => {
      invalidate(d)
      invalidateMedia()
    },
  })
}
