import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Post } from './publish'

// ---- publishing (matches EduPublishService.java) ------------------------------------------------

export interface YouTubeChannel {
  id: string
  name: string
  username?: string | null
  avatarUrl?: string | null
  status: 'CONNECTED' | 'NEEDS_RECONNECT' | string
  /** Playlists and captions (youtube.force-ssl). */
  canManage: boolean
  /** YouTube Analytics and Reporting (yt-analytics.readonly). */
  canReadAnalytics: boolean
  lastError?: string | null
}

export interface ReadyVideo {
  resourceId: string
  kind: 'LESSON' | 'QUIZ'
  level?: string | null
  topicId: string
  title: string
  versionId: string
  versionNo: number
  mediaId: string
  seoApproved: boolean
  captionsAvailable: boolean
  publicationIds: string[]
}

export interface Publication {
  id: string
  resourceId: string
  versionId: string
  topicId: string
  postId?: string | null
  postTargetId?: string | null
  accountId?: string | null
  title: string
  createdByName?: string | null
  createdAt: string
  status?: string | null
  videoId?: string | null
  url?: string | null
  error?: string | null
  publishedAt?: string | null
  runAt?: string | null
  settings?: Record<string, unknown> | null
  authorisedAt?: string | null
  authorisedByName?: string | null
  statement?: string | null
  rightsConfirmed: boolean
  publicConfirmed: boolean
}

export interface PublishOverview {
  canAuthorise: boolean
  channels: YouTubeChannel[]
  videos: ReadyVideo[]
  publications: Publication[]
  quotaUnits: Record<'upload' | 'thumbnail' | 'playlistItem' | 'caption' | 'dailyDefault', number>
}

export interface Prepared {
  resourceId: string
  versionId: string
  versionNo: number
  mediaId: string
  topicId: string
  videoTitle: string
  seoVersionId: string
  titles: { id: string; text: string; chosen: boolean }[]
  description: string
  tags: string[]
  hashtags: string[]
  playlistIdeas: string[]
  captions?: { language: string; name: string; cues: number; srt: string } | null
  language: string
  problems: string[]
}

export interface Playlist {
  id: string
  title: string
  privacy?: string | null
  items: number
}

export interface ChannelDetails {
  channelId: string
  title: string
  customUrl?: string | null
  thumbnail?: string | null
  subscribers?: number | null
  videos?: number | null
  views?: number | null
  subscribersHidden: boolean
  playlists: Playlist[]
  note?: string | null
}

export type Visibility = 'private' | 'unlisted' | 'public' | 'scheduled'

export interface PublishRequest {
  resourceId: string
  versionId: string
  accountId: string
  titleId: string
  visibility: Visibility
  publishAt?: string
  when: 'NOW' | 'SCHEDULE'
  uploadAt?: string
  thumbnailMediaId?: string
  playlistIds: string[]
  addCaptions: boolean
  addHashtags: boolean
  madeForKids: boolean
  alteredOrSynthetic: boolean
  notifySubscribers: boolean
  categoryId?: string
  rightsConfirmed: boolean
  confirmPublic: boolean
}

export const VISIBILITY_LABEL: Record<Visibility, string> = {
  private: 'Private',
  unlisted: 'Unlisted',
  public: 'Public',
  scheduled: 'Scheduled',
}

export const VISIBILITY_HINT: Record<Visibility, string> = {
  private: 'Only you and people you choose on YouTube.',
  unlisted: 'Anyone with the link; not in search or on the channel.',
  public: 'Everyone, in search and on the channel.',
  scheduled: 'Private until the time you set, then public.',
}

/** Whether the video will be public now or later (the server asks for a confirmation then). */
export const willBePublic = (v: Visibility) => v === 'public' || v === 'scheduled'

/** The approved description as it will be sent, with the hashtags only when the person adds them. */
export function finalDescription(p: Pick<Prepared, 'description' | 'hashtags'>, addHashtags: boolean): string {
  if (!addHashtags || p.hashtags.length === 0) return p.description
  return `${p.description.trimEnd()}\n\n${p.hashtags.map((h) => (h.startsWith('#') ? h : `#${h}`)).join(' ')}`
}

/** YouTube Data API units this publication will use (the default daily quota is 10,000). */
export function quotaEstimate(units: PublishOverview['quotaUnits'], o: { thumbnail: boolean; playlists: number; captions: boolean }): number {
  return units.upload + (o.thumbnail ? units.thumbnail : 0) + o.playlists * units.playlistItem + (o.captions ? units.caption : 0)
}

export interface PublishForm {
  accountId?: string
  titleId?: string
  visibility: Visibility
  publishAt?: string
  when: 'NOW' | 'SCHEDULE'
  uploadAt?: string
  playlistIds: string[]
  addCaptions: boolean
  madeForKids?: boolean
  alteredOrSynthetic?: boolean
  rightsConfirmed: boolean
  confirmPublic: boolean
}

/** What still stops the authorisation, in plain words (the server checks all of it again). */
export function publishProblems(f: PublishForm, p: Prepared | undefined, channel: YouTubeChannel | undefined, now = new Date()): string[] {
  const out: string[] = []
  if (!p) return ['Loading the approved metadata']
  out.push(...p.problems)
  if (!channel) out.push('Choose a channel')
  else if (channel.status !== 'CONNECTED') out.push(`${channel.name} needs to be reconnected`)
  else if ((f.playlistIds.length > 0 || f.addCaptions) && !channel.canManage) out.push(`Reconnect ${channel.name} to add playlists and captions`)
  if (!f.titleId) out.push('Choose an approved title')
  if (f.madeForKids === undefined) out.push('Say whether the video is made for kids')
  if (f.alteredOrSynthetic === undefined) out.push('Say whether the video has realistic altered or synthetic content')
  const upload = f.when === 'SCHEDULE' ? (f.uploadAt ? new Date(f.uploadAt) : undefined) : now
  if (f.when === 'SCHEDULE' && (!upload || upload.getTime() < now.getTime() - 60_000)) out.push('Choose a future upload time')
  if (f.visibility === 'scheduled') {
    const at = f.publishAt ? new Date(f.publishAt) : undefined
    if (!at) out.push('Choose when YouTube makes it public')
    else if (upload && at.getTime() < upload.getTime() + 3600_000) out.push('Make it public at least an hour after the upload starts')
  }
  if (f.playlistIds.length > 5) out.push('Choose at most 5 playlists')
  if (!f.rightsConfirmed) out.push('Confirm that you hold the rights to everything in the video')
  if (willBePublic(f.visibility) && !f.confirmPublic) out.push('Confirm that the video will be public')
  return out
}

/** The sentence recorded with the authorisation, shown before it is given. */
export function authorisationSummary(title: string | undefined, channel: string | undefined, f: PublishForm): string {
  const vis =
    f.visibility === 'scheduled' ? `private until ${f.publishAt ? new Date(f.publishAt).toLocaleString() : '…'}, then public` : VISIBILITY_LABEL[f.visibility].toLowerCase()
  const when = f.when === 'NOW' ? 'now' : f.uploadAt ? `at ${new Date(f.uploadAt).toLocaleString()}` : 'at the time you choose'
  return `Upload “${title ?? '…'}” to ${channel ?? '…'} as ${vis}, ${when}.`
}

export const PUBLICATION_LABEL: Record<string, string> = {
  SCHEDULED: 'Waiting to upload',
  UPLOADING: 'Uploading',
  PROCESSING: 'YouTube is processing',
  AWAITING_USER: 'Waiting for you',
  PUBLISHED: 'On YouTube',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  DRAFT: 'Draft',
}

export function publicationTone(s?: string | null): 'neutral' | 'progress' | 'done' | 'muted' {
  if (s === 'PUBLISHED') return 'done'
  if (s === 'UPLOADING' || s === 'PROCESSING' || s === 'SCHEDULED') return 'progress'
  if (s === 'CANCELLED' || !s) return 'muted'
  return 'neutral'
}

// ---- analytics (matches YouTubeAnalyticsService.java) --------------------------------------------

export interface ReachStatus {
  started: boolean
  startedAt?: string | null
  lastCheckedAt?: string | null
  lastError?: string | null
  lastDay?: string | null
  note?: string | null
}

export interface VideoRow {
  videoId: string
  url: string
  title: string
  resourceId?: string | null
  topicId?: string | null
  topicTitle?: string | null
  publishedAt?: string | null
  views?: number | null
  minutesWatched?: number | null
  averageViewDuration?: number | null
  averageViewPercentage?: number | null
  subscribersGained?: number | null
  likes?: number | null
  comments?: number | null
  shares?: number | null
  impressions?: number | null
  ctr?: number | null
  reachDays?: number | null
  durationSeconds?: number | null
}

export interface Totals {
  views?: number | null
  minutesWatched?: number | null
  averageViewDuration?: number | null
  subscribersGained?: number | null
  likes?: number | null
  comments?: number | null
  shares?: number | null
  impressions?: number | null
  ctr?: number | null
}

export interface RetentionPoint {
  ratio: number
  audienceWatchRatio: number
  relativeRetentionPerformance?: number | null
}

export interface SceneSpan {
  start: number
  end: number
  title?: string | null
}

export interface Recommendation {
  kind: 'BEST_SUBJECT' | 'UNDERPERFORMING' | 'DROP_OFF' | 'STRONG_SECTION' | 'TITLE_THUMBNAIL' | 'FUTURE_TOPIC' | 'STRATEGY'
  title: string
  detail: string
  rule: string
  videoId?: string | null
  evidence: { metric: string; value: string; source: string }[]
}

export interface SnapshotSet {
  accountId: string
  startDate: string
  endDate: string
  fetchedAt: string
  createdByName?: string | null
  parts: number
}

export interface Dashboard {
  accountId: string
  from: string
  to: string
  fetchedAt?: string | null
  fetchedByName?: string | null
  canRefresh: boolean
  canManageReach: boolean
  reach: ReachStatus
  totals: Totals
  videos: VideoRow[]
  daily: Record<string, string | number | null>[]
  traffic: Record<string, number>
  searchTerms: Record<string, string | number | null>[]
  retention: Record<string, RetentionPoint[]>
  scenes: Record<string, SceneSpan[]>
  recommendations: Recommendation[]
  history: SnapshotSet[]
  notes: string[]
}

export const RECOMMENDATION_LABEL: Record<Recommendation['kind'], string> = {
  BEST_SUBJECT: 'Best subject',
  UNDERPERFORMING: 'Underperforming',
  DROP_OFF: 'Drop-off',
  STRONG_SECTION: 'Strong section',
  TITLE_THUMBNAIL: 'Title and thumbnail',
  FUTURE_TOPIC: 'Future topic',
  STRATEGY: 'Strategy',
}

export const TRAFFIC_LABEL: Record<string, string> = {
  YT_SEARCH: 'YouTube search',
  RELATED_VIDEO: 'Suggested videos',
  SUBSCRIBER: 'Subscribers',
  BROWSE: 'Browse features',
  PLAYLIST: 'Playlists',
  EXT_URL: 'External websites',
  NO_LINK_OTHER: 'Direct or unknown',
  SHORTS: 'Shorts feed',
  YT_CHANNEL: 'Channel pages',
  END_SCREEN: 'End screens',
  NOTIFICATION: 'Notifications',
  YT_OTHER_PAGE: 'Other YouTube pages',
  ANNOTATION: 'Cards and annotations',
  CAMPAIGN_CARD: 'Campaign cards',
  HASHTAGS: 'Hashtag pages',
  LIVE_REDIRECT: 'Live redirects',
  SOUND_PAGE: 'Sound pages',
  VIDEO_REMIXES: 'Remixes',
  PRODUCT_PAGE: 'Product pages',
  IMMERSIVE_LIVE: 'Immersive live',
  ADVERTISING: 'Advertising',
}

export const trafficLabel = (k: string) => TRAFFIC_LABEL[k] ?? k.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())

/** Daily rows for a column chart: YouTube's "day" column becomes the chart date. */
export function dailyViews(daily: Dashboard['daily']): { date: string; values: { views: number } }[] {
  return daily
    .filter((r) => typeof r.day === 'string')
    .map((r) => ({ date: String(r.day), values: { views: typeof r.views === 'number' ? r.views : 0 } }))
    .sort((a, b) => a.date.localeCompare(b.date))
}

/** Traffic sources, largest first, with their share of views. */
export function trafficRows(traffic: Record<string, number>): { key: string; label: string; views: number; share: number }[] {
  const total = Object.values(traffic).reduce((s, v) => s + v, 0)
  return Object.entries(traffic)
    .map(([key, views]) => ({ key, label: trafficLabel(key), views, share: total > 0 ? views / total : 0 }))
    .sort((a, b) => b.views - a.views)
}

/** A YYYY-MM-DD date some days before another (UTC, as YouTube counts days). */
export function daysBefore(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString().slice(0, 10)
}

export function yesterday(now = new Date()): string {
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()))
  d.setUTCDate(d.getUTCDate() - 1)
  return d.toISOString().slice(0, 10)
}

export function clock(seconds?: number | null): string {
  if (seconds == null) return '—'
  const s = Math.round(seconds)
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`
}

export function percent(fraction?: number | null, digits = 1): string {
  if (fraction == null) return '—'
  return `${(fraction * 100).toFixed(fraction < 0.1 ? digits : 0)}%`
}

// ---- queries ----------------------------------------------------------------------------------

const base = (projectId: string) => `/projects/${projectId}/youtube`

export function usePublishOverview(projectId: string) {
  return useQuery({
    queryKey: ['youtube', projectId],
    queryFn: () => api.get<PublishOverview>(base(projectId)),
    enabled: !!projectId,
    refetchInterval: (q) => (q.state.data?.publications.some((p) => ['SCHEDULED', 'UPLOADING', 'PROCESSING'].includes(p.status ?? '')) ? 10_000 : false),
  })
}

export function usePrepared(projectId: string, resourceId?: string) {
  return useQuery({
    queryKey: ['youtube', projectId, 'prepare', resourceId ?? ''],
    queryFn: () => api.get<Prepared>(`${base(projectId)}/videos/${resourceId}/prepare`),
    enabled: !!resourceId,
    retry: false,
  })
}

export function useChannelDetails(projectId: string, accountId?: string) {
  return useQuery({
    queryKey: ['youtube', projectId, 'channel', accountId ?? ''],
    queryFn: () => api.get<ChannelDetails>(`${base(projectId)}/channels/${accountId}`),
    enabled: !!accountId,
    retry: false,
    staleTime: 60_000,
  })
}

export function useCreatePlaylist(projectId: string, accountId?: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { title: string; description?: string; privacy: 'public' | 'unlisted' | 'private' }) =>
      api.post<Playlist>(`${base(projectId)}/channels/${accountId}/playlists`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['youtube', projectId, 'channel', accountId ?? ''] }),
  })
}

export function usePublish(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: PublishRequest) => api.post<{ publicationId: string; post: Post }>(`${base(projectId)}/publications`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['youtube', projectId] })
      qc.invalidateQueries({ queryKey: ['posts'] })
    },
  })
}

const range = (accountId: string, from?: string, to?: string) =>
  `accountId=${encodeURIComponent(accountId)}${from ? `&from=${from}` : ''}${to ? `&to=${to}` : ''}`

export function useDashboard(projectId: string, accountId?: string, from?: string, to?: string, snapshotAt?: string) {
  return useQuery({
    queryKey: ['youtube', projectId, 'analytics', accountId ?? '', from ?? '', to ?? '', snapshotAt ?? ''],
    queryFn: () =>
      snapshotAt
        ? api.get<Dashboard>(`${base(projectId)}/analytics/snapshots/${encodeURIComponent(snapshotAt)}?accountId=${encodeURIComponent(accountId!)}`)
        : api.get<Dashboard>(`${base(projectId)}/analytics?${range(accountId!, from, to)}`),
    enabled: !!accountId,
  })
}

export function useRefreshAnalytics(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, from, to }: { accountId: string; from?: string; to?: string }) =>
      api.post<Dashboard>(`${base(projectId)}/analytics/refresh?${range(accountId, from, to)}`),
    onSuccess: (d, v) => {
      qc.setQueryData(['youtube', projectId, 'analytics', v.accountId, v.from ?? '', v.to ?? '', ''], d)
      qc.invalidateQueries({ queryKey: ['youtube', projectId, 'analytics'] })
    },
  })
}

export function useReach(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, action }: { accountId: string; action: 'start' | 'import' }) =>
      api.post<ReachStatus>(`${base(projectId)}/analytics/reach${action === 'import' ? '/import' : ''}?accountId=${encodeURIComponent(accountId)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['youtube', projectId, 'analytics'] }),
  })
}
