import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Page, RenderJob, RenderQuality, TimelineResponse, TimelineSummary } from './types'
import type { TimelineDoc } from '../remotion/types'

export const editorKeys = {
  list: (projectId?: string, page = 0) => ['editor', 'timelines', projectId ?? 'all', page] as const,
  timeline: (id: string) => ['editor', 'timeline', id] as const,
  renders: (timelineId?: string) => ['editor', 'renders', timelineId ?? 'all'] as const,
  renderStatus: ['editor', 'render-status'] as const,
}

export function useTimelines(projectId?: string, page = 0) {
  const q = new URLSearchParams({ page: String(page) })
  if (projectId) q.set('projectId', projectId)
  return useQuery({
    queryKey: editorKeys.list(projectId, page),
    queryFn: () => api.get<Page<TimelineSummary>>(`/editor/timelines?${q}`),
    placeholderData: keepPreviousData,
  })
}

/** Refetched every 20 minutes so signed media links never expire mid-edit. */
export function useTimeline(id?: string) {
  return useQuery({
    queryKey: editorKeys.timeline(id ?? ''),
    queryFn: () => api.get<TimelineResponse>(`/editor/timelines/${id}`),
    enabled: !!id,
    refetchInterval: 20 * 60_000,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  })
}

function useInvalidateList() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['editor', 'timelines'] })
}

export function useCreateTimeline() {
  const invalidate = useInvalidateList()
  return useMutation({
    mutationFn: (body: { name: string; document: TimelineDoc; projectId?: string }) => api.post<TimelineResponse>('/editor/timelines', body),
    onSuccess: invalidate,
  })
}

export function useUpdateTimeline() {
  const invalidate = useInvalidateList()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; name?: string; projectId?: string; unassign?: boolean }) =>
      api.patch<TimelineResponse>(`/editor/timelines/${id}`, body),
    onSuccess: invalidate,
  })
}

export function saveTimelineDocument(id: string, revision: number, document: TimelineDoc) {
  return api.put<{ revision: number; durationSeconds: number; updatedAt: string }>(`/editor/timelines/${id}/document`, { revision, document })
}

export function useDuplicateTimeline() {
  const invalidate = useInvalidateList()
  return useMutation({ mutationFn: (id: string) => api.post<TimelineResponse>(`/editor/timelines/${id}/duplicate`), onSuccess: invalidate })
}

export function useDeleteTimeline() {
  const invalidate = useInvalidateList()
  return useMutation({ mutationFn: (id: string) => api.delete(`/editor/timelines/${id}`), onSuccess: invalidate })
}

export function useRenderAvailable() {
  return useQuery({ queryKey: editorKeys.renderStatus, queryFn: () => api.get<{ available: boolean }>('/editor/renders/status'), staleTime: 60_000 })
}

/** Polls every 2 s while an export is running. */
export function useRenders(timelineId?: string) {
  return useQuery({
    queryKey: editorKeys.renders(timelineId),
    queryFn: () => api.get<RenderJob[]>(`/editor/renders${timelineId ? `?timelineId=${timelineId}` : ''}`),
    refetchInterval: (q) => (q.state.data?.some((j) => j.status === 'QUEUED' || j.status === 'RENDERING') ? 2000 : false),
  })
}

function useInvalidateRenders() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['editor', 'renders'] })
    qc.invalidateQueries({ queryKey: ['media'] })
    qc.invalidateQueries({ queryKey: ['dashboard'] })
  }
}

export function useStartRender() {
  const invalidate = useInvalidateRenders()
  return useMutation({
    mutationFn: ({ timelineId, ...body }: { timelineId: string; resolution: number; quality: RenderQuality }) =>
      api.post<RenderJob>(`/editor/timelines/${timelineId}/renders`, body),
    onSuccess: invalidate,
  })
}

export function useRenderAction() {
  const invalidate = useInvalidateRenders()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'cancel' | 'delete' }) =>
      action === 'cancel' ? api.post<RenderJob>(`/editor/renders/${id}/cancel`).then(() => undefined) : api.delete(`/editor/renders/${id}`),
    onSuccess: invalidate,
  })
}
