import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, request } from './api'
import type {
  Character,
  CharacterInput,
  ExportResult,
  Idea,
  SceneInput,
  Script,
  Storyboard,
  StoryboardSummary,
  VideoType,
  Writer,
} from './story'
import { isVideoRunning } from './story'
import type { GenerationMode, Language, VideoFormat, VoiceChoice, VoiceStyle } from './types'

export const storyKeys = {
  scripts: (projectId?: string) => ['scripts', 'list', projectId ?? 'all'] as const,
  script: (id: string) => ['scripts', 'one', id] as const,
  characters: (projectId?: string) => ['characters', projectId ?? 'all'] as const,
  boards: (projectId?: string) => ['storyboards', 'list', projectId ?? 'all'] as const,
  board: (id: string) => ['storyboards', 'one', id] as const,
}

const withProject = (path: string, projectId?: string) => (projectId ? `${path}?projectId=${projectId}` : path)

// ---- scripts --------------------------------------------------------------------------------

export function useScripts(projectId?: string) {
  return useQuery({ queryKey: storyKeys.scripts(projectId), queryFn: () => api.get<Script[]>(withProject('/scripts', projectId)) })
}

export function useScript(id?: string) {
  return useQuery({ queryKey: storyKeys.script(id ?? ''), queryFn: () => api.get<Script>(`/scripts/${id}`), enabled: !!id })
}

export interface IdeasInput {
  writer: Writer
  topic: string
  videoType: VideoType
  language: Language
  count: number
  audience?: string
}

export function useIdeas() {
  return useMutation({ mutationFn: (body: IdeasInput) => api.post<{ ideas: Idea[] }>('/scripts/ideas', body).then((r) => r.ideas) })
}

export interface WriteInput {
  writer: Writer
  projectId?: string
  title?: string
  idea: string
  videoType: VideoType
  language: Language
  minutes: number
  tone?: string
  notes?: string
}

function useScriptSaved() {
  const qc = useQueryClient()
  return (s: Script) => {
    qc.setQueryData(storyKeys.script(s.id), s)
    qc.invalidateQueries({ queryKey: ['scripts', 'list'] })
  }
}

export function useWriteScript() {
  const saved = useScriptSaved()
  return useMutation({ mutationFn: (body: WriteInput) => api.post<Script>('/scripts/write', body), onSuccess: saved })
}

export interface ScriptInput {
  title: string
  idea?: string
  body: string
  language: Language
  videoType: VideoType
  projectId?: string
}

export function useCreateScript() {
  const saved = useScriptSaved()
  return useMutation({ mutationFn: (body: ScriptInput) => api.post<Script>('/scripts', body), onSuccess: saved })
}

export function useUpdateScript() {
  const saved = useScriptSaved()
  return useMutation({ mutationFn: ({ id, ...body }: ScriptInput & { id: string }) => api.put<Script>(`/scripts/${id}`, body), onSuccess: saved })
}

export function useRewriteScript() {
  const saved = useScriptSaved()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; writer: Writer; instruction: string }) => api.post<Script>(`/scripts/${id}/rewrite`, body),
    onSuccess: saved,
  })
}

export function useScriptMetadata() {
  const saved = useScriptSaved()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; writer: Writer; platforms: string[] }) => api.post<Script>(`/scripts/${id}/metadata`, body),
    onSuccess: saved,
  })
}

export function useDeleteScript() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: (id: string) => api.delete(`/scripts/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['scripts'] }) })
}

// ---- characters --------------------------------------------------------------------------------

export function useCharacters(projectId?: string) {
  return useQuery({ queryKey: storyKeys.characters(projectId), queryFn: () => api.get<Character[]>(withProject('/characters', projectId)) })
}

export function useSaveCharacter() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: CharacterInput & { id?: string }) => (id ? api.put<Character>(`/characters/${id}`, body) : api.post<Character>('/characters', body)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['characters'] }),
  })
}

export function useDeleteCharacter() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: (id: string) => api.delete(`/characters/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['characters'] }) })
}

// ---- storyboards ---------------------------------------------------------------------------------

export function useStoryboards(projectId?: string) {
  return useQuery({ queryKey: storyKeys.boards(projectId), queryFn: () => api.get<StoryboardSummary[]>(withProject('/storyboards', projectId)) })
}

/** Polls every 5 s while a scene video is being made; the server links it when it finishes. */
export function useStoryboard(id?: string) {
  return useQuery({
    queryKey: storyKeys.board(id ?? ''),
    queryFn: () => api.get<Storyboard>(`/storyboards/${id}`),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data?.scenes.some((s) => isVideoRunning(s.videoJob)) ? 5000 : false),
  })
}

function useBoardSaved() {
  const qc = useQueryClient()
  return (b: Storyboard) => {
    qc.setQueryData(storyKeys.board(b.id), b)
    qc.invalidateQueries({ queryKey: ['storyboards', 'list'] })
  }
}

export interface BoardInput {
  title: string
  aspectRatio: VideoFormat
  language: Language
  style?: string
  projectId?: string
}

export function useCreateStoryboard() {
  const saved = useBoardSaved()
  return useMutation({ mutationFn: (body: BoardInput) => api.post<Storyboard>('/storyboards', body), onSuccess: saved })
}

export function useStoryboardFromScript() {
  const saved = useBoardSaved()
  return useMutation({
    mutationFn: (body: { scriptId: string; writer: Writer; aspectRatio: VideoFormat; style?: string; title?: string }) =>
      api.post<Storyboard>('/storyboards/from-script', body),
    onSuccess: saved,
  })
}

export function useUpdateStoryboard() {
  const saved = useBoardSaved()
  return useMutation({ mutationFn: ({ id, ...body }: BoardInput & { id: string }) => api.put<Storyboard>(`/storyboards/${id}`, body), onSuccess: saved })
}

export function useDeleteStoryboard() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: (id: string) => api.delete(`/storyboards/${id}`), onSuccess: () => qc.invalidateQueries({ queryKey: ['storyboards'] }) })
}

export type SceneAction =
  | { kind: 'add'; boardId: string; at?: number; scene?: SceneInput }
  | { kind: 'update'; sceneId: string; scene: SceneInput }
  | { kind: 'duplicate'; sceneId: string }
  | { kind: 'delete'; sceneId: string }
  | { kind: 'reorder'; boardId: string; sceneIds: string[] }
  | { kind: 'image'; sceneId: string; accountId: string; model: string }
  | {
      kind: 'video'
      sceneId: string
      accountId: string
      modelId: string
      mode: GenerationMode
      durationSeconds?: number
      resolution?: string
      audio?: boolean
    }
  | { kind: 'dialogue'; sceneId: string }
  | { kind: 'narrate'; boardId: string; voice: VoiceChoice; style?: VoiceStyle }

function sceneRequest(a: SceneAction): Promise<Storyboard> {
  switch (a.kind) {
    case 'add':
      return api.post<Storyboard>(`/storyboards/${a.boardId}/scenes${a.at != null ? `?at=${a.at}` : ''}`, a.scene ?? {})
    case 'update':
      return api.put<Storyboard>(`/storyboards/scenes/${a.sceneId}`, a.scene)
    case 'duplicate':
      return api.post<Storyboard>(`/storyboards/scenes/${a.sceneId}/duplicate`)
    case 'delete':
      return request<Storyboard>('DELETE', `/storyboards/scenes/${a.sceneId}`)  // answers with the updated storyboard
    case 'reorder':
      return api.put<Storyboard>(`/storyboards/${a.boardId}/scenes/order`, { sceneIds: a.sceneIds })
    case 'image':
      return api.post<Storyboard>(`/storyboards/scenes/${a.sceneId}/image`, { accountId: a.accountId, model: a.model })
    case 'video': {
      const { sceneId, kind: _kind, ...body } = a
      return api.post<Storyboard>(`/storyboards/scenes/${sceneId}/video`, body)
    }
    case 'dialogue':
      return api.post<Storyboard>(`/storyboards/scenes/${a.sceneId}/dialogue-voice`)
    case 'narrate':
      return api.post<Storyboard>(`/storyboards/${a.boardId}/narration`, { voice: a.voice, style: a.style })
  }
}

export function useSceneAction() {
  const saved = useBoardSaved()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: sceneRequest,
    onSuccess: (b, a) => {
      saved(b)
      if (a.kind === 'image' || a.kind === 'video' || a.kind === 'dialogue' || a.kind === 'narrate') {
        qc.invalidateQueries({ queryKey: ['media'] })
        qc.invalidateQueries({ queryKey: ['ai'] })
      }
    },
  })
}

export function useExportStoryboard() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; name?: string; captions: boolean; musicMediaId?: string; musicVolume?: number }) =>
      api.post<ExportResult>(`/storyboards/${id}/export`, body),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: storyKeys.board(v.id) })
      qc.invalidateQueries({ queryKey: ['storyboards', 'list'] })
      qc.invalidateQueries({ queryKey: ['editor', 'timelines'] })
    },
  })
}
