import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { CreatorInfo, PlatformInfo, Post, PostRequest, PostStatus, SignIn, SocialAccount } from './publish'
import { isActivePost } from './publish'
import type { MediaAsset } from './types'

export const publishKeys = {
  platforms: ['social', 'platforms'] as const,
  accounts: ['social', 'accounts'] as const,
  creator: (id: string) => ['social', 'creator', id] as const,
  posts: (status?: PostStatus) => ['publish', 'posts', status ?? 'all'] as const,
  post: (id: string) => ['publish', 'post', id] as const,
  calendar: (from: string, to: string) => ['publish', 'calendar', from, to] as const,
}

export function usePlatforms() {
  return useQuery({ queryKey: publishKeys.platforms, queryFn: () => api.get<PlatformInfo[]>('/social/platforms'), staleTime: 60_000 })
}

export function useSocialAccounts() {
  return useQuery({ queryKey: publishKeys.accounts, queryFn: () => api.get<SocialAccount[]>('/social/accounts') })
}

/** TikTok requires fresh creator info on the post screen, so it is not cached for long. */
export function useTikTokCreator(accountId?: string) {
  return useQuery({
    queryKey: publishKeys.creator(accountId ?? ''),
    queryFn: () => api.get<CreatorInfo>(`/social/accounts/${accountId}/tiktok-creator`),
    enabled: !!accountId,
    staleTime: 30_000,
    retry: false,
  })
}

/** Starts a sign-in: the browser leaves for the platform's own page. */
export function useConnect() {
  return useMutation({
    mutationFn: (signIn: SignIn) => api.post<{ authorizationUrl: string }>(`/social/oauth/${signIn.toLowerCase()}/start`),
    onSuccess: ({ authorizationUrl }) => {
      window.location.assign(authorizationUrl)
    },
  })
}

export function useAccountAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'check' | 'disconnect' }) =>
      action === 'disconnect' ? api.delete(`/social/accounts/${id}`) : api.post<SocialAccount>(`/social/accounts/${id}/check`).then(() => undefined),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: publishKeys.accounts })
      qc.invalidateQueries({ queryKey: ['publish'] })
    },
  })
}

const pollWhileActive = (posts?: Post[]) => (posts?.some(isActivePost) ? 4000 : false)

export function usePosts(status?: PostStatus) {
  return useQuery({
    queryKey: publishKeys.posts(status),
    queryFn: () => api.get<Post[]>(`/publish/posts${status ? `?status=${status}` : ''}`),
    refetchInterval: (q) => pollWhileActive(q.state.data),
  })
}

export function usePost(id?: string) {
  return useQuery({
    queryKey: publishKeys.post(id ?? ''),
    queryFn: () => api.get<Post>(`/publish/posts/${id}`),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data && isActivePost(q.state.data) ? 4000 : false),
  })
}

export function useCalendar(from: Date, to: Date) {
  const f = from.toISOString()
  const t = to.toISOString()
  return useQuery({
    queryKey: publishKeys.calendar(f, t),
    queryFn: () => api.get<Post[]>(`/publish/calendar?from=${encodeURIComponent(f)}&to=${encodeURIComponent(t)}`),
    refetchInterval: (q) => pollWhileActive(q.state.data),
    placeholderData: (prev) => prev,
  })
}

function useInvalidatePosts() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['publish'] })
}

export function useSavePost() {
  const invalidate = useInvalidatePosts()
  return useMutation({
    mutationFn: ({ id, body }: { id?: string; body: PostRequest }) =>
      id ? api.put<Post>(`/publish/posts/${id}`, body) : api.post<Post>('/publish/posts', body),
    onSuccess: invalidate,
  })
}

export function useReschedule() {
  const invalidate = useInvalidatePosts()
  return useMutation({
    mutationFn: ({ id, scheduledAt }: { id: string; scheduledAt: string }) => api.post<Post>(`/publish/posts/${id}/reschedule`, { scheduledAt }),
    onSettled: invalidate,
  })
}

export function usePostAction() {
  const invalidate = useInvalidatePosts()
  return useMutation({
    mutationFn: ({ id, action, targetId, confirmPublic }: { id: string; action: 'cancel' | 'delete' | 'retry'; targetId?: string; confirmPublic?: boolean }) =>
      action === 'delete'
        ? api.delete(`/publish/posts/${id}`)
        : api
            .post<Post>(action === 'retry' ? `/publish/posts/${id}/targets/${targetId}/retry${confirmPublic ? '?confirmPublic=true' : ''}` : `/publish/posts/${id}/cancel`)
            .then(() => undefined),
    onSuccess: invalidate,
  })
}

/** One library file by id (for editing a post whose thumbnail was chosen earlier). */
export function useMediaAsset(id?: string) {
  return useQuery({ queryKey: ['media', 'one', id ?? ''], queryFn: () => api.get<MediaAsset>(`/media/${id}`), enabled: !!id })
}
