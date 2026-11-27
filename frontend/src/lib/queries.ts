import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type {
  AspectRatio,
  AuthStatus,
  DashboardSummary,
  Language,
  MediaAsset,
  MediaKind,
  Page,
  Project,
  ProjectStatus,
  LoginResult,
  User,
} from './types'

export const keys = {
  auth: ['auth'] as const,
  dashboard: ['dashboard'] as const,
  projects: (params: object) => ['projects', params] as const,
  project: (id: string) => ['project', id] as const,
  media: (params: object) => ['media', params] as const,
}

// ---- auth -------------------------------------------------------------

export function useAuthStatus() {
  return useQuery({ queryKey: keys.auth, queryFn: () => api.get<AuthStatus>('/auth/status'), staleTime: 60_000 })
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { email: string; password: string }) => api.post<LoginResult>('/auth/login', body),
    onSuccess: (r) => {
      if (!r.mfaRequired) qc.invalidateQueries({ queryKey: keys.auth })
    },
  })
}

export function useRegistrationStatus() {
  return useQuery({ queryKey: ['auth', 'registration'], queryFn: () => api.get<{ enabled: boolean }>('/auth/registration'), staleTime: 30_000 })
}

export function useRegister() {
  return useMutation({
    mutationFn: (body: { displayName: string; email: string; password: string; confirmPassword: string }) =>
      api.post<{ message: string }>('/auth/register', body),
  })
}

/** Second sign-in step: a code from the authenticator app, or a recovery code. */
export function useMfaLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<LoginResult>('/auth/mfa/login', { code }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.auth }),
  })
}

export function useSetup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { setupToken: string; email: string; displayName: string; password: string }) =>
      api.post<User>('/auth/setup', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.auth }),
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSettled: () => {
      qc.clear()
      qc.invalidateQueries({ queryKey: keys.auth })
    },
  })
}

export function useUpdateProfile() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { displayName: string }) => api.patch<User>('/auth/me', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.auth }),
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) => api.post<void>('/auth/change-password', body),
  })
}

// ---- dashboard --------------------------------------------------------

export function useDashboard() {
  return useQuery({ queryKey: keys.dashboard, queryFn: () => api.get<DashboardSummary>('/dashboard') })
}

// ---- projects ---------------------------------------------------------

export interface ProjectQuery {
  status?: ProjectStatus
  search?: string
  includeArchived?: boolean
  page?: number
}

function qs(params: Record<string, string | number | boolean | undefined>) {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v))
  const s = p.toString()
  return s ? `?${s}` : ''
}

export function useProjects(params: ProjectQuery) {
  return useQuery({
    queryKey: keys.projects(params),
    queryFn: () => api.get<Page<Project>>(`/projects${qs({ ...params })}`),
    placeholderData: keepPreviousData,
  })
}

export function useProject(id: string) {
  return useQuery({ queryKey: keys.project(id), queryFn: () => api.get<Project>(`/projects/${id}`) })
}

export interface ProjectInput {
  name: string
  description?: string
  aspectRatio?: AspectRatio
  language?: Language
  status?: ProjectStatus
  kind?: import('./types').ProjectKind
  subject?: import('./types').ProjectSubject
  reviewerNotAuthor?: boolean
}

function useInvalidateWork() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['projects'] })
    qc.invalidateQueries({ queryKey: ['project'] })
    qc.invalidateQueries({ queryKey: ['media'] })
    qc.invalidateQueries({ queryKey: keys.dashboard })
  }
}

export function useCreateProject() {
  const invalidate = useInvalidateWork()
  return useMutation({
    mutationFn: (body: ProjectInput) => api.post<Project>('/projects', body),
    onSuccess: invalidate,
  })
}

export function useUpdateProject(id: string) {
  const invalidate = useInvalidateWork()
  return useMutation({
    mutationFn: (body: Partial<ProjectInput>) => api.patch<Project>(`/projects/${id}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteProject() {
  const invalidate = useInvalidateWork()
  return useMutation({ mutationFn: (id: string) => api.delete(`/projects/${id}`), onSuccess: invalidate })
}

// ---- media ------------------------------------------------------------

export interface MediaQuery {
  kind?: MediaKind
  projectId?: string
  unassigned?: boolean
  search?: string
  page?: number
}

export function useMedia(params: MediaQuery) {
  return useQuery({
    queryKey: keys.media(params),
    queryFn: () => api.get<Page<MediaAsset>>(`/media${qs({ ...params })}`),
    placeholderData: keepPreviousData,
    // Signed URLs expire after 30 minutes; refresh well before that.
    staleTime: 5 * 60_000,
    refetchInterval: 20 * 60_000,
  })
}

export function useUpdateMedia() {
  const invalidate = useInvalidateWork()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; filename?: string; projectId?: string; unassign?: boolean }) =>
      api.patch<MediaAsset>(`/media/${id}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteMedia() {
  const invalidate = useInvalidateWork()
  return useMutation({ mutationFn: (id: string) => api.delete(`/media/${id}`), onSuccess: invalidate })
}

export function useInvalidateAfterUpload() {
  return useInvalidateWork()
}
