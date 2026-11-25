import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type {
  AiAccount,
  AiModelInfo,
  Balance,
  CreditUnit,
  Estimate,
  GenerationJob,
  Page,
  Pricing,
  ProviderCode,
  ProviderInfo,
  UsageEntry,
  UsageSummary,
  VideoGenerationInput,
} from './types'

export const aiKeys = {
  providers: ['ai', 'providers'] as const,
  accounts: ['ai', 'accounts'] as const,
  models: ['ai', 'models'] as const,
  textModels: (id: string) => ['ai', 'text-models', id] as const,
  jobs: (params: object) => ['ai', 'jobs', params] as const,
  usage: (params: object) => ['ai', 'usage', params] as const,
  summary: (days: number) => ['ai', 'usage-summary', days] as const,
}

// ---- catalogue -----------------------------------------------------------

export function useProviders() {
  return useQuery({ queryKey: aiKeys.providers, queryFn: () => api.get<ProviderInfo[]>('/ai/providers'), staleTime: Infinity })
}

export function useModels() {
  return useQuery({ queryKey: aiKeys.models, queryFn: () => api.get<AiModelInfo[]>('/ai/models') })
}

export function useUpdateModel() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; enabled?: boolean; pricing?: Pricing; displayName?: string }) =>
      api.patch<AiModelInfo>(`/ai/models/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiKeys.models }),
  })
}

// ---- accounts ------------------------------------------------------------

export function useAccounts() {
  return useQuery({ queryKey: aiKeys.accounts, queryFn: () => api.get<AiAccount[]>('/ai/accounts') })
}

function useInvalidateAccounts() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: aiKeys.accounts })
    qc.invalidateQueries({ queryKey: ['ai', 'usage-summary'] })
  }
}

export function useCreateAccount() {
  const invalidate = useInvalidateAccounts()
  return useMutation({
    mutationFn: (body: { provider: ProviderCode; name: string; apiKey: string; makeDefault: boolean }) =>
      api.post<AiAccount>('/ai/accounts', body),
    onSuccess: invalidate,
  })
}

export function useUpdateAccount() {
  const invalidate = useInvalidateAccounts()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; name?: string; lowCreditThreshold?: number; clearLowCreditThreshold?: boolean; enabled?: boolean }) =>
      api.patch<AiAccount>(`/ai/accounts/${id}`, body),
    onSuccess: invalidate,
  })
}

export function useAccountAction() {
  const invalidate = useInvalidateAccounts()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'verify' | 'sync-balance' | 'default' }) =>
      api.post<AiAccount>(`/ai/accounts/${id}/${action}`),
    onSettled: invalidate,
  })
}

export function useReplaceKey() {
  const invalidate = useInvalidateAccounts()
  return useMutation({
    mutationFn: ({ id, apiKey }: { id: string; apiKey: string }) => api.put<AiAccount>(`/ai/accounts/${id}/key`, { apiKey }),
    onSuccess: invalidate,
  })
}

export function useDeleteAccount() {
  const invalidate = useInvalidateAccounts()
  return useMutation({ mutationFn: (id: string) => api.delete(`/ai/accounts/${id}`), onSuccess: invalidate })
}

export function useTextModels(accountId?: string) {
  return useQuery({
    queryKey: aiKeys.textModels(accountId ?? ''),
    queryFn: () => api.get<string[]>(`/ai/accounts/${accountId}/models`),
    enabled: !!accountId,
    staleTime: 10 * 60_000,
  })
}

// ---- balances & usage ----------------------------------------------------

export interface BalanceInput {
  label: string
  kind: 'FREE' | 'PAID'
  amount: number
  unit: CreditUnit
  expiresAt?: string
}

export function useSaveBalance() {
  const invalidate = useInvalidateAccounts()
  return useMutation({
    mutationFn: ({ accountId, id, clearExpiry, ...body }: BalanceInput & { accountId: string; id?: string; clearExpiry?: boolean }) =>
      id
        ? api.patch<Balance>(`/ai/balances/${id}`, { ...body, clearExpiry: !!clearExpiry })
        : api.post<Balance>(`/ai/accounts/${accountId}/balances`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteBalance() {
  const invalidate = useInvalidateAccounts()
  return useMutation({ mutationFn: (id: string) => api.delete(`/ai/balances/${id}`), onSuccess: invalidate })
}

export function useUsage(params: { accountId?: string; page: number }) {
  const q = new URLSearchParams({ page: String(params.page), size: '20' })
  if (params.accountId) q.set('accountId', params.accountId)
  return useQuery({
    queryKey: aiKeys.usage(params),
    queryFn: () => api.get<Page<UsageEntry>>(`/ai/usage?${q}`),
    placeholderData: keepPreviousData,
  })
}

export function useUsageSummary(days = 30) {
  return useQuery({ queryKey: aiKeys.summary(days), queryFn: () => api.get<UsageSummary>(`/ai/usage/summary?days=${days}`) })
}

// ---- generation ----------------------------------------------------------

export function useJobs(page = 0) {
  return useQuery({
    queryKey: aiKeys.jobs({ page }),
    queryFn: () => api.get<Page<GenerationJob>>(`/ai/generation/jobs?page=${page}&size=24`),
    placeholderData: keepPreviousData,
  })
}

export function useEstimate(input: VideoGenerationInput | undefined) {
  return useQuery({
    queryKey: ['ai', 'estimate', input],
    queryFn: () => api.post<Estimate>('/ai/generation/video/estimate', input),
    enabled: !!input,
    staleTime: 30_000,
    retry: false,
  })
}

function useInvalidateJobs() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['ai', 'jobs'] })
    qc.invalidateQueries({ queryKey: aiKeys.accounts })
    qc.invalidateQueries({ queryKey: ['media'] })
    qc.invalidateQueries({ queryKey: ['dashboard'] })
  }
}

export function useGenerateVideo() {
  const invalidate = useInvalidateJobs()
  return useMutation({
    mutationFn: ({ input, idempotencyKey }: { input: VideoGenerationInput; idempotencyKey: string }) =>
      api.post<{ jobs: GenerationJob[]; estimate?: Estimate }>('/ai/generation/video', input, { 'Idempotency-Key': idempotencyKey }),
    onSuccess: invalidate,
  })
}

export function useJobAction() {
  const invalidate = useInvalidateJobs()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'cancel' | 'regenerate' | 'delete' }) =>
      action === 'delete' ? api.delete(`/ai/generation/jobs/${id}`) : api.post(`/ai/generation/jobs/${id}/${action}`),
    onSuccess: invalidate,
  })
}

export function useEnhancePrompt() {
  return useMutation({
    mutationFn: (body: { accountId: string; model: string; prompt: string; language?: string; targetModel?: string }) =>
      api.post<{ prompt: string }>('/ai/generation/enhance-prompt', body),
  })
}
