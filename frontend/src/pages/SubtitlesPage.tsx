import { Download, Languages, Plus, Save, Trash2, Wand2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { AccountSelect, Card, ConnectAccount, MediaField, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { useTextModels } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import {
  trackExportUrl,
  useCreateTrack,
  useDeleteTrack,
  useSaveTrack,
  useTrack,
  useTracks,
  useTranscribe,
  useTranslateTrack,
} from '../lib/studioQueries'
import {
  activeCueIndex,
  cueProblems,
  formatCueTime,
  insertCueAfter,
  languageLabel,
  parseCueTime,
  shiftCues,
  SUBTITLE_LANGUAGES,
  toVtt,
} from '../lib/subtitles'
import type { Cue, MediaAsset, SubtitleTrack } from '../lib/types'
import { toast } from '../stores/ui'

const SOURCE_LABEL = { TRANSCRIBED: 'Auto', TRANSLATED: 'Translated', MANUAL: 'Manual' } as const

export function SubtitlesPage() {
  const [media, setMedia] = useState<MediaAsset>()
  const [trackId, setTrackId] = useState<string>()
  const tracks = useTracks(media?.id)

  useEffect(() => {
    if (!tracks.data) return
    if (trackId && tracks.data.some((t) => t.id === trackId)) return
    setTrackId(tracks.data[0]?.id)
  }, [tracks.data, trackId])

  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Subtitles</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Turn speech into timed subtitles, fix the wording and timing while you watch, translate them, and download SRT or VTT.
        </p>
      </header>

      <div className="grid xl:grid-cols-[minmax(0,22rem)_minmax(0,1fr)] gap-8 items-start">
        <div className="flex flex-col gap-5">
          <Card>
            <MediaField
              label="Video or audio"
              kind={['VIDEO', 'AUDIO']}
              value={media}
              onChange={(m) => {
                setMedia(m)
                setTrackId(undefined)
              }}
            />
          </Card>
          {media && (
            <>
              <TrackList media={media} trackId={trackId} onSelect={setTrackId} loading={tracks.isPending} error={tracks.error} tracks={tracks.data ?? []} />
              <TranscribeCard media={media} onDone={(t) => setTrackId(t.id)} />
            </>
          )}
        </div>

        {!media && <EmptyState title="Pick a video or audio file" body="Your subtitles appear here, next to a preview of the video." />}
        {media && trackId && <TrackEditor key={trackId} media={media} trackId={trackId} onCreated={setTrackId} onDeleted={() => setTrackId(undefined)} />}
        {media && !trackId && !tracks.isPending && (
          <EmptyState title="No subtitles for this file yet" body="Make them automatically from the speech, or start an empty track and type them yourself." />
        )}
      </div>
    </>
  )
}

function TrackList({
  media,
  tracks,
  trackId,
  onSelect,
  loading,
  error,
}: {
  media: MediaAsset
  tracks: { id: string; label: string; language: string; source: keyof typeof SOURCE_LABEL; cueCount: number; updatedAt: string }[]
  trackId?: string
  onSelect: (id: string) => void
  loading: boolean
  error: unknown
}) {
  const create = useCreateTrack()
  const [language, setLanguage] = useState('ta')
  return (
    <Card label="Subtitle tracks">
      <h2 className="font-semibold">Subtitle tracks</h2>
      {loading && <Spinner label="Loading" />}
      {!!error && <ErrorNotice error={error} />}
      {tracks.length > 0 && (
        <ul className="flex flex-col gap-1" role="listbox" aria-label="Subtitle tracks">
          {tracks.map((t) => (
            <li key={t.id}>
              <button
                role="option"
                aria-selected={t.id === trackId}
                onClick={() => onSelect(t.id)}
                className={cn(
                  'w-full text-left rounded-lg px-3 py-2 border',
                  t.id === trackId ? 'border-marigold-500 bg-marigold-500/10' : 'border-transparent hover:bg-surface-2',
                )}
              >
                <span className="flex items-center gap-2">
                  <span className="font-medium truncate">{t.label}</span>
                  <StatusPill tone="neutral">{SOURCE_LABEL[t.source]}</StatusPill>
                </span>
                <span className="block text-xs text-faint">
                  {t.cueCount} subtitles · edited {formatRelative(t.updatedAt)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex gap-2 items-end">
        <Field label="Empty track">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value)}>
              {SUBTITLE_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Button
          icon={<Plus className="size-4" />}
          loading={create.isPending}
          onClick={() =>
            create.mutate(
              { mediaId: media.id, language, cues: [{ start: 0, end: 2, text: '' }] },
              { onSuccess: (t) => onSelect(t.id), onError: (e) => toast.error(e.message) },
            )
          }
        >
          Start
        </Button>
      </div>
    </Card>
  )
}

function TranscribeCard({ media, onDone }: { media: MediaAsset; onDone: (t: SubtitleTrack) => void }) {
  const accounts = useUsableAccounts('TRANSCRIPTION')
  const transcribe = useTranscribe()
  const [accountId, setAccountId] = useState('')
  const [language, setLanguage] = useState('ta')
  const [vertical, setVertical] = useState(!!media.height && !!media.width && media.height > media.width)
  useDefaultAccount(accounts.usable, accountId, setAccountId)

  if (accounts.isPending) return <Spinner label="Loading accounts" />
  if (accounts.usable.length === 0) {
    return <ConnectAccount title="Automatic subtitles need an account" body="Add an ElevenLabs or OpenAI API key to turn speech into subtitles." />
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    transcribe.mutate(
      { mediaId: media.id, accountId, language: language || undefined, maxLineChars: vertical ? 28 : 42 },
      {
        onSuccess: (t) => {
          toast.success(`${t.cues.length} subtitles made`)
          onDone(t)
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit}>
      <Card label="Automatic subtitles">
        <h2 className="font-semibold">Make subtitles from speech</h2>
        <Field label="Account">{(id) => <AccountSelect id={id} accounts={accounts.usable} value={accountId} onChange={setAccountId} />}</Field>
        <Field label="Spoken language">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value)}>
              <option value="">Detect automatically</option>
              {SUBTITLE_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={vertical} onChange={(e) => setVertical(e.target.checked)} className="accent-marigold-500" />
          Short lines for Shorts and Reels
        </label>
        <Button type="submit" variant="primary" loading={transcribe.isPending} icon={<Wand2 className="size-4" />}>
          Make subtitles
        </Button>
        {transcribe.isPending && (
          <p className="text-sm text-muted" role="status">
            Listening to the audio. Long videos take a minute or two.
          </p>
        )}
      </Card>
    </form>
  )
}

function TrackEditor({
  media,
  trackId,
  onCreated,
  onDeleted,
}: {
  media: MediaAsset
  trackId: string
  onCreated: (id: string) => void
  onDeleted: () => void
}) {
  const track = useTrack(trackId)
  const save = useSaveTrack()
  const del = useDeleteTrack()
  const [cues, setCues] = useState<Cue[]>([])
  const [label, setLabel] = useState('')
  const [dirty, setDirty] = useState(false)
  const [time, setTime] = useState(0)
  const [translating, setTranslating] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const player = useRef<HTMLVideoElement & HTMLAudioElement>(null)
  const rows = useRef<(HTMLLIElement | null)[]>([])

  useEffect(() => {
    if (track.data && !dirty) {
      setCues(track.data.cues)
      setLabel(track.data.label)
    }
  }, [track.data, dirty])

  // Warn before leaving with unsaved edits.
  useEffect(() => {
    if (!dirty) return
    const h = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  const vttUrl = useMemo(() => URL.createObjectURL(new Blob([toVtt(cues)], { type: 'text/vtt' })), [cues])
  useEffect(() => () => URL.revokeObjectURL(vttUrl), [vttUrl])
  const active = activeCueIndex(cues, time)
  const problems = cueProblems(cues)

  useEffect(() => {
    if (active >= 0) rows.current[active]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [active])

  if (track.isPending) return <Spinner label="Loading subtitles" />
  if (track.error) return <ErrorNotice error={track.error} onRetry={() => track.refetch()} />
  const t = track.data

  function change(next: Cue[]) {
    setCues(next)
    setDirty(true)
  }

  function update(i: number, patch: Partial<Cue>) {
    change(cues.map((c, j) => (j === i ? { ...c, ...patch } : c)))
  }

  function seek(seconds: number) {
    if (player.current) {
      player.current.currentTime = seconds
      setTime(seconds)
    }
  }

  function saveNow() {
    save.mutate(
      { id: trackId, label: label.trim() || t.label, cues },
      {
        onSuccess: () => {
          setDirty(false)
          toast.success('Subtitles saved')
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  return (
    <div className="flex flex-col gap-4 min-w-0">
      <div className="rounded-2xl border border-line bg-[#0d1020] overflow-hidden xl:sticky xl:top-4 z-10">
        {media.kind === 'VIDEO' ? (
          <video
            ref={player}
            src={media.url}
            poster={media.posterUrl}
            controls
            onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
            className="w-full max-h-[46vh] bg-black"
          >
            <track key={vttUrl} kind="subtitles" src={vttUrl} srcLang={t.language} label={label} default />
          </video>
        ) : (
          <div className="p-4 flex flex-col gap-3">
            <audio ref={player} src={media.url} controls onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)} className="w-full" />
            <p className="text-center text-white text-lg min-h-14 whitespace-pre-line" aria-live="polite">
              {active >= 0 ? cues[active].text : ''}
            </p>
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <Field label="Track name">{(id) => <Input id={id} value={label} maxLength={120} onChange={(e) => { setLabel(e.target.value); setDirty(true) }} />}</Field>
        <Button variant="primary" icon={<Save className="size-4" />} onClick={saveNow} loading={save.isPending} disabled={!dirty}>
          {dirty ? 'Save changes' : 'Saved'}
        </Button>
        <Button icon={<Languages className="size-4" />} onClick={() => setTranslating(true)} disabled={dirty || cues.length === 0} title={dirty ? 'Save first' : undefined}>
          Translate
        </Button>
        <a href={trackExportUrl(trackId, 'srt')} className={cn('inline-flex h-10 items-center gap-2 px-4 rounded-lg border border-line bg-surface hover:bg-surface-2', dirty && 'pointer-events-none opacity-55')} aria-disabled={dirty}>
          <Download className="size-4" aria-hidden /> SRT
        </a>
        <a href={trackExportUrl(trackId, 'vtt')} className={cn('inline-flex h-10 items-center gap-2 px-4 rounded-lg border border-line bg-surface hover:bg-surface-2', dirty && 'pointer-events-none opacity-55')} aria-disabled={dirty}>
          <Download className="size-4" aria-hidden /> VTT
        </a>
        <Button variant="ghost" aria-label="Delete this subtitle track" icon={<Trash2 className="size-4" />} onClick={() => setConfirmDelete(true)} />
      </div>

      <div className="flex flex-wrap items-center gap-2 text-sm text-muted">
        <span>Move all subtitles:</span>
        {[-0.5, -0.1, 0.1, 0.5].map((d) => (
          <Button key={d} size="sm" variant="ghost" onClick={() => change(shiftCues(cues, d))}>
            {d > 0 ? '+' : '−'}
            {Math.abs(d)} s
          </Button>
        ))}
        <span className="ml-auto tabular">{cues.length} subtitles</span>
      </div>

      <ol className="flex flex-col gap-2" aria-label="Subtitles">
        {cues.map((c, i) => (
          <li
            key={i}
            ref={(el) => {
              rows.current[i] = el
            }}
            className={cn(
              'rounded-xl border p-3 grid sm:grid-cols-[9.5rem_minmax(0,1fr)_auto] gap-3 bg-surface',
              i === active ? 'border-marigold-500 ring-1 ring-marigold-500/40' : 'border-line',
            )}
          >
            <div className="flex sm:flex-col gap-1.5">
              <TimeInput label={`Start of subtitle ${i + 1}`} value={c.start} onChange={(v) => update(i, { start: v })} onNow={() => update(i, { start: Math.round(time * 1000) / 1000 })} />
              <TimeInput label={`End of subtitle ${i + 1}`} value={c.end} onChange={(v) => update(i, { end: v })} onNow={() => update(i, { end: Math.round(time * 1000) / 1000 })} />
            </div>
            <div className="flex flex-col gap-1">
              <Textarea
                aria-label={`Text of subtitle ${i + 1}`}
                rows={2}
                value={c.text}
                onChange={(e) => update(i, { text: e.target.value })}
                onFocus={() => seek(c.start)}
                lang={t.language}
                className="min-h-0"
              />
              {problems.get(i) && <p className="text-xs text-rose-signal">{problems.get(i)}</p>}
            </div>
            <div className="flex sm:flex-col gap-1">
              <Button size="sm" variant="ghost" onClick={() => seek(c.start)}>
                Play
              </Button>
              <Button size="sm" variant="ghost" aria-label={`Add a subtitle after ${i + 1}`} icon={<Plus className="size-4" />} onClick={() => change(insertCueAfter(cues, i))} />
              <Button size="sm" variant="ghost" aria-label={`Delete subtitle ${i + 1}`} icon={<Trash2 className="size-4" />} onClick={() => change(cues.filter((_, j) => j !== i))} />
            </div>
          </li>
        ))}
      </ol>
      {cues.length === 0 && (
        <Button icon={<Plus className="size-4" />} onClick={() => change(insertCueAfter([], -1))} className="self-start">
          Add a subtitle
        </Button>
      )}

      {translating && (
        <TranslateDialog
          track={t}
          onClose={() => setTranslating(false)}
          onDone={(n) => {
            setTranslating(false)
            onCreated(n.id)
          }}
        />
      )}
      <Dialog
        open={confirmDelete}
        title={`Delete “${t.label}”?`}
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <Button onClick={() => setConfirmDelete(false)}>Cancel</Button>
            <Button
              variant="danger"
              loading={del.isPending}
              onClick={() => del.mutate(trackId, { onSuccess: () => { toast.success('Subtitles deleted'); onDeleted() }, onError: (e) => toast.error(e.message) })}
            >
              Delete
            </Button>
          </>
        }
      >
        <p className="text-muted">The video itself is not affected.</p>
      </Dialog>
    </div>
  )
}

function TimeInput({ label, value, onChange, onNow }: { label: string; value: number; onChange: (v: number) => void; onNow: () => void }) {
  const [text, setText] = useState(formatCueTime(value))
  const [bad, setBad] = useState(false)
  useEffect(() => setText(formatCueTime(value)), [value])
  return (
    <div className="flex items-center gap-1">
      <input
        aria-label={label}
        aria-invalid={bad}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => {
          const v = parseCueTime(text)
          if (v === undefined) {
            setBad(true)
            return
          }
          setBad(false)
          onChange(v)
        }}
        className={cn('w-24 h-8 rounded-md border bg-surface px-2 text-sm tabular', bad ? 'border-rose-signal' : 'border-line')}
      />
      <button type="button" onClick={onNow} className="text-xs text-muted hover:text-ink px-1" title="Use the current playback time">
        now
      </button>
    </div>
  )
}

function TranslateDialog({ track, onClose, onDone }: { track: SubtitleTrack; onClose: () => void; onDone: (t: SubtitleTrack) => void }) {
  const accounts = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const models = useTextModels(accountId || undefined)
  const [model, setModel] = useState('')
  const [target, setTarget] = useState(track.language === 'ta' ? 'en' : 'ta')
  const translate = useTranslateTrack()
  useDefaultAccount(accounts.usable, accountId, setAccountId)
  useEffect(() => {
    if (models.data && !models.data.includes(model)) setModel(models.data[0] ?? '')
  }, [models.data, model])

  return (
    <Dialog
      open
      title={`Translate “${track.label}”`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!accountId || !model || target === track.language}
            loading={translate.isPending}
            onClick={() =>
              translate.mutate(
                { id: track.id, accountId, model, targetLanguage: target },
                {
                  onSuccess: (t) => {
                    toast.success(`${languageLabel(target)} subtitles ready`)
                    onDone(t)
                  },
                  onError: (e) => toast.error(e.message),
                },
              )
            }
          >
            Translate
          </Button>
        </>
      }
    >
      {accounts.usable.length === 0 ? (
        <p className="text-muted">Add an OpenAI, Anthropic or Google Gemini account to translate subtitles.</p>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-muted">A new track is made. Timing is copied exactly; only the words change.</p>
          <Field label="Translate into">
            {(id) => (
              <Select id={id} value={target} onChange={(e) => setTarget(e.target.value)}>
                {SUBTITLE_LANGUAGES.filter((l) => l.code !== track.language).map((l) => (
                  <option key={l.code} value={l.code}>
                    {l.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Account">{(id) => <AccountSelect id={id} accounts={accounts.usable} value={accountId} onChange={setAccountId} />}</Field>
          <Field label="Model">
            {(id) =>
              models.isPending ? (
                <Spinner label="Loading models" />
              ) : (
                <Select id={id} value={model} onChange={(e) => setModel(e.target.value)}>
                  {(models.data ?? []).map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </Select>
              )
            }
          </Field>
          {translate.isPending && <p className="text-sm text-muted" role="status">Translating in batches…</p>}
        </div>
      )}
    </Dialog>
  )
}
