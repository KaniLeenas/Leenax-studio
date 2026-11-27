import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Page, Project, ProjectRole, SystemRole } from './types'

// ---- accounts -----------------------------------------------------------------------------------

export type AccountStatus = 'INVITED' | 'ACTIVE' | 'DEACTIVATED'

export interface Person {
  id: string
  email: string
  displayName: string
  role: SystemRole
  status: AccountStatus
  canCreateProjects: boolean
  mfaEnabled: boolean
  lastLoginAt?: string
  activatedAt?: string
  deactivatedAt?: string
  createdAt: string
}

export interface UsersResponse {
  users: Person[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** A one-time link the administrator passes on. Shown once: only its hash is stored. */
export interface IssuedLink {
  user: Person
  path: string
  expiresAt: string
}

export interface Colleague {
  id: string
  displayName: string
  email: string
}

export interface LoginRecord {
  id: string
  userId?: string
  email: string
  success: boolean
  reason?: string
  ipAddress?: string
  userAgent?: string
  createdAt: string
}

export interface LinkInfo {
  purpose: 'ACTIVATE' | 'RESET'
  email: string
  displayName: string
  expiresAt: string
  passwordChosen?: boolean
}

export type RegistrationMode = 'INVITATION_ONLY' | 'PUBLIC'
export interface RegistrationSettings { mode: RegistrationMode; emailConfigured: boolean }

export const peopleKeys = {
  users: ['users'] as const,
  colleagues: (search = '') => ['users', 'colleagues', search.trim()] as const,
  logins: (userId?: string) => ['users', 'logins', userId ?? 'all'] as const,
  members: (projectId: string) => ['projects', projectId, 'members'] as const,
}

export function useUsers(page = 0, search = '', status: AccountStatus | '' = '') {
  const params = new URLSearchParams({ page: String(page), size: '50' })
  if (search.trim()) params.set('search', search.trim())
  if (status) params.set('status', status)
  return useQuery({
    queryKey: [...peopleKeys.users, page, search.trim(), status],
    queryFn: () => api.get<UsersResponse>(`/users?${params.toString()}`),
  })
}

export function useRegistrationSettings() {
  return useQuery({ queryKey: ['users', 'registration'], queryFn: () => api.get<RegistrationSettings>('/users/registration') })
}

export function useUpdateRegistrationMode() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (mode: RegistrationMode) => api.put<RegistrationSettings>('/users/registration', { mode }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users', 'registration'] }),
  })
}

export function useColleagues(enabled = true, search = '') {
  const params = new URLSearchParams({ size: '20' })
  if (search.trim()) params.set('search', search.trim())
  return useQuery({ queryKey: peopleKeys.colleagues(search), queryFn: () => api.get<Page<Colleague>>(`/users/colleagues?${params.toString()}`), enabled })
}

export function useLogins(userId?: string) {
  return useQuery({
    queryKey: peopleKeys.logins(userId),
    queryFn: () => api.get<Page<LoginRecord>>(`/users/logins?size=50${userId ? `&userId=${userId}` : ''}`),
  })
}

function usePeopleChanged() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['users'] })
}

export function useInvite() {
  const changed = usePeopleChanged()
  return useMutation({
    mutationFn: (body: { email: string; displayName: string; role: SystemRole; canCreateProjects: boolean }) =>
      api.post<IssuedLink>('/users', body),
    onSuccess: changed,
  })
}

export function useUpdatePerson() {
  const changed = usePeopleChanged()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; displayName: string; role: SystemRole; canCreateProjects: boolean }) =>
      api.put<Person>(`/users/${id}`, body),
    onSuccess: () => {
      changed()
      qc.invalidateQueries({ queryKey: ['auth'] })
    },
  })
}

export type PersonAction = 'deactivate' | 'reactivate'

export function usePersonAction() {
  const changed = usePeopleChanged()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: PersonAction }) => api.post<Person>(`/users/${id}/${action}`),
    onSuccess: changed,
  })
}

export function useIssueLink() {
  const changed = usePeopleChanged()
  return useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'activation' | 'reset' }) => api.post<IssuedLink>(`/users/${id}/${kind}-link`),
    onSuccess: changed,
  })
}

/** Public: what an activation or reset link is for. */
export function useLinkInfo(token: string | null) {
  return useQuery({
    queryKey: ['link', token],
    queryFn: () => api.get<LinkInfo>(`/auth/link?token=${encodeURIComponent(token ?? '')}`),
    enabled: !!token,
    retry: false,
  })
}

export function useRedeemLink() {
  return useMutation({ mutationFn: (body: { token: string; password: string }) => api.post<void>('/auth/link', body) })
}

/** The full address of a one-time link, ready to copy. */
export const linkUrl = (path: string, origin = window.location.origin) => `${origin}${path}`

// ---- project members ------------------------------------------------------------------------------

export interface Member {
  userId: string
  displayName: string
  email: string
  role: ProjectRole
  status: AccountStatus
  addedAt: string
}

export function useProjectMembers(projectId?: string) {
  return useQuery({
    queryKey: peopleKeys.members(projectId ?? ''),
    queryFn: () => api.get<Member[]>(`/projects/${projectId}/members`),
    enabled: !!projectId,
  })
}

export type MemberChange =
  | { kind: 'add'; userId: string; role: ProjectRole }
  | { kind: 'role'; userId: string; role: ProjectRole }
  | { kind: 'remove'; userId: string }

export function useChangeMembers(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (c: MemberChange): Promise<Member[]> => {
      if (c.kind === 'add') return api.post<Member[]>(`/projects/${projectId}/members`, { userId: c.userId, role: c.role })
      if (c.kind === 'role') return api.put<Member[]>(`/projects/${projectId}/members/${c.userId}`, { role: c.role })
      return api.delete(`/projects/${projectId}/members/${c.userId}`) as unknown as Promise<Member[]>
    },
    onSuccess: (list, c) => {
      if (c.kind === 'remove') qc.invalidateQueries({ queryKey: peopleKeys.members(projectId) })
      else qc.setQueryData(peopleKeys.members(projectId), list)
      qc.invalidateQueries({ queryKey: ['projects'] })
    },
  })
}

export interface SimilarProject {
  id: string
  name: string
  moduleCode?: string
  academicYear?: string
  reason: string
}

export function useSimilarProjects(name: string, moduleCode: string) {
  const q = new URLSearchParams()
  if (name.trim().length >= 3) q.set('name', name.trim())
  if (moduleCode.trim()) q.set('moduleCode', moduleCode.trim())
  const query = q.toString()
  return useQuery({
    queryKey: ['projects', 'similar', query],
    queryFn: () => api.get<SimilarProject[]>(`/projects/similar?${query}`),
    enabled: query.length > 0,
    staleTime: 30_000,
  })
}

// ---- roles in plain words ---------------------------------------------------------------------------

export const PROJECT_ROLES: { id: ProjectRole; label: string; can: string }[] = [
  { id: 'OWNER', label: 'Owner', can: 'Everything, including members and deleting the project' },
  { id: 'EDITOR', label: 'Editor', can: 'Upload, generate and edit' },
  { id: 'REVIEWER', label: 'Reviewer', can: 'Read and approve or reject stages' },
  { id: 'VIEWER', label: 'Viewer', can: 'Read approved work and download reports' },
]

export const roleLabel = (role?: Project['myRole']) =>
  role === 'ADMIN' ? 'Administrator' : PROJECT_ROLES.find((r) => r.id === role)?.label ?? 'No access'

/** Owners, editors and administrators change content. */
export const canEditProject = (role?: Project['myRole']) => role === 'OWNER' || role === 'EDITOR' || role === 'ADMIN'

/** Owners and administrators manage members and delete the project. */
export const canOwnProject = (role?: Project['myRole']) => role === 'OWNER' || role === 'ADMIN'


/** A short "Chrome on Windows" from a user agent, enough to spot a stranger. */
export function device(ua?: string): string {
  if (!ua) return ''
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${browser} on ${os}` : browser
}
