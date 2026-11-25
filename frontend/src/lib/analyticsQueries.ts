import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { AuditEntry, Overview, SystemStatus } from './analytics'
import { keys } from './queries'
import type { Page } from './types'

export const timeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone

export function useOverview(days: number) {
  return useQuery({
    queryKey: ['analytics', 'overview', days],
    queryFn: () => api.get<Overview>(`/analytics/overview?days=${days}&tz=${encodeURIComponent(timeZone())}`),
    placeholderData: (prev) => prev,
  })
}

export function useRefreshNumbers() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<{ updated: number }>('/analytics/refresh'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['analytics'] }),
  })
}

export function useSystemStatus() {
  return useQuery({ queryKey: ['system', 'status'], queryFn: () => api.get<SystemStatus>('/system/status'), refetchInterval: 60_000 })
}

export function useAudit(group: string, page: number) {
  return useQuery({
    queryKey: ['audit', group, page],
    queryFn: () => api.get<Page<AuditEntry>>(`/audit?group=${group}&page=${page}&size=25`),
    placeholderData: (prev) => prev,
  })
}

// ---- two-step sign-in ----

function useInvalidateMe() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: keys.auth })
    qc.invalidateQueries({ queryKey: ['me'] })
    qc.invalidateQueries({ queryKey: ['audit'] })
  }
}

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: () => api.get<import('./types').User>('/auth/me') })
}

export function useMfaSetup() {
  return useMutation({ mutationFn: (password: string) => api.post<{ secret: string; otpauthUri: string }>('/auth/mfa/setup', { password }) })
}

export function useMfaEnable() {
  const invalidate = useInvalidateMe()
  return useMutation({
    mutationFn: (code: string) => api.post<{ recoveryCodes: string[] }>('/auth/mfa/enable', { code }),
    onSuccess: invalidate,
  })
}

export function useMfaDisable() {
  const invalidate = useInvalidateMe()
  return useMutation({
    mutationFn: (body: { password: string; code: string }) => api.post<void>('/auth/mfa/disable', body),
    onSuccess: invalidate,
  })
}

export function useMfaNewCodes() {
  const invalidate = useInvalidateMe()
  return useMutation({
    mutationFn: (body: { password: string; code: string }) => api.post<{ recoveryCodes: string[] }>('/auth/mfa/recovery-codes', body),
    onSuccess: invalidate,
  })
}
