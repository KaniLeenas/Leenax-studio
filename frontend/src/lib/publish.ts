import type { MediaAsset } from './types'

// ---- types (mirror the backend DTOs) -----------------------------------------------

export type SocialPlatform = 'YOUTUBE' | 'FACEBOOK' | 'INSTAGRAM' | 'TIKTOK'
export type SignIn = 'GOOGLE' | 'META' | 'TIKTOK'

export interface PlatformInfo {
  platform: SocialPlatform
  name: string
  signIn: SignIn
  configured: boolean
  redirectUri: string
  setupHint: string
  limits: string[]
}

export interface SocialAccount {
  id: string
  platform: SocialPlatform
  displayName: string
  username?: string
  avatarUrl?: string
  profileUrl?: string
  status: 'CONNECTED' | 'NEEDS_RECONNECT'
  lastError?: string
  accessExpiresAt?: string
  connectedAt: string
}

export interface CreatorInfo {
  username?: string
  nickname?: string
  avatarUrl?: string
  privacyOptions: string[]
  commentDisabled: boolean
  duetDisabled: boolean
  stitchDisabled: boolean
  maxVideoSeconds: number
}

export type PostStatus = 'DRAFT' | 'SCHEDULED' | 'PUBLISHING' | 'PUBLISHED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
export type TargetStatus = 'DRAFT' | 'SCHEDULED' | 'UPLOADING' | 'PROCESSING' | 'AWAITING_USER' | 'PUBLISHED' | 'FAILED' | 'CANCELLED'
export type Settings = Record<string, unknown>

export interface PostTarget {
  id: string
  accountId?: string
  platform: SocialPlatform
  accountName: string
  status: TargetStatus
  settings: Settings
  step?: string
  progress: number
  remoteUrl?: string
  errorMessage?: string
  warning?: string
  attempts: number
  nextAttemptAt?: string
  publishedAt?: string
}

export interface Post {
  id: string
  title: string
  media?: MediaAsset
  thumbnailMediaId?: string
  status: PostStatus
  scheduledAt?: string
  publishedAt?: string
  createdAt: string
  updatedAt: string
  version: number
  targets: PostTarget[]
}

export type PublishAction = 'DRAFT' | 'SCHEDULE' | 'NOW'

export interface PostRequest {
  title: string
  mediaId: string
  thumbnailMediaId?: string
  action: PublishAction
  scheduledAt?: string
  targets: { accountId: string; settings: Settings }[]
  /** The person's confirmation that public targets will be seen by anyone (needed to post or schedule them). */
  confirmPublic?: boolean
}

/**
 * Whether anyone will be able to see the post, now or later. Mirrors the server's rule: a YouTube
 * video that is public or scheduled to go public, TikTok other than "Only me", and every
 * Facebook or Instagram post.
 */
export function isPublicTarget(platform: SocialPlatform, s: Settings): boolean {
  switch (platform) {
    case 'YOUTUBE':
      return s.privacy === 'public' || (typeof s.publishAt === 'string' && s.publishAt !== '')
    case 'TIKTOK':
      return s.privacy !== 'SELF_ONLY'
    default:
      return true
  }
}

// ---- platforms ------------------------------------------------------------------------

export const PLATFORM: Record<SocialPlatform, { name: string; short: string; captionKey: string; captionMax: number; captionLabel: string }> = {
  YOUTUBE: { name: 'YouTube', short: 'YT', captionKey: 'description', captionMax: 5000, captionLabel: 'Description' },
  FACEBOOK: { name: 'Facebook', short: 'FB', captionKey: 'description', captionMax: 5000, captionLabel: 'Description' },
  INSTAGRAM: { name: 'Instagram', short: 'IG', captionKey: 'caption', captionMax: 2200, captionLabel: 'Caption' },
  TIKTOK: { name: 'TikTok', short: 'TT', captionKey: 'caption', captionMax: 2200, captionLabel: 'Caption' },
}

export const PLATFORM_ORDER: SocialPlatform[] = ['YOUTUBE', 'FACEBOOK', 'INSTAGRAM', 'TIKTOK']

export const TIKTOK_PRIVACY_LABEL: Record<string, string> = {
  PUBLIC_TO_EVERYONE: 'Everyone',
  MUTUAL_FOLLOW_FRIENDS: 'Friends',
  FOLLOWER_OF_CREATOR: 'Followers',
  SELF_ONLY: 'Only me',
}

export const TARGET_LABEL: Record<TargetStatus, string> = {
  DRAFT: 'Draft',
  SCHEDULED: 'Scheduled',
  UPLOADING: 'Uploading',
  PROCESSING: 'Processing',
  AWAITING_USER: 'Finish in TikTok',
  PUBLISHED: 'Published',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export const POST_LABEL: Record<PostStatus, string> = {
  DRAFT: 'Draft',
  SCHEDULED: 'Scheduled',
  PUBLISHING: 'Publishing',
  PUBLISHED: 'Published',
  PARTIAL: 'Partly published',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export function postTone(s: PostStatus | TargetStatus): 'neutral' | 'progress' | 'done' | 'muted' | 'error' {
  switch (s) {
    case 'PUBLISHED':
    case 'AWAITING_USER':
      return 'done'
    case 'SCHEDULED':
    case 'PUBLISHING':
    case 'UPLOADING':
    case 'PROCESSING':
      return 'progress'
    case 'FAILED':
    case 'PARTIAL':
      return 'error'
    case 'CANCELLED':
      return 'muted'
    default:
      return 'neutral'
  }
}

export const isActivePost = (p: Post) => p.status === 'SCHEDULED' || p.status === 'PUBLISHING' || p.targets.some((t) => t.status === 'AWAITING_USER')
export const canEdit = (p: Post) => p.targets.every((t) => ['DRAFT', 'SCHEDULED', 'FAILED', 'CANCELLED'].includes(t.status))
export const canMove = (p: Post) => p.status === 'DRAFT' || p.status === 'SCHEDULED'

// ---- captions and checks (mirror backend PlatformRules) -----------------------------------------

const HASHTAG = /(?<![\p{L}\p{M}\p{N}_])#[\p{L}\p{N}_][\p{L}\p{M}\p{N}_]*/gu

export function countHashtags(text: string): number {
  return (text.match(HASHTAG) ?? []).length
}

/** Joins the shared caption and hashtags the way each platform shows them. */
export function composeCaption(base: string, hashtags: string): string {
  const tags = hashtags
    .split(/[\s,]+/)
    .map((t) => t.trim())
    .filter(Boolean)
    .map((t) => (t.startsWith('#') ? t : `#${t}`))
  const uniq = [...new Set(tags)]
  return [base.trim(), uniq.join(' ')].filter(Boolean).join('\n\n')
}

/** Starting settings for a newly selected account. TikTok privacy has no default, as TikTok requires. */
export function defaultSettings(platform: SocialPlatform, title: string, caption: string): Settings {
  switch (platform) {
    case 'YOUTUBE':
      return { title: title.slice(0, 100), description: caption, tags: [], privacy: 'private', categoryId: '22', aiGenerated: false, notifySubscribers: true }
    case 'FACEBOOK':
      return { description: caption, title: '' }
    case 'INSTAGRAM':
      return { caption, shareToFeed: true }
    case 'TIKTOK':
      return { mode: 'DIRECT', caption, allowComment: false, allowDuet: false, allowStitch: false, brandContent: false, brandOrganic: false, aiGenerated: false }
  }
}

const str = (v: unknown) => (typeof v === 'string' ? v : '')

/** Problems the backend would also reject, shown before submitting. */
export function checkTarget(platform: SocialPlatform, s: Settings, media?: MediaAsset, creator?: CreatorInfo): string[] {
  const e: string[] = []
  const sec = media?.durationMs != null ? media.durationMs / 1000 : undefined
  switch (platform) {
    case 'YOUTUBE': {
      const title = str(s.title).trim()
      if (!title) e.push('Add a title')
      if (title.length > 100) e.push('Title: at most 100 characters')
      if (/[<>]/.test(title + str(s.description))) e.push('YouTube does not allow < or > in the title or description')
      if (new TextEncoder().encode(str(s.description)).length > 5000) e.push('Description: at most 5000 bytes')
      if (typeof s.madeForKids !== 'boolean') e.push('Say whether the video is made for kids')
      break
    }
    case 'FACEBOOK':
      if (sec != null && (sec < 3 || sec > 90)) e.push(`Page reels must be 3–90 seconds (this video is ${Math.round(sec)} s)`)
      if (media?.width && media?.height && (Math.min(media.width, media.height) < 540 || Math.max(media.width, media.height) < 960)) {
        e.push('Page reels need at least 540 × 960 pixels')
      }
      break
    case 'INSTAGRAM':
      if (str(s.caption).length > 2200) e.push('Caption: at most 2200 characters')
      if (countHashtags(str(s.caption)) > 30) e.push('At most 30 hashtags')
      if (sec != null && (sec < 3 || sec > 900)) e.push('Reels must be 3 seconds to 15 minutes')
      if (media && media.sizeBytes > 300 * 1024 * 1024) e.push('Reels can be at most 300 MB')
      break
    case 'TIKTOK':
      if (str(s.caption).length > 2200) e.push('Caption: at most 2200 characters')
      if (s.mode === 'INBOX') break
      if (!s.privacy) e.push('Choose who can see the post')
      if (creator && s.privacy && !creator.privacyOptions.includes(str(s.privacy))) e.push('That privacy option is not available for this account')
      if (creator && creator.maxVideoSeconds > 0 && sec != null && sec > creator.maxVideoSeconds) {
        e.push(`This account can post videos up to ${creator.maxVideoSeconds} seconds`)
      }
      if (s.brandContent && s.privacy === 'SELF_ONLY') e.push('Branded content cannot be set to Only me')
      if (!s.musicConsent) e.push('Agree to the TikTok terms below')
      break
  }
  return e
}

// ---- dates (local time; weeks start on Monday) -----------------------------------------------

export function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

export function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes(), d.getSeconds())
}

export function startOfWeek(d: Date): Date {
  const day = (d.getDay() + 6) % 7 // Monday = 0
  return startOfDay(addDays(d, -day))
}

/** Six full weeks covering the month, Monday first. */
export function monthGrid(year: number, month: number): Date[] {
  const first = startOfWeek(new Date(year, month, 1))
  return Array.from({ length: 42 }, (_, i) => addDays(first, i))
}

export function weekDays(d: Date): Date[] {
  const first = startOfWeek(d)
  return Array.from({ length: 7 }, (_, i) => addDays(first, i))
}

export function dayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** The date a post sits on in the calendar. */
export function postDate(p: Post): string | undefined {
  return p.publishedAt ?? p.scheduledAt
}

export function groupByDay(posts: Post[]): Map<string, Post[]> {
  const out = new Map<string, Post[]>()
  for (const p of [...posts].sort((a, b) => (postDate(a) ?? '').localeCompare(postDate(b) ?? ''))) {
    const at = postDate(p)
    if (!at) continue
    const key = dayKey(new Date(at))
    out.set(key, [...(out.get(key) ?? []), p])
  }
  return out
}

/** Moves a post to another day and keeps its time of day. */
export function moveToDay(iso: string | undefined, day: Date): Date {
  const from = iso ? new Date(iso) : new Date(day.getFullYear(), day.getMonth(), day.getDate(), 9, 0)
  return new Date(day.getFullYear(), day.getMonth(), day.getDate(), from.getHours(), from.getMinutes())
}

/** ISO instant → value for <input type="datetime-local">. */
export function toLocalInput(iso?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

export function fromLocalInput(value: string): string | undefined {
  if (!value) return undefined
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

export function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
}
