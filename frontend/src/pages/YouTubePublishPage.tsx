import { AlertTriangle, BarChart3, Check, ChevronLeft, ExternalLink, ListPlus, ShieldCheck, Upload } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { MediaField } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill } from '../components/ui'
import { cn } from '../lib/cn'
import { LEVEL_LABEL, type Level } from '../lib/materials'
import { fromLocalInput, toLocalInput } from '../lib/publish'
import { useProject } from '../lib/queries'
import type { MediaAsset } from '../lib/types'
import {
  authorisationSummary,
  finalDescription,
  PUBLICATION_LABEL,
  publicationTone,
  publishProblems,
  quotaEstimate,
  useChannelDetails,
  useCreatePlaylist,
  usePrepared,
  usePublish,
  usePublishOverview,
  VISIBILITY_HINT,
  VISIBILITY_LABEL,
  willBePublic,
  type PublishForm,
  type PublishOverview,
  type ReadyVideo,
  type Visibility,
  type YouTubeChannel,
} from '../lib/youtube'
import { toast } from '../stores/ui'

/**
 * /projects/:id/youtube — YouTube Publishing Manager. Approved lesson and quiz videos go to
 * YouTube with their approved title, description and tags, after the project owner (or an
 * administrator) authorises exactly what will be uploaded.
 */
export function YouTubePublishPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = usePublishOverview(id)
  const [publishing, setPublishing] = useState<ReadyVideo | null>(null)

  if (project.isPending || data.isPending) return <Spinner label="Loading YouTube publishing" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const d = data.data
  const connected = d.channels.filter((c) => c.status === 'CONNECTED')
  return (
    <>
      <Link to={`/projects/${id}/videos`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Lesson and quiz videos
      </Link>
      <PageHeader
        title="Publish to YouTube"
        description="Approved videos go out with the title, description and tags approved in the topic's SEO report. Nothing uploads until the project owner or an administrator authorises it."
        actions={
          <Link to={`/projects/${id}/youtube/analytics`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">
            <BarChart3 className="size-4" aria-hidden />
            YouTube analytics
          </Link>
        }
      />

      <section aria-labelledby="channels" className="mb-8">
        <h2 id="channels" className="font-semibold mb-3">Channels</h2>
        {d.channels.length === 0 ? (
          <EmptyState title="No YouTube channel connected" body="Connect the channel the videos should go to. Its sign-in stays on the server."
            action={<Link className="underline underline-offset-2" to="/publish/accounts">Connect a channel</Link>} />
        ) : (
          <ul className="grid sm:grid-cols-2 gap-3">
            {d.channels.map((c) => <ChannelCard key={c.id} channel={c} />)}
          </ul>
        )}
      </section>

      <section aria-labelledby="ready" className="mb-8">
        <h2 id="ready" className="font-semibold mb-1">Ready to publish</h2>
        <p className="text-sm text-muted mb-3">Approved videos whose render passed its file checks.</p>
        {!d.canAuthorise && d.videos.length > 0 && (
          <p role="note" className="text-sm rounded-xl border border-line px-4 py-3 mb-3">Only the project owner or an administrator can authorise YouTube publishing. You can see what's ready and how earlier publications went.</p>
        )}
        {d.videos.length === 0 ? (
          <EmptyState title="Nothing ready yet" body="A video appears here once its version is approved with a render that passed its checks."
            action={<Link className="underline underline-offset-2" to={`/projects/${id}/videos`}>Go to videos</Link>} />
        ) : (
          <ul className="divide-y divide-line border-y border-line">
            {d.videos.map((v) => (
              <li key={v.resourceId} className="py-3 flex flex-wrap items-center gap-3">
                <div className="min-w-0 flex-1">
                  <p className="font-medium break-words">{v.title}</p>
                  <p className="text-xs text-muted">
                    {v.kind === 'LESSON' ? `Lesson video, ${LEVEL_LABEL[v.level as Level] ?? v.level}` : 'Quiz video'} · version {v.versionNo}
                    {v.captionsAvailable ? ' · captions available' : ''}
                    {v.publicationIds.length > 0 ? ` · published ${v.publicationIds.length === 1 ? 'once' : `${v.publicationIds.length} times`}` : ''}
                  </p>
                  {!v.seoApproved && <p className="text-xs text-rose-signal mt-0.5">Its SEO report isn't approved, so it has no approved title, description or tags yet.</p>}
                </div>
                {d.canAuthorise && (
                  <Button size="sm" variant="primary" icon={<Upload className="size-4" />} disabled={!v.seoApproved || connected.length === 0}
                    title={connected.length === 0 ? 'Connect a YouTube channel first' : undefined} onClick={() => setPublishing(v)}>
                    Publish
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="history">
        <h2 id="history" className="font-semibold mb-3">Publishing history</h2>
        <History overview={d} />
      </section>

      {publishing && <PublishDialog projectId={id} video={publishing} overview={d} onClose={() => setPublishing(null)} />}
    </>
  )
}

function ChannelCard({ channel: c }: { channel: YouTubeChannel }) {
  const missing = [!c.canManage && 'playlists and captions', !c.canReadAnalytics && 'analytics'].filter(Boolean)
  return (
    <li className="rounded-xl border border-line p-4 flex gap-3 min-w-0">
      {c.avatarUrl ? <img src={c.avatarUrl} alt="" className="size-10 rounded-full shrink-0" /> : <span aria-hidden className="size-10 rounded-full bg-surface-2 shrink-0" />}
      <div className="min-w-0">
        <p className="font-medium truncate">{c.name}</p>
        <p className="text-xs text-muted">{c.status === 'CONNECTED' ? 'Connected' : 'Needs reconnecting'}{c.username ? ` · ${c.username}` : ''}</p>
        {(c.status !== 'CONNECTED' || missing.length > 0) && (
          <p className="text-xs mt-1">
            {c.status !== 'CONNECTED' ? c.lastError ?? 'The sign-in expired.' : `Connected before ${missing.join(' and ')} were supported.`}{' '}
            <Link to="/publish/accounts" className="underline underline-offset-2">Reconnect</Link>
          </p>
        )}
      </div>
    </li>
  )
}

function History({ overview: d }: { overview: PublishOverview }) {
  const channels = new Map(d.channels.map((c) => [c.id, c.name]))
  if (d.publications.length === 0) return <p className="text-sm text-muted">Nothing published from this project yet.</p>
  return (
    <ul className="divide-y divide-line border-y border-line">
      {d.publications.map((p) => (
        <li key={p.id} className="py-3 flex flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="font-medium break-words flex-1 min-w-0">{p.title}</p>
            <StatusPill tone={publicationTone(p.status)}>{p.status ? PUBLICATION_LABEL[p.status] ?? p.status : 'Removed'}</StatusPill>
          </div>
          <p className="text-xs text-muted">
            {p.accountId ? channels.get(p.accountId) ?? 'A disconnected channel' : 'A disconnected channel'}
            {p.settings?.privacy ? ` · ${p.settings.publishAt ? `scheduled for ${new Date(String(p.settings.publishAt)).toLocaleString()}` : String(p.settings.privacy)}` : ''}
            {p.status === 'SCHEDULED' && p.runAt ? ` · uploads ${new Date(p.runAt).toLocaleString()}` : ''}
            {p.publishedAt ? ` · on YouTube since ${new Date(p.publishedAt).toLocaleString()}` : ''}
          </p>
          {p.authorisedAt && (
            <p className="text-xs text-muted flex items-start gap-1.5">
              <ShieldCheck className="size-3.5 shrink-0 mt-px text-peacock-600 dark:text-peacock-400" aria-hidden />
              <span>Authorised by {p.authorisedByName ?? 'someone no longer here'} on {new Date(p.authorisedAt).toLocaleString()}{p.rightsConfirmed ? ', rights confirmed' : ''}{p.publicConfirmed ? ', public confirmed' : ''}. {p.statement}</span>
            </p>
          )}
          {p.error && <p className="text-xs text-rose-signal">{p.error} {p.status === 'FAILED' && <Link to="/publish/calendar" className="underline underline-offset-2">Retry from the publishing calendar</Link>}</p>}
          {p.url && (
            <a href={p.url} target="_blank" rel="noreferrer noopener" className="text-xs inline-flex items-center gap-1 text-peacock-600 dark:text-peacock-400 hover:underline w-fit">
              Open on YouTube <ExternalLink className="size-3" aria-hidden />
            </a>
          )}
        </li>
      ))}
    </ul>
  )
}

// ---- the authorisation step ---------------------------------------------------------------------------

function PublishDialog({ projectId, video, overview, onClose }: { projectId: string; video: ReadyVideo; overview: PublishOverview; onClose: () => void }) {
  const prepared = usePrepared(projectId, video.resourceId)
  const connected = overview.channels.filter((c) => c.status === 'CONNECTED')
  const [accountId, setAccountId] = useState<string | undefined>(connected[0]?.id)
  const channel = overview.channels.find((c) => c.id === accountId)
  const details = useChannelDetails(projectId, accountId)
  const publish = usePublish(projectId)
  const [titleId, setTitleId] = useState<string>()
  const [visibility, setVisibility] = useState<Visibility>('private')
  const [publishAt, setPublishAt] = useState('')
  const [when, setWhen] = useState<'NOW' | 'SCHEDULE'>('NOW')
  const [uploadAt, setUploadAt] = useState('')
  const [thumbnail, setThumbnail] = useState<MediaAsset>()
  const [playlistIds, setPlaylistIds] = useState<string[]>([])
  const [addCaptions, setAddCaptions] = useState(true)
  const [addHashtags, setAddHashtags] = useState(false)
  const [madeForKids, setMadeForKids] = useState<boolean>()
  const [synthetic, setSynthetic] = useState<boolean>()
  const [notify, setNotify] = useState(true)
  const [rights, setRights] = useState(false)
  const [confirmPublic, setConfirmPublic] = useState(false)

  const p = prepared.data
  const chosenTitle = titleId ?? p?.titles.find((t) => t.chosen)?.id ?? p?.titles[0]?.id
  const captions = addCaptions && !!p?.captions
  const form: PublishForm = {
    accountId, titleId: chosenTitle, visibility, publishAt: fromLocalInput(publishAt), when, uploadAt: fromLocalInput(uploadAt),
    playlistIds, addCaptions: captions, madeForKids, alteredOrSynthetic: synthetic, rightsConfirmed: rights, confirmPublic,
  }
  const problems = publishProblems(form, p, channel)
  const units = quotaEstimate(overview.quotaUnits, { thumbnail: !!thumbnail, playlists: playlistIds.length, captions })
  const titleText = p?.titles.find((t) => t.id === chosenTitle)?.text
  const description = useMemo(() => (p ? finalDescription(p, addHashtags) : ''), [p, addHashtags])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!p || problems.length || !accountId || !chosenTitle || madeForKids === undefined || synthetic === undefined) return
    publish.mutate(
      {
        resourceId: video.resourceId, versionId: p.versionId, accountId, titleId: chosenTitle, visibility,
        publishAt: visibility === 'scheduled' ? fromLocalInput(publishAt) : undefined, when,
        uploadAt: when === 'SCHEDULE' ? fromLocalInput(uploadAt) : undefined, thumbnailMediaId: thumbnail?.id, playlistIds,
        addCaptions: captions, addHashtags, madeForKids, alteredOrSynthetic: synthetic, notifySubscribers: notify, categoryId: '27',
        rightsConfirmed: rights, confirmPublic: willBePublic(visibility) && confirmPublic,
      },
      {
        onSuccess: () => {
          toast.success(when === 'NOW' ? 'Authorised. The upload has started.' : 'Authorised. The upload is scheduled.')
          onClose()
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <Dialog open title={`Publish “${video.title}”`} onClose={onClose} size="lg"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="yt-publish" variant="primary" icon={<ShieldCheck className="size-4" />} loading={publish.isPending} disabled={problems.length > 0}>
            Authorise and publish
          </Button>
        </>
      }
    >
      {prepared.isPending && <Spinner label="Loading the approved metadata" />}
      {prepared.error && <ErrorNotice error={prepared.error} onRetry={() => prepared.refetch()} />}
      {p && (
        <form id="yt-publish" onSubmit={submit} className="flex flex-col gap-6" noValidate>
          <Field label="Channel">
            {(fid) => (
              <Select id={fid} value={accountId ?? ''} onChange={(e) => { setAccountId(e.target.value || undefined); setPlaylistIds([]) }}>
                {connected.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>
            )}
          </Field>
          {details.data && (
            <p className="text-xs text-muted -mt-4 tabular">
              {details.data.customUrl ? `${details.data.customUrl} · ` : ''}
              {details.data.subscribersHidden ? 'subscriber count hidden' : details.data.subscribers != null ? `${details.data.subscribers.toLocaleString()} subscribers` : ''}
              {details.data.videos != null ? ` · ${details.data.videos.toLocaleString()} videos` : ''}
            </p>
          )}
          {details.error && <p className="text-xs text-rose-signal -mt-4">{details.error.message}</p>}

          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium mb-1">Title <span className="font-normal text-muted">· approved in the SEO report</span></legend>
            {p.titles.map((t) => (
              <label key={t.id} className={cn('flex items-start gap-2.5 rounded-lg border p-2.5 text-sm cursor-pointer', chosenTitle === t.id ? 'border-marigold-500 bg-marigold-500/8' : 'border-line')}>
                <input type="radio" name="title" className="mt-0.5 accent-marigold-500" checked={chosenTitle === t.id} onChange={() => setTitleId(t.id)} />
                <span className="flex-1 break-words">{t.text}{t.chosen && <span className="text-xs text-muted"> · the report's choice</span>}</span>
                <span className={cn('text-xs tabular', t.text.length > 100 ? 'text-rose-signal' : 'text-faint')}>{t.text.length}/100</span>
              </label>
            ))}
          </fieldset>

          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium">Description <span className="font-normal text-muted">· approved, sent as shown</span></span>
            <pre className="whitespace-pre-wrap break-words rounded-lg border border-line bg-surface-2/50 p-3 text-sm font-sans max-h-48 overflow-y-auto">{description || '(empty)'}</pre>
            {p.hashtags.length > 0 && (
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="accent-marigold-500" checked={addHashtags} onChange={(e) => setAddHashtags(e.target.checked)} />
                Add the approved hashtags at the end ({p.hashtags.map((h) => (h.startsWith('#') ? h : `#${h}`)).join(' ')})
              </label>
            )}
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium">Tags <span className="font-normal text-muted">· approved</span></span>
            {p.tags.length === 0 ? <p className="text-sm text-muted">No tags</p> : (
              <ul className="flex flex-wrap gap-1.5">{p.tags.map((t) => <li key={t} className="rounded-full bg-surface-2 px-2.5 py-0.5 text-xs">{t}</li>)}</ul>
            )}
          </div>

          <MediaField label="Thumbnail (optional)" kind="IMAGE" value={thumbnail} onChange={setThumbnail} pickerTitle="Choose a thumbnail" />

          <Playlists projectId={projectId} accountId={accountId} channel={channel} ideas={p.playlistIdeas} selected={playlistIds} onChange={setPlaylistIds} />

          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium">Captions</span>
            {p.captions ? (
              <>
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" className="accent-marigold-500" checked={addCaptions} onChange={(e) => setAddCaptions(e.target.checked)} />
                  Upload the captions ({p.captions.name}, {p.captions.cues} lines, from the checked render)
                </label>
                <details className="text-xs text-muted">
                  <summary className="cursor-pointer">Preview the caption file</summary>
                  <pre className="whitespace-pre-wrap mt-1 max-h-40 overflow-y-auto rounded-lg border border-line p-2">{p.captions.srt.split('\n').slice(0, 16).join('\n')}</pre>
                </details>
              </>
            ) : <p className="text-sm text-muted">This render has no captions.</p>}
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium mb-1">Visibility</legend>
            <div role="radiogroup" className="grid sm:grid-cols-2 gap-2">
              {(['private', 'unlisted', 'public', 'scheduled'] as Visibility[]).map((v) => (
                <button key={v} type="button" role="radio" aria-checked={visibility === v} onClick={() => { setVisibility(v); setConfirmPublic(false) }}
                  className={cn('text-left rounded-xl border p-3', visibility === v ? 'border-marigold-500 bg-marigold-500/8' : 'border-line hover:bg-surface-2')}>
                  <span className="font-medium text-sm">{VISIBILITY_LABEL[v]}</span>
                  <span className="block text-xs text-muted mt-0.5">{VISIBILITY_HINT[v]}</span>
                </button>
              ))}
            </div>
            {visibility === 'scheduled' && (
              <Field label="Goes public at" hint={`Your time zone: ${Intl.DateTimeFormat().resolvedOptions().timeZone}. YouTube makes it public; the studio needn't be running.`}>
                {(fid, dsc) => <Input id={fid} aria-describedby={dsc} type="datetime-local" value={publishAt} min={toLocalInput(new Date().toISOString())} onChange={(e) => setPublishAt(e.target.value)} className="max-w-64" />}
              </Field>
            )}
          </fieldset>

          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium mb-1">Upload</legend>
            <div className="flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-2"><input type="radio" name="when" className="accent-marigold-500" checked={when === 'NOW'} onChange={() => setWhen('NOW')} />Now</label>
              <label className="flex items-center gap-2"><input type="radio" name="when" className="accent-marigold-500" checked={when === 'SCHEDULE'} onChange={() => setWhen('SCHEDULE')} />At a set time</label>
            </div>
            {when === 'SCHEDULE' && (
              <Field label="Upload at" hint="The studio must be running at that time.">
                {(fid, dsc) => <Input id={fid} aria-describedby={dsc} type="datetime-local" value={uploadAt} min={toLocalInput(new Date().toISOString())} onChange={(e) => setUploadAt(e.target.value)} className="max-w-64" />}
              </Field>
            )}
          </fieldset>

          <YesNo label="Made for kids?" hint="YouTube requires an answer. A lesson for university students usually isn't." value={madeForKids} onChange={setMadeForKids} />
          <YesNo label="Realistic altered or synthetic content?" hint="YouTube asks you to disclose realistic content made with AI that viewers could mistake for real people, places or events. A narrated slide lesson with a synthetic voice is usually no; decide for this video." value={synthetic} onChange={setSynthetic} />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" className="accent-marigold-500" checked={notify} onChange={(e) => setNotify(e.target.checked)} />
            Notify subscribers
          </label>

          <p className="text-xs text-muted tabular">
            Uses about {units.toLocaleString()} YouTube API units, of the {overview.quotaUnits.dailyDefault.toLocaleString()} a Google Cloud project gets a day by default. If the quota runs out, the upload is tried again later.
          </p>

          <section aria-label="Authorisation" className="rounded-xl border border-marigold-500/60 p-4 flex flex-col gap-3">
            <p className="text-sm font-medium flex items-center gap-2"><ShieldCheck className="size-4" aria-hidden />Your authorisation</p>
            <p className="text-sm">{authorisationSummary(titleText, channel?.name, form)}</p>
            <label className="flex items-start gap-2.5 text-sm">
              <input type="checkbox" className="mt-0.5 size-4 accent-marigold-500" checked={rights} onChange={(e) => setRights(e.target.checked)} />
              <span>I hold the rights to publish everything in this video: its slides, images, voice and music.</span>
            </label>
            {willBePublic(visibility) && (
              <label className="flex items-start gap-2.5 text-sm">
                <input type="checkbox" className="mt-0.5 size-4 accent-marigold-500" checked={confirmPublic} onChange={(e) => setConfirmPublic(e.target.checked)} />
                <span>{visibility === 'scheduled' ? 'I confirm that YouTube will make this video public at that time.' : 'I confirm that anyone will be able to find and watch this video.'}</span>
              </label>
            )}
            <p className="text-xs text-muted">Your name, the time and this statement are recorded. Changing the video or its settings afterwards needs a new authorisation.</p>
            {problems.length > 0 && (
              <ul className="text-sm text-muted flex flex-col gap-0.5" aria-live="polite">
                {problems.slice(0, 5).map((x) => <li key={x}>· {x}</li>)}
              </ul>
            )}
          </section>
        </form>
      )}
    </Dialog>
  )
}

function YesNo({ label, hint, value, onChange }: { label: string; hint: string; value?: boolean; onChange: (v: boolean) => void }) {
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="text-sm font-medium">{label}</legend>
      <p className="text-xs text-muted">{hint}</p>
      <div className="flex gap-4 text-sm mt-1">
        <label className="flex items-center gap-2"><input type="radio" className="accent-marigold-500" checked={value === true} onChange={() => onChange(true)} />Yes</label>
        <label className="flex items-center gap-2"><input type="radio" className="accent-marigold-500" checked={value === false} onChange={() => onChange(false)} />No</label>
      </div>
    </fieldset>
  )
}

function Playlists({ projectId, accountId, channel, ideas, selected, onChange }: {
  projectId: string; accountId?: string; channel?: YouTubeChannel; ideas: string[]; selected: string[]; onChange: (ids: string[]) => void
}) {
  const details = useChannelDetails(projectId, accountId)
  const create = useCreatePlaylist(projectId, accountId)
  const [title, setTitle] = useState('')
  const [privacy, setPrivacy] = useState<'public' | 'unlisted' | 'private'>('public')
  const lists = details.data?.playlists ?? []
  const toggle = (id: string) => onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-sm font-medium">Playlists</span>
      {channel && !channel.canManage ? (
        <p className="text-sm text-muted">Reconnect {channel.name} in <Link to="/publish/accounts" className="underline underline-offset-2">Accounts</Link> to add videos to playlists.</p>
      ) : details.isPending && accountId ? (
        <Spinner label="Loading playlists" />
      ) : (
        <>
          {details.data?.note && <p className="text-xs text-rose-signal">{details.data.note}</p>}
          {lists.length === 0 ? <p className="text-sm text-muted">This channel has no playlists yet.</p> : (
            <ul className="flex flex-col gap-1 max-h-40 overflow-y-auto">
              {lists.map((l) => (
                <li key={l.id}>
                  <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" className="accent-marigold-500" checked={selected.includes(l.id)} onChange={() => toggle(l.id)} />
                    <span className="flex-1 truncate">{l.title}</span>
                    <span className="text-xs text-faint tabular">{l.items} videos · {l.privacy}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          <div className="flex flex-wrap items-end gap-2 mt-1">
            <Field label="New playlist">
              {(fid) => <Input id={fid} value={title} maxLength={150} onChange={(e) => setTitle(e.target.value)} placeholder={ideas[0] ?? 'Playlist title'} className="w-64" />}
            </Field>
            <Select aria-label="Playlist visibility" value={privacy} onChange={(e) => setPrivacy(e.target.value as typeof privacy)} className="w-32">
              <option value="public">Public</option>
              <option value="unlisted">Unlisted</option>
              <option value="private">Private</option>
            </Select>
            <Button type="button" size="sm" icon={<ListPlus className="size-4" />} loading={create.isPending} disabled={!title.trim() || !accountId}
              onClick={() => create.mutate({ title: title.trim(), privacy }, {
                onSuccess: (pl) => { onChange([...selected, pl.id]); setTitle(''); toast.success(`Playlist “${pl.title}” created on YouTube`) },
                onError: (e) => toast.error(e.message),
              })}>
              Create
            </Button>
          </div>
          {ideas.length > 0 && (
            <p className="text-xs text-muted">
              Ideas from the SEO report:{' '}
              {ideas.slice(0, 4).map((i, n) => (
                <span key={i}>
                  {n > 0 && ', '}
                  <button type="button" className="underline underline-offset-2 hover:text-ink" onClick={() => setTitle(i)}>{i}</button>
                </span>
              ))}
            </p>
          )}
        </>
      )}
      {selected.length > 0 && <p className="text-xs text-muted flex items-center gap-1"><Check className="size-3" aria-hidden />Added to {selected.length} playlist{selected.length === 1 ? '' : 's'} after the upload</p>}
      {selected.length > 5 && <p className="text-xs text-rose-signal flex items-center gap-1"><AlertTriangle className="size-3" aria-hidden />At most 5 playlists</p>}
    </div>
  )
}
