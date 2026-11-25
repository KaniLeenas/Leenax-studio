import { CalendarClock, FileText, Send, Share2 } from 'lucide-react'
import { useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { PlatformMark, PostDialog, StatusBadge } from '../components/publish'
import { Card, MediaField } from '../components/studio'
import { Button, EmptyState, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { formatDuration } from '../lib/format'
import {
  checkTarget,
  composeCaption,
  countHashtags,
  defaultSettings,
  formatWhen,
  fromLocalInput,
  isPublicTarget,
  PLATFORM,
  PLATFORM_ORDER,
  TIKTOK_PRIVACY_LABEL,
  toLocalInput,
  type CreatorInfo,
  type Post,
  type PublishAction,
  type Settings,
  type SocialAccount,
} from '../lib/publish'
import { useMediaAsset, usePost, usePosts, useSavePost, useSocialAccounts, useTikTokCreator } from '../lib/publishQueries'
import type { MediaAsset } from '../lib/types'
import { toast } from '../stores/ui'

export function PublisherPage() {
  const [params] = useSearchParams()
  const editId = params.get('edit') ?? undefined
  const existing = usePost(editId)
  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">{editId ? 'Edit post' : 'Publisher'}</h1>
        <p className="text-muted mt-1 max-w-2xl">
          One video, every platform: write each platform's caption and settings, then post now, schedule it, or keep it as a draft.
        </p>
      </header>
      <div className="grid xl:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] gap-8 items-start">
        {editId && existing.isPending && <Spinner label="Opening the post" />}
        {editId && existing.error && <ErrorNotice error={existing.error} onRetry={() => existing.refetch()} />}
        {(!editId || existing.data) && <Composer key={editId ?? 'new'} existing={existing.data} />}
        <RecentPosts />
      </div>
    </>
  )
}

// ---- the composer -------------------------------------------------------------------------------

type When = PublishAction

function Composer({ existing }: { existing?: Post }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const accounts = useSocialAccounts()
  const save = useSavePost()
  const presetMedia = useMediaAsset(existing ? undefined : params.get('media') ?? undefined)
  const existingThumb = useMediaAsset(existing?.thumbnailMediaId)

  const [titleState, setTitle] = useState<string | undefined>(existing?.title)
  const [mediaState, setMedia] = useState<MediaAsset | undefined>(existing?.media)
  const [thumbState, setThumbnail] = useState<MediaAsset | null | undefined>()
  // A video passed in the address (?media=) and a saved thumbnail fill in until the user picks something else.
  const media = mediaState ?? presetMedia.data
  const thumbnail = thumbState === null ? undefined : thumbState ?? existingThumb.data
  const title = titleState ?? (presetMedia.data ? presetMedia.data.filename.replace(/\.[^.]+$/, '') : '')
  const [selected, setSelected] = useState<string[]>(existing?.targets.flatMap((t) => (t.accountId ? [t.accountId] : [])) ?? [])
  const [settings, setSettings] = useState<Record<string, Settings>>(() =>
    Object.fromEntries(existing?.targets.flatMap((t) => (t.accountId ? [[t.accountId, uiSettings(t.settings)]] : [])) ?? []),
  )
  const [caption, setCaption] = useState('')
  const [hashtags, setHashtags] = useState('')
  const [when, setWhen] = useState<When>(existing?.status === 'SCHEDULED' ? 'SCHEDULE' : existing ? 'DRAFT' : 'NOW')
  const [at, setAt] = useState(toLocalInput(existing?.scheduledAt))
  const [tab, setTab] = useState<string | undefined>(selected[0])


  const byId = useMemo(() => new Map((accounts.data ?? []).map((a) => [a.id, a])), [accounts.data])
  const chosen = selected.flatMap((id) => (byId.has(id) ? [byId.get(id)!] : []))
  const composed = composeCaption(caption, hashtags)

  const toggle = (a: SocialAccount) => {
    if (selected.includes(a.id)) {
      const next = selected.filter((x) => x !== a.id)
      setSelected(next)
      if (tab === a.id) setTab(next[0])
      return
    }
    setSelected([...selected, a.id])
    setSettings((s) => (s[a.id] ? s : { ...s, [a.id]: defaultSettings(a.platform, title, composed) }))
    setTab(a.id)
  }

  const applyCaptionToAll = () => {
    setSettings((s) => {
      const next = { ...s }
      for (const a of chosen) next[a.id] = { ...next[a.id], [PLATFORM[a.platform].captionKey]: composed }
      return next
    })
    toast.info('Caption copied to every platform')
  }

  const patch = (id: string, change: Settings) => setSettings((s) => ({ ...s, [id]: { ...s[id], ...change } }))

  // Fresh creator info is needed for the TikTok checks (and must be shown before posting).
  const tiktokIds = chosen.filter((a) => a.platform === 'TIKTOK').map((a) => a.id)
  const creator = useTikTokCreator(tiktokIds[0])
  const errors: Record<string, string[]> = Object.fromEntries(
    chosen.map((a) => {
      const v = settings[a.id] ?? {}
      const list = checkTarget(a.platform, apiSettings(v), media, a.id === tiktokIds[0] ? creator.data : undefined)
      if (v.disclose && !v.brandContent && !v.brandOrganic) list.push('Choose your brand or branded content, or turn disclosure off')
      return [a.id, list]
    }),
  )
  const problems = [
    ...(!media ? ['Choose a video'] : []),
    ...(!title.trim() ? ['Give the post a name'] : []),
    ...(chosen.length === 0 ? ['Choose at least one account'] : []),
    ...(when === 'SCHEDULE' && !fromLocalInput(at) ? ['Choose when to post'] : []),
    ...chosen.flatMap((a) => (errors[a.id] ?? []).map((e) => `${PLATFORM[a.platform].name} (${a.displayName}): ${e}`)),
    ...chosen.filter((a) => a.status !== 'CONNECTED').map((a) => `${a.displayName} needs to be reconnected`),
  ]
  // posting publicly is confirmed separately from choosing the setting
  const publicTargets = chosen.filter((a) => isPublicTarget(a.platform, apiSettings(settings[a.id] ?? {})))
  const [confirmPublic, setConfirmPublic] = useState(false)
  if (when !== 'DRAFT' && publicTargets.length > 0 && !confirmPublic) problems.push('Confirm that this will be posted publicly')
  const blocking = when === 'DRAFT' ? problems.filter((p) => p === 'Choose a video' || p === 'Give the post a name') : problems

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!media || blocking.length) return
    save.mutate(
      {
        id: existing?.id,
        body: {
          title: title.trim(),
          mediaId: media.id,
          thumbnailMediaId: thumbnail?.id,
          action: when,
          scheduledAt: when === 'NOW' ? undefined : fromLocalInput(at),
          targets: chosen.map((a) => ({ accountId: a.id, settings: apiSettings(settings[a.id] ?? {}) })),
          confirmPublic: when !== 'DRAFT' && publicTargets.length > 0 ? confirmPublic : undefined,
        },
      },
      {
        onSuccess: (post) => {
          toast.success(when === 'NOW' ? 'Publishing started' : when === 'SCHEDULE' ? `Scheduled for ${formatWhen(post.scheduledAt!)}` : 'Draft saved')
          navigate(when === 'NOW' ? '/publish/new' : '/publish/calendar', { replace: true })
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-6 min-w-0" noValidate>
      <Card label="Video">
        <div className="grid sm:grid-cols-2 gap-5">
          <MediaField label="Video" kind="VIDEO" value={media} onChange={(m) => { setMedia(m); if (m && !title) setTitle(m.filename.replace(/\.[^.]+$/, '')) }} pickerTitle="Choose the video to publish" />
          <MediaField label="Thumbnail (YouTube, optional)" kind="IMAGE" value={thumbnail} onChange={(m) => setThumbnail(m ?? null)} pickerTitle="Choose a thumbnail" />
        </div>
        {media && (
          <p className="text-xs text-faint -mt-2 tabular">
            {media.width && media.height ? `${media.width} × ${media.height}` : ''}
            {media.durationMs != null ? ` · ${formatDuration(media.durationMs)}` : ''}
            {media.width && media.height && media.height > media.width ? ' · vertical' : media.width && media.height ? ' · horizontal (Reels and TikTok are vertical)' : ''}
          </p>
        )}
        <Field label="Post name" hint="Only you see this, in the calendar and post list.">
          {(id, d) => <Input id={id} aria-describedby={d} value={title} maxLength={160} onChange={(e) => setTitle(e.target.value)} placeholder="Kandy trip — part 2" />}
        </Field>
      </Card>

      <Card label="Accounts">
        <h2 className="font-semibold -mb-2">Where to post</h2>
        {accounts.isPending && <Spinner label="Loading accounts" />}
        {accounts.error && <ErrorNotice error={accounts.error} onRetry={() => accounts.refetch()} />}
        {accounts.data && accounts.data.length === 0 && (
          <EmptyState
            title="No accounts connected"
            body="Connect YouTube, Facebook, Instagram or TikTok first."
            action={<Link to="/publish/accounts" className="inline-flex items-center gap-2 h-10 px-4 rounded-lg bg-marigold-500 text-[#1e2240] font-semibold"><Share2 className="size-4" aria-hidden />Connect accounts</Link>}
          />
        )}
        {accounts.data && accounts.data.length > 0 && (
          <ul className="grid sm:grid-cols-2 gap-2">
            {[...accounts.data].sort((a, b) => PLATFORM_ORDER.indexOf(a.platform) - PLATFORM_ORDER.indexOf(b.platform)).map((a) => {
              const on = selected.includes(a.id)
              return (
                <li key={a.id}>
                  <label className={cn('flex items-center gap-3 rounded-xl border px-3 py-2.5 cursor-pointer transition-colors', on ? 'border-marigold-500 bg-marigold-500/8' : 'border-line hover:bg-surface-2')}>
                    <input type="checkbox" checked={on} onChange={() => toggle(a)} className="accent-marigold-500 size-4" />
                    <PlatformMark platform={a.platform} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{a.displayName}</span>
                      {a.status !== 'CONNECTED' && <span className="block text-xs text-rose-signal">Needs reconnecting</span>}
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>
        )}
      </Card>

      {chosen.length > 0 && (
        <Card label="Captions">
          <div className="grid gap-4">
            <Field label="Caption for every platform" hint="Write it once, add hashtags, then copy it to each platform and adjust.">
              {(id, d) => <Textarea id={id} aria-describedby={d} value={caption} onChange={(e) => setCaption(e.target.value)} rows={3} />}
            </Field>
            <div className="flex flex-wrap items-end gap-3">
              <div className="flex-1 min-w-48">
                <Field label="Hashtags">
                  {(id) => <Input id={id} value={hashtags} onChange={(e) => setHashtags(e.target.value)} placeholder="srilanka travel kandy" />}
                </Field>
              </div>
              <Button type="button" onClick={applyCaptionToAll} disabled={!composed}>Copy to all platforms</Button>
            </div>
          </div>

          <div role="tablist" aria-label="Platform settings" className="flex gap-1 overflow-x-auto border-b border-line -mx-1 px-1">
            {chosen.map((a) => (
              <button
                key={a.id}
                type="button"
                role="tab"
                id={`ptab-${a.id}`}
                aria-selected={tab === a.id}
                aria-controls={`ppanel-${a.id}`}
                onClick={() => setTab(a.id)}
                className={cn('px-3 py-2.5 -mb-px border-b-2 text-sm font-medium whitespace-nowrap inline-flex items-center gap-2', tab === a.id ? 'border-marigold-500 text-ink' : 'border-transparent text-muted hover:text-ink')}
              >
                <PlatformMark platform={a.platform} className="size-5 text-[0.6rem]" />
                {a.displayName}
                {(errors[a.id]?.length ?? 0) > 0 && <span className="size-1.5 rounded-full bg-rose-signal" aria-label="has problems" />}
              </button>
            ))}
          </div>
          {chosen.map((a) =>
            tab === a.id ? (
              <section key={a.id} role="tabpanel" id={`ppanel-${a.id}`} aria-labelledby={`ptab-${a.id}`} className="flex flex-col gap-4">
                <TargetFields account={a} value={settings[a.id] ?? {}} onChange={(c) => patch(a.id, c)} media={media} creator={a.platform === 'TIKTOK' ? { query: creator, applies: a.id === tiktokIds[0] } : undefined} />
                {errors[a.id]?.length > 0 && (
                  <ul className="text-sm text-rose-signal list-disc pl-5">
                    {errors[a.id].map((e) => <li key={e}>{e}</li>)}
                  </ul>
                )}
              </section>
            ) : null,
          )}
        </Card>
      )}

      <Card label="When">
        <h2 className="font-semibold -mb-2">When</h2>
        <div role="radiogroup" aria-label="When to post" className="grid sm:grid-cols-3 gap-2">
          <WhenOption value="NOW" current={when} onChange={setWhen} icon={<Send className="size-4" />} title="Post now" body="Starts uploading straight away." />
          <WhenOption value="SCHEDULE" current={when} onChange={setWhen} icon={<CalendarClock className="size-4" />} title="Schedule" body="Goes out at the time you pick." />
          <WhenOption value="DRAFT" current={when} onChange={setWhen} icon={<FileText className="size-4" />} title="Save draft" body="Nothing is posted. Add a planned date if you like." />
        </div>
        {when !== 'NOW' && (
          <Field label={when === 'SCHEDULE' ? 'Post at' : 'Planned for (optional)'} hint={`Your time zone: ${Intl.DateTimeFormat().resolvedOptions().timeZone}. The studio must be running at that time.`}>
            {(id, d) => <Input id={id} aria-describedby={d} type="datetime-local" value={at} min={when === 'SCHEDULE' ? toLocalInput(new Date().toISOString()) : undefined} onChange={(e) => setAt(e.target.value)} className="max-w-64" />}
          </Field>
        )}
        {when !== 'DRAFT' && publicTargets.length > 0 && (
          <label className="flex items-start gap-2.5 rounded-xl border border-line p-3 text-sm">
            <input type="checkbox" className="mt-0.5 size-4 accent-marigold-500" checked={confirmPublic} onChange={(e) => setConfirmPublic(e.target.checked)} />
            <span>
              Anyone will be able to see this on {publicTargets.map((a) => `${PLATFORM[a.platform].name} (${a.displayName})`).join(', ')}.
              <span className="block text-xs text-muted mt-0.5">Your confirmation is recorded with the post. Changing the video or its settings afterwards needs a new one.</span>
            </span>
          </label>
        )}
        {blocking.length > 0 && (selected.length > 0 || media) && (
          <ul className="text-sm text-muted flex flex-col gap-0.5" aria-live="polite">
            {blocking.slice(0, 6).map((p) => <li key={p}>· {p}</li>)}
            {blocking.length > 6 && <li>· and {blocking.length - 6} more</li>}
          </ul>
        )}
        <div className="flex gap-2">
          <Button type="submit" variant="primary" loading={save.isPending} disabled={blocking.length > 0} icon={when === 'NOW' ? <Send className="size-4" /> : when === 'SCHEDULE' ? <CalendarClock className="size-4" /> : <FileText className="size-4" />}>
            {when === 'NOW' ? 'Publish now' : when === 'SCHEDULE' ? 'Schedule' : 'Save draft'}
          </Button>
          {existing && <Button type="button" variant="ghost" onClick={() => navigate('/publish/calendar')}>Discard changes</Button>}
        </div>
      </Card>
    </form>
  )
}

function WhenOption({ value, current, onChange, icon, title, body }: { value: When; current: When; onChange: (w: When) => void; icon: ReactNode; title: string; body: string }) {
  const on = value === current
  return (
    <button type="button" role="radio" aria-checked={on} onClick={() => onChange(value)} className={cn('text-left rounded-xl border p-3 transition-colors', on ? 'border-marigold-500 bg-marigold-500/8' : 'border-line hover:bg-surface-2')}>
      <span className="flex items-center gap-2 font-medium">{icon}{title}</span>
      <span className="block text-xs text-muted mt-1">{body}</span>
    </button>
  )
}

/** Tags are edited as text and sent as text (the backend splits on commas). */
function uiSettings(s: Settings): Settings {
  const out = { ...s }
  if (Array.isArray(out.tags)) out.tags = (out.tags as string[]).join(', ')
  if (out.brandContent || out.brandOrganic) out.disclose = true
  return out
}

function apiSettings(s: Settings): Settings {
  const { disclose: _disclose, ...rest } = s
  return rest
}

// ---- per-platform fields ----------------------------------------------------------------------------

interface FieldsProps {
  account: SocialAccount
  value: Settings
  onChange: (c: Settings) => void
  media?: MediaAsset
  creator?: { query: ReturnType<typeof useTikTokCreator>; applies: boolean }
}

function TargetFields(props: FieldsProps) {
  switch (props.account.platform) {
    case 'YOUTUBE':
      return <YouTubeFields {...props} />
    case 'FACEBOOK':
      return <FacebookFields {...props} />
    case 'INSTAGRAM':
      return <InstagramFields {...props} />
    case 'TIKTOK':
      return <TikTokFields {...props} />
  }
}

const s = (v: unknown) => (typeof v === 'string' ? v : '')

function Counted({ value, max, extra }: { value: string; max: number; extra?: string }) {
  const over = value.length > max
  return <span className={cn('tabular', over ? 'text-rose-signal' : 'text-faint')}>{value.length}/{max}{extra ? ` · ${extra}` : ''}</span>
}

function CaptionField({ label, value, max, onChange, hashtagsMax }: { label: string; value: string; max: number; onChange: (v: string) => void; hashtagsMax?: number }) {
  const tags = countHashtags(value)
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex justify-between text-sm">
        <span className="font-medium">{label}</span>
        <Counted value={value} max={max} extra={hashtagsMax ? `${tags}/${hashtagsMax} hashtags` : undefined} />
      </div>
      <Textarea aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} rows={5} />
    </div>
  )
}

function Check({ label, checked, onChange, disabled, hint }: { label: string; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; hint?: string }) {
  return (
    <label className={cn('flex items-start gap-2.5 text-sm', disabled && 'opacity-60')}>
      <input type="checkbox" className="accent-marigold-500 size-4 mt-0.5" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span>
        {label}
        {hint && <span className="block text-xs text-faint">{hint}</span>}
      </span>
    </label>
  )
}

function YouTubeFields({ value: v, onChange }: FieldsProps) {
  return (
    <>
      <div className="flex flex-col gap-1.5">
        <div className="flex justify-between text-sm"><span className="font-medium">Title</span><Counted value={s(v.title)} max={100} /></div>
        <Input aria-label="YouTube title" value={s(v.title)} onChange={(e) => onChange({ title: e.target.value })} />
      </div>
      <CaptionField label="Description" value={s(v.description)} max={5000} onChange={(d) => onChange({ description: d })} />
      <Field label="Tags" hint="Separated by commas. 500 characters in total.">
        {(id, d) => <Input id={id} aria-describedby={d} value={s(v.tags)} onChange={(e) => onChange({ tags: e.target.value })} />}
      </Field>
      <div className="grid sm:grid-cols-2 gap-4">
        <Field label="Visibility" hint="Until your Google project passes YouTube's audit, YouTube keeps API uploads private.">
          {(id, d) => (
            <Select id={id} aria-describedby={d} value={s(v.privacy)} onChange={(e) => onChange({ privacy: e.target.value })}>
              <option value="private">Private</option>
              <option value="unlisted">Unlisted</option>
              <option value="public">Public</option>
            </Select>
          )}
        </Field>
        <Field label="Category">
          {(id) => (
            <Select id={id} value={s(v.categoryId) || '22'} onChange={(e) => onChange({ categoryId: e.target.value })}>
              {YT_CATEGORIES.map(([cid, name]) => <option key={cid} value={cid}>{name}</option>)}
            </Select>
          )}
        </Field>
      </div>
      <fieldset className="flex flex-col gap-2">
        <legend className="text-sm font-medium mb-1.5">Audience (required by YouTube)</legend>
        <label className="flex items-center gap-2 text-sm"><input type="radio" name="kids" className="accent-marigold-500" checked={v.madeForKids === false} onChange={() => onChange({ madeForKids: false })} />No, it's not made for kids</label>
        <label className="flex items-center gap-2 text-sm"><input type="radio" name="kids" className="accent-marigold-500" checked={v.madeForKids === true} onChange={() => onChange({ madeForKids: true })} />Yes, it's made for kids</label>
      </fieldset>
      <Check label="Contains realistic altered or synthetic content" hint="YouTube asks you to disclose realistic AI-generated people, places or events." checked={v.aiGenerated === true} onChange={(c) => onChange({ aiGenerated: c })} />
    </>
  )
}

const YT_CATEGORIES: [string, string][] = [
  ['1', 'Film & Animation'], ['2', 'Autos & Vehicles'], ['10', 'Music'], ['15', 'Pets & Animals'], ['17', 'Sports'],
  ['19', 'Travel & Events'], ['20', 'Gaming'], ['22', 'People & Blogs'], ['23', 'Comedy'], ['24', 'Entertainment'],
  ['25', 'News & Politics'], ['26', 'Howto & Style'], ['27', 'Education'], ['28', 'Science & Technology'], ['29', 'Nonprofits & Activism'],
]

function FacebookFields({ value: v, onChange, media }: FieldsProps) {
  return (
    <>
      <p className="text-sm text-muted">Posts to the Page as a Reel: vertical, 3–90 seconds{media?.durationMs != null ? ` (this video: ${Math.round(media.durationMs / 1000)} s)` : ''}.</p>
      <CaptionField label="Description" value={s(v.description)} max={5000} onChange={(d) => onChange({ description: d })} />
    </>
  )
}

function InstagramFields({ value: v, onChange }: FieldsProps) {
  return (
    <>
      <p className="text-sm text-muted">Posts as a Reel (3 seconds to 15 minutes, up to 300 MB).</p>
      <CaptionField label="Caption" value={s(v.caption)} max={2200} hashtagsMax={30} onChange={(c) => onChange({ caption: c })} />
      <div className="grid sm:grid-cols-2 gap-4 items-end">
        <Field label="Cover frame (seconds into the video)" hint="Optional.">
          {(id, d) => <Input id={id} aria-describedby={d} type="number" min={0} step={0.5} value={v.coverSeconds == null ? '' : String(v.coverSeconds)} onChange={(e) => onChange({ coverSeconds: e.target.value === '' ? undefined : Number(e.target.value) })} />}
        </Field>
        <Check label="Also show in the profile grid" checked={v.shareToFeed !== false} onChange={(c) => onChange({ shareToFeed: c })} />
      </div>
    </>
  )
}

function TikTokFields({ value: v, onChange, creator, media }: FieldsProps) {
  const inbox = v.mode === 'INBOX'
  const info: CreatorInfo | undefined = creator?.applies ? creator.query.data : undefined
  const disclose = v.disclose === true
  return (
    <>
      <fieldset className="flex flex-col gap-2">
        <legend className="text-sm font-medium mb-1.5">How to post</legend>
        <label className="flex items-center gap-2 text-sm"><input type="radio" name="ttmode" className="accent-marigold-500" checked={!inbox} onChange={() => onChange({ mode: 'DIRECT' })} />Post directly to TikTok</label>
        <label className="flex items-center gap-2 text-sm"><input type="radio" name="ttmode" className="accent-marigold-500" checked={inbox} onChange={() => onChange({ mode: 'INBOX' })} />Send to the TikTok app and finish there (sounds, effects, privacy)</label>
      </fieldset>
      <CaptionField label="Caption" value={s(v.caption)} max={2200} onChange={(c) => onChange({ caption: c })} />
      {inbox ? (
        <p className="text-sm text-muted">TikTok sends you a notification; open it to add sounds and choose who can see the post.</p>
      ) : !creator?.applies ? (
        <p className="text-sm text-muted">Direct posting checks one TikTok account at a time; use “Send to the TikTok app” for a second account.</p>
      ) : creator.query.isPending ? (
        <Spinner label="Checking your TikTok account" />
      ) : creator.query.error ? (
        <ErrorNotice error={creator.query.error} onRetry={() => creator.query.refetch()} />
      ) : info ? (
        <>
          <p className="text-sm">Posting as <strong>{info.nickname ?? info.username}</strong>{info.username ? <span className="text-faint"> @{info.username}</span> : null}
            {info.maxVideoSeconds > 0 && <span className="text-faint"> · videos up to {formatDuration(info.maxVideoSeconds * 1000)}{media?.durationMs != null && media.durationMs / 1000 > info.maxVideoSeconds ? ' (this one is longer)' : ''}</span>}
          </p>
          <Field label="Who can see this post">
            {(id) => (
              <Select id={id} value={s(v.privacy)} onChange={(e) => onChange({ privacy: e.target.value || undefined })} required>
                <option value="">Choose…</option>
                {info.privacyOptions.map((p) => (
                  <option key={p} value={p} disabled={p === 'SELF_ONLY' && v.brandContent === true}>
                    {TIKTOK_PRIVACY_LABEL[p] ?? p}{p === 'SELF_ONLY' && v.brandContent === true ? ' (not for branded content)' : ''}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium mb-1.5">Allow people to</legend>
            <Check label="Comment" checked={!info.commentDisabled && v.allowComment === true} disabled={info.commentDisabled} hint={info.commentDisabled ? 'Turned off in your TikTok settings' : undefined} onChange={(c) => onChange({ allowComment: c })} />
            <Check label="Duet" checked={!info.duetDisabled && v.allowDuet === true} disabled={info.duetDisabled} hint={info.duetDisabled ? 'Turned off in your TikTok settings' : undefined} onChange={(c) => onChange({ allowDuet: c })} />
            <Check label="Stitch" checked={!info.stitchDisabled && v.allowStitch === true} disabled={info.stitchDisabled} hint={info.stitchDisabled ? 'Turned off in your TikTok settings' : undefined} onChange={(c) => onChange({ allowStitch: c })} />
          </fieldset>
          <fieldset className="flex flex-col gap-2 rounded-xl border border-line p-3">
            <Check label="Disclose commercial content" hint="Turn on if this video promotes yourself, a brand, product or service." checked={disclose} onChange={(c) => onChange(c ? { disclose: true } : { disclose: false, brandContent: false, brandOrganic: false })} />
            {disclose && (
              <div className="pl-6 flex flex-col gap-2">
                <Check label="Your brand" hint="You are promoting yourself or your own business. Labeled “Promotional content”." checked={v.brandOrganic === true} onChange={(c) => onChange({ brandOrganic: c })} />
                <Check label="Branded content" hint="You are promoting another brand or a third party. Labeled “Paid partnership”." checked={v.brandContent === true} onChange={(c) => onChange({ brandContent: c, ...(c && v.privacy === 'SELF_ONLY' ? { privacy: undefined } : {}) })} />
                {!v.brandContent && !v.brandOrganic && <p className="text-xs text-rose-signal">Choose at least one, or turn disclosure off.</p>}
              </div>
            )}
          </fieldset>
          <Check label="AI-generated content" hint="Adds TikTok's AI-generated label." checked={v.aiGenerated === true} onChange={(c) => onChange({ aiGenerated: c })} />
          <Check
            label={`I agree to TikTok's Music Usage Confirmation${v.brandContent ? ' and Branded Content Policy' : ''}`}
            checked={v.musicConsent === true}
            onChange={(c) => onChange({ musicConsent: c })}
          />
          <p className="text-xs text-faint">After posting, it can take a few minutes for the video to appear on your profile.</p>
        </>
      ) : null}
    </>
  )
}

// ---- recent posts --------------------------------------------------------------------------------------

function RecentPosts() {
  const posts = usePosts()
  const [open, setOpen] = useState<string>()
  const current = posts.data?.find((p) => p.id === open)
  return (
    <aside aria-labelledby="recent-heading" className="flex flex-col gap-3 xl:sticky xl:top-6">
      <div className="flex items-baseline justify-between">
        <h2 id="recent-heading" className="font-semibold">Recent posts</h2>
        <Link to="/publish/calendar" className="text-sm text-muted hover:text-ink">Calendar</Link>
      </div>
      {posts.isPending && <Spinner label="Loading posts" />}
      {posts.error && <ErrorNotice error={posts.error} onRetry={() => posts.refetch()} />}
      {posts.data?.length === 0 && <p className="text-sm text-muted">Nothing posted yet.</p>}
      <ul className="flex flex-col gap-2">
        {posts.data?.slice(0, 12).map((p) => {
          const when = p.publishedAt ?? p.scheduledAt
          return (
            <li key={p.id}>
              <button type="button" onClick={() => setOpen(p.id)} className="w-full text-left rounded-xl border border-line bg-surface hover:bg-surface-2 p-3 flex flex-col gap-2">
                <span className="flex items-start justify-between gap-2">
                  <span className="font-medium text-sm truncate">{p.title}</span>
                  <StatusBadge status={p.status} />
                </span>
                <span className="flex items-center gap-1.5">
                  {p.targets.map((t) => <PlatformMark key={t.id} platform={t.platform} className={cn('size-5 text-[0.6rem]', t.status === 'FAILED' && 'bg-rose-signal/12 text-rose-signal', t.status === 'PUBLISHED' && 'bg-peacock-500/15 text-peacock-600 dark:text-peacock-400')} />)}
                  {when && <span className="ml-auto text-xs text-faint">{formatWhen(when)}</span>}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      <PostDialog post={current} onClose={() => setOpen(undefined)} />
    </aside>
  )
}
