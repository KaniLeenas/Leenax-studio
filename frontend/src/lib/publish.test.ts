import { describe, expect, it } from 'vitest'
import {
  canEdit,
  checkTarget,
  composeCaption,
  countHashtags,
  dayKey,
  defaultSettings,
  fromLocalInput,
  groupByDay,
  monthGrid,
  moveToDay,
  startOfWeek,
  toLocalInput,
  weekDays,
  type Post,
} from './publish'
import type { MediaAsset } from './types'

const video = (over: Partial<MediaAsset> = {}): MediaAsset => ({
  id: 'm1', kind: 'VIDEO', source: 'UPLOAD', filename: 'a.mp4', contentType: 'video/mp4', sizeBytes: 10_000_000,
  width: 1080, height: 1920, durationMs: 30_000, createdAt: '2026-11-01T00:00:00Z', url: '/x', ...over,
})

const post = (over: Partial<Post>): Post => ({
  id: 'p', title: 'Post', status: 'SCHEDULED', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', version: 0, targets: [], ...over,
})

describe('captions', () => {
  it('joins caption and hashtags, adding # and dropping repeats', () => {
    expect(composeCaption('Sunrise at Ella', 'srilanka, #travel travel')).toBe('Sunrise at Ella\n\n#srilanka #travel')
    expect(composeCaption('', 'a')).toBe('#a')
  })

  it('counts hashtags like Instagram does', () => {
    expect(countHashtags('#one #two mail@x.com a#b #மலை')).toBe(3)
  })
})

describe('platform checks', () => {
  it('YouTube needs a title and an explicit audience answer', () => {
    const s = defaultSettings('YOUTUBE', '', 'desc')
    expect(checkTarget('YOUTUBE', s, video())).toEqual(['Add a title', 'Say whether the video is made for kids'])
    expect(checkTarget('YOUTUBE', { ...s, title: 'Ok', madeForKids: false }, video())).toEqual([])
  })

  it('Facebook reels must be 3–90 seconds; Instagram reels up to 15 minutes', () => {
    const long = video({ durationMs: 120_000 })
    expect(checkTarget('FACEBOOK', {}, long)).toHaveLength(1)
    expect(checkTarget('INSTAGRAM', { caption: '' }, long)).toEqual([])
    expect(checkTarget('INSTAGRAM', { caption: '' }, video({ sizeBytes: 400 * 1024 * 1024 }))).toHaveLength(1)
  })

  it('TikTok has no default privacy and checks the creator options', () => {
    const s = defaultSettings('TIKTOK', '', 'hi')
    expect(s.privacy).toBeUndefined()
    expect(s.allowComment).toBe(false)
    const creator = { privacyOptions: ['SELF_ONLY'], commentDisabled: false, duetDisabled: false, stitchDisabled: false, maxVideoSeconds: 20 }
    expect(checkTarget('TIKTOK', s, video(), creator)).toEqual(['Choose who can see the post', 'This account can post videos up to 20 seconds', 'Agree to the TikTok terms below'])
    expect(checkTarget('TIKTOK', { ...s, privacy: 'PUBLIC_TO_EVERYONE', musicConsent: true }, video({ durationMs: 10_000 }), creator))
      .toEqual(['That privacy option is not available for this account'])
    expect(checkTarget('TIKTOK', { mode: 'INBOX', caption: '' }, video(), creator)).toEqual([])
  })
})

describe('calendar dates', () => {
  it('starts weeks on Monday and shows six weeks per month', () => {
    const grid = monthGrid(2026, 10) // November 2026 starts on a Sunday
    expect(grid).toHaveLength(42)
    expect(dayKey(grid[0])).toBe('2026-10-26')
    expect(grid[0].getDay()).toBe(1)
    expect(dayKey(startOfWeek(new Date(2026, 10, 19)))).toBe('2026-11-16')
    expect(weekDays(new Date(2026, 10, 22)).map(dayKey)[6]).toBe('2026-11-22')
  })

  it('moves a post to another day at the same time of day', () => {
    const iso = new Date(2026, 10, 19, 18, 30).toISOString()
    const moved = moveToDay(iso, new Date(2026, 10, 25))
    expect([moved.getDate(), moved.getHours(), moved.getMinutes()]).toEqual([25, 18, 30])
    expect(moveToDay(undefined, new Date(2026, 10, 25)).getHours()).toBe(9)
  })

  it('groups posts by their published or scheduled day', () => {
    const a = post({ id: 'a', scheduledAt: new Date(2026, 10, 19, 20).toISOString() })
    const b = post({ id: 'b', scheduledAt: new Date(2026, 10, 19, 8).toISOString() })
    const c = post({ id: 'c', status: 'PUBLISHED', scheduledAt: new Date(2026, 10, 18).toISOString(), publishedAt: new Date(2026, 10, 20, 7).toISOString() })
    const d = post({ id: 'd', status: 'DRAFT' })
    const g = groupByDay([a, b, c, d])
    expect(g.get('2026-11-19')?.map((p) => p.id)).toEqual(['b', 'a'])
    expect(g.get('2026-11-20')?.map((p) => p.id)).toEqual(['c'])
    expect([...g.values()].flat()).toHaveLength(3)
  })

  it('round-trips datetime-local values', () => {
    const iso = new Date(2026, 10, 19, 9, 5).toISOString()
    expect(toLocalInput(iso)).toBe('2026-11-19T09:05')
    expect(fromLocalInput('2026-11-19T09:05')).toBe(iso)
    expect(fromLocalInput('')).toBeUndefined()
  })

  it('only allows editing before anything is uploaded', () => {
    const t = { id: 't', platform: 'YOUTUBE' as const, accountName: 'C', settings: {}, progress: 0, attempts: 0 }
    expect(canEdit(post({ targets: [{ ...t, status: 'SCHEDULED' }, { ...t, status: 'FAILED' }] }))).toBe(true)
    expect(canEdit(post({ targets: [{ ...t, status: 'UPLOADING' }] }))).toBe(false)
  })
})

describe('Tamil hashtags', () => {
  it('counts whole Tamil words and ignores # inside a word', () => {
    expect(countHashtags('#தமிழ் #மதுரை food #madurai')).toBe(3)
    expect(countHashtags('சாப்பாடு#தமிழ் #உணவு')).toBe(1)
  })
})
