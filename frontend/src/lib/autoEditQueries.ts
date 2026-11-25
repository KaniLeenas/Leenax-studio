import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { AutoEditJob, AutoEditRequest } from './types'

export const autoEditKeys = { jobs: ['auto-edit', 'jobs'] as const }

/** Polls every 2.5 s while an edit is running. */
export function useAutoEditJobs() {
  return useQuery({
    queryKey: autoEditKeys.jobs,
    queryFn: () => api.get<AutoEditJob[]>('/auto-edit/jobs'),
    refetchInterval: (q) => (q.state.data?.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING') ? 2500 : false),
  })
}

function useInvalidate() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: autoEditKeys.jobs })
    qc.invalidateQueries({ queryKey: ['editor', 'timelines'] })
    qc.invalidateQueries({ queryKey: ['media'] })
  }
}

export function useStartAutoEdit() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (body: AutoEditRequest) => api.post<AutoEditJob>('/auto-edit/jobs', body), onSuccess: invalidate })
}

export function useAutoEditAction() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'cancel' | 'rerun' | 'delete' }) =>
      action === 'delete' ? api.delete(`/auto-edit/jobs/${id}`) : api.post<AutoEditJob>(`/auto-edit/jobs/${id}/${action}`).then(() => undefined),
    onSuccess: invalidate,
  })
}
