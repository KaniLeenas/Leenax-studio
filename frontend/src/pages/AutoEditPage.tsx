import { ArrowDown, ArrowUp, CheckCircle2, Clapperboard, Library, Loader2, RotateCcw, Trash2, Wand2, X, XCircle } from 'lucide-react'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { MediaPicker } from '../components/MediaPicker'
import { MediaThumb } from '../components/media'
import { AccountSelect, Card, MediaField, Slider, TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, EmptyState, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useModels } from '../lib/aiQueries'
import { useAutoEditAction, useAutoEditJobs, useStartAutoEdit } from '../lib/autoEditQueries'
import { cn } from '../lib/cn'
import { formatDuration, formatRelative } from '../lib/format'
import { useProjects } from '../lib/queries'
import { useVoiceProfiles } from '../lib/studioQueries'
import type { AutoEditJob, AutoEditMode, AutoEditRequest, Language, MediaAsset, MediaKind, VideoFormat } from '../lib/types'
import { toast } from '../stores/ui'

const MODES: { id: AutoEditMode; title: string; body: string }[] = [
  { id: 'SCRIPT_TO_VIDEO', title: 'Script to video', body: 'Your script (or a topic) becomes a narrated video with matching B-roll, captions and music.' },
  { id: 'TALKING_HEAD', title: 'Clean up a talking video', body: 'Cuts long pauses and filler words, adds jump-cut zooms and captions.' },
  { id: 'HIGHLIGHTS', title: 'Shorts from a long video', body: 'Finds the best moments and makes vertical Shorts and Reels with captions.' },
]

const MODE_LABEL: Record<AutoEditMode, string> = {
  SCRIPT_TO_VIDEO: 'Script to video',
  TALKING_HEAD: 'Talking video clean-up',
  HIGHLIGHTS: 'Shorts from a long video',
}

const FORMATS: { id: VideoFormat; label: string }[] = [
  { id: 'LANDSCAPE', label: 'Landscape 16:9' },
  { id: 'PORTRAIT', label: 'Vertical 9:16' },
  { id: 'SQUARE', label: 'Square 1:1' },
]

export function AutoEditPage() {
  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">AI auto editor</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Give it footage and a script, a talking video, or a long recording. You get an editable first cut in the video editor: change anything, then export.
        </p>
      </header>
      <div className="grid xl:grid-cols-[minmax(0,1fr)_minmax(0,24rem)] gap-8 items-start">
        <NewEdit />
        <JobsPanel />
      </div>
    </>
  )
}

// ---- the form ------------------------------------------------------------------------------

function NewEdit() {
  const [mode, setMode] = useState<AutoEditMode>('SCRIPT_TO_VIDEO')
  const [name, setName] = useState('')
  const [format, setFormat] = useState<VideoFormat>('LANDSCAPE')
  const [language, setLanguage] = useState<Language>('TA')
  const [projectId, setProjectId] = useState('')
  const [footage, setFootage] = useState<MediaAsset[]>([])
  // script
  const [hasScript, setHasScript] = useState(true)
  const [script, setScript] = useState('')
  const [topic, setTopic] = useState('')
  const [targetSeconds, setTargetSeconds] = useState(60)
  const [style, setStyle] = useState('')
  const [voiceProfileId, setVoiceProfileId] = useState('')
  const [describe, setDescribe] = useState(true)
  const [imageAccountId, setImageAccountId] = useState('')
  const [imageModel, setImageModel] = useState('')
  // accounts
  const [textAccountId, setTextAccountId] = useState('')
  const [textModel, setTextModel] = useState('')
  const [sttAccountId, setSttAccountId] = useState('')
  // talking head
  const [removeFillers, setRemoveFillers] = useState(true)
  const [maxPause, setMaxPause] = useState(0.45)
  const [punchIn, setPunchIn] = useState(true)
  // highlights
  const [count, setCount] = useState(3)
  const [minSeconds, setMinSeconds] = useState(20)
  const [maxSeconds, setMaxSeconds] = useState(60)
  // all
  const [captions, setCaptions] = useState(true)
  const [music, setMusic] = useState<MediaAsset>()
  const [musicVolume, setMusicVolume] = useState(0.15)

  const start = useStartAutoEdit()
  const projects = useProjects({ page: 0 })
  const profiles = useVoiceProfiles()
  const textAccounts = useUsableAccounts('TEXT')
  const sttAccounts = useUsableAccounts('TRANSCRIPTION')
  const imageAccounts = useUsableAccounts('IMAGE')
  useDefaultAccount(textAccounts.usable, textAccountId, setTextAccountId)
  useDefaultAccount(sttAccounts.usable, sttAccountId, setSttAccountId)

  useEffect(() => {
    if (mode === 'HIGHLIGHTS') setFormat('PORTRAIT')
    setFootage((f) => (mode === 'HIGHLIGHTS' ? f.filter((m) => m.kind === 'VIDEO').slice(0, 1) : mode === 'TALKING_HEAD' ? f.filter((m) => m.kind === 'VIDEO') : f))
  }, [mode])

  const needsText = mode === 'HIGHLIGHTS' || (mode === 'SCRIPT_TO_VIDEO' && (!hasScript || describe))
  const missing: string[] = []
  if (!name.trim()) missing.push('a name')
  if (mode === 'SCRIPT_TO_VIDEO' && hasScript && !script.trim()) missing.push('your script')
  if (mode === 'SCRIPT_TO_VIDEO' && !hasScript && !topic.trim()) missing.push('a topic')
  if (mode !== 'SCRIPT_TO_VIDEO' && footage.length === 0) missing.push(mode === 'HIGHLIGHTS' ? 'a video' : 'your videos')
  if (mode !== 'SCRIPT_TO_VIDEO' && !sttAccountId) missing.push('a speech-to-text account')
  if (needsText && (!textAccountId || !textModel)) missing.push('an AI text model')
  if (mode === 'HIGHLIGHTS' && minSeconds >= maxSeconds) missing.push('a shortest length below the longest')

  function submit(e: FormEvent) {
    e.preventDefault()
    if (missing.length) return
    const req: AutoEditRequest = {
      mode,
      name: name.trim(),
      format,
      language,
      projectId: projectId || undefined,
      footageIds: footage.map((f) => f.id),
      captions,
      musicMediaId: music?.id,
      musicVolume: music ? musicVolume : undefined,
      describeFootage: mode === 'SCRIPT_TO_VIDEO' && describe && footage.length > 0,
      removeFillers,
      punchIn,
      textAccountId: textAccountId && textModel ? textAccountId : undefined,
      textModel: textAccountId && textModel ? textModel : undefined,
    }
    if (mode === 'SCRIPT_TO_VIDEO') {
      Object.assign(req, {
        script: hasScript ? script : undefined,
        topic: hasScript ? undefined : topic,
        targetSeconds: hasScript ? undefined : targetSeconds,
        style: style.trim() || undefined,
        voice: voiceProfileId ? { profileId: voiceProfileId } : undefined,
        imageAccountId: footage.length === 0 && imageAccountId && imageModel ? imageAccountId : undefined,
        imageModel: footage.length === 0 && imageAccountId && imageModel ? imageModel : undefined,
      })
    } else {
      Object.assign(req, { transcriptionAccountId: sttAccountId })
      if (mode === 'TALKING_HEAD') Object.assign(req, { maxPause })
      if (mode === 'HIGHLIGHTS') Object.assign(req, { highlightCount: count, minSeconds, maxSeconds })
    }
    start.mutate(req, {
      onSuccess: () => toast.success('Automatic edit started. It appears on the right.'),
      onError: (err) => toast.error(err.message),
    })
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <div role="radiogroup" aria-label="What to make" className="grid sm:grid-cols-3 gap-2">
        {MODES.map((m) => (
          <button
            key={m.id}
            type="button"
            role="radio"
            aria-checked={mode === m.id}
            onClick={() => setMode(m.id)}
            className={cn('rounded-xl border p-3.5 text-left', mode === m.id ? 'border-marigold-500 bg-marigold-500/10' : 'border-line bg-surface hover:bg-surface-2')}
          >
            <span className="block font-semibold">{m.title}</span>
            <span className="block text-sm text-muted mt-1">{m.body}</span>
          </button>
        ))}
      </div>

      <Card>
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="Name">{(id) => <Input id={id} value={name} maxLength={160} onChange={(e) => setName(e.target.value)} placeholder="Madurai food tour" />}</Field>
          <Field label="Project">
            {(id) => (
              <Select id={id} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
                <option value="">No project</option>
                {(projects.data?.items ?? []).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Format">
            {(id) => (
              <Select id={id} value={format} onChange={(e) => setFormat(e.target.value as VideoFormat)}>
                {FORMATS.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Language">
            {(id) => (
              <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
                <option value="TA">Tamil</option>
                <option value="EN">English</option>
              </Select>
            )}
          </Field>
        </div>
      </Card>

      {mode === 'SCRIPT_TO_VIDEO' && (
        <Card label="Script">
          <div role="radiogroup" aria-label="Script source" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1">
            {[true, false].map((v) => (
              <button
                key={String(v)}
                type="button"
                role="radio"
                aria-checked={hasScript === v}
                onClick={() => setHasScript(v)}
                className={cn('rounded-lg py-2 text-sm font-medium', hasScript === v ? 'bg-surface shadow-sm text-ink' : 'text-muted hover:text-ink')}
              >
                {v ? 'I have a script' : 'Write it for me'}
              </button>
            ))}
          </div>
          {hasScript ? (
            <Field label="Script" hint="Paragraphs become scenes. [pause 1s] adds a pause in the voice-over.">
              {(id, d) => <Textarea id={id} aria-describedby={d} rows={10} maxLength={20_000} value={script} onChange={(e) => setScript(e.target.value)} />}
            </Field>
          ) : (
            <>
              <Field label="Topic" hint="What the video is about, and anything it must mention.">
                {(id, d) => <Textarea id={id} aria-describedby={d} rows={3} maxLength={1000} value={topic} onChange={(e) => setTopic(e.target.value)} />}
              </Field>
              <div className="grid sm:grid-cols-2 gap-4">
                <Slider label="Length" min={15} max={600} step={15} value={targetSeconds} onChange={setTargetSeconds} format={(v) => formatDuration(v * 1000)} />
                <Field label="Style">{(id) => <Input id={id} maxLength={200} value={style} onChange={(e) => setStyle(e.target.value)} placeholder="Energetic food vlog" />}</Field>
              </div>
            </>
          )}
          <Field label="Voice-over" hint="From My voices in Voice & audio. Without a voice, the script is shown as captions.">
            {(id, d) =>
              profiles.isPending ? (
                <Spinner label="Loading voices" />
              ) : (
                <Select id={id} aria-describedby={d} value={voiceProfileId} onChange={(e) => setVoiceProfileId(e.target.value)}>
                  <option value="">No voice-over</option>
                  {(profiles.data ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                      {p.kind === 'CLONED' ? ' (your voice)' : ''}
                    </option>
                  ))}
                </Select>
              )
            }
          </Field>
        </Card>
      )}

      <Card label="Footage">
        <FootageList
          mode={mode}
          items={footage}
          onChange={setFootage}
        />
        {mode === 'SCRIPT_TO_VIDEO' && footage.length > 0 && (
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" checked={describe} onChange={(e) => setDescribe(e.target.checked)} className="mt-1 accent-marigold-500" />
            <span>
              Let AI look at the footage to match shots to what is said
              <span className="block text-xs text-faint">Sends 3 small frames per clip to your AI text account (once per file; the result is remembered).</span>
            </span>
          </label>
        )}
        {mode === 'SCRIPT_TO_VIDEO' && footage.length === 0 && (
          <AiImagePicker accounts={imageAccounts.usable} accountId={imageAccountId} setAccountId={setImageAccountId} model={imageModel} setModel={setImageModel} />
        )}
      </Card>

      <Card label="AI">
        {mode !== 'SCRIPT_TO_VIDEO' && (
          <Field label="Speech-to-text account" hint="ElevenLabs or OpenAI. Each file is transcribed once and remembered.">
            {(id, d) =>
              sttAccounts.usable.length === 0 ? (
                <p className="text-sm text-muted" id={d}>Add an ElevenLabs or OpenAI account under AI accounts & keys.</p>
              ) : (
                <AccountSelect id={id} accounts={sttAccounts.usable} value={sttAccountId} onChange={setSttAccountId} />
              )
            }
          </Field>
        )}
        {(mode !== 'TALKING_HEAD') && (
          <TextModelPicker
            accounts={textAccounts.usable}
            accountId={textAccountId}
            setAccountId={setTextAccountId}
            model={textModel}
            setModel={setTextModel}
            required={needsText}
            hint={
              mode === 'HIGHLIGHTS'
                ? 'Reads the transcript and picks the best moments.'
                : 'Splits or writes the script and chooses shots. Without it, footage is placed in order.'
            }
          />
        )}
        {mode === 'TALKING_HEAD' && (
          <>
            <Slider
              label="Cut pauses longer than"
              min={0.2}
              max={2}
              step={0.05}
              value={maxPause}
              onChange={setMaxPause}
              format={(v) => `${v.toFixed(2)} s`}
              hint="Lower = tighter, faster pace."
            />
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={removeFillers} onChange={(e) => setRemoveFillers(e.target.checked)} className="accent-marigold-500" />
              Remove filler sounds (um, uh, ம்ம்…)
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={punchIn} onChange={(e) => setPunchIn(e.target.checked)} className="accent-marigold-500" />
              Zoom in slightly on every other cut
            </label>
          </>
        )}
        {mode === 'HIGHLIGHTS' && (
          <div className="grid sm:grid-cols-3 gap-4">
            <Slider label="How many" min={1} max={5} step={1} value={count} onChange={setCount} />
            <Slider label="Shortest" min={10} max={60} step={5} value={minSeconds} onChange={setMinSeconds} format={(v) => `${v} s`} />
            <Slider label="Longest" min={15} max={90} step={5} value={maxSeconds} onChange={setMaxSeconds} format={(v) => `${v} s`} />
          </div>
        )}
      </Card>

      <Card label="Finishing">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={captions} onChange={(e) => setCaptions(e.target.checked)} className="accent-marigold-500" />
          Add captions
        </label>
        <MediaField label="Background music (optional)" kind="AUDIO" value={music} onChange={setMusic} />
        {music && (
          <div className="flex items-end gap-3">
            <div className="flex-1">
              <Slider label="Music level" min={0.05} max={1} step={0.05} value={musicVolume} onChange={setMusicVolume} format={(v) => `${Math.round(v * 100)}%`} />
            </div>
            <Button type="button" size="sm" variant="ghost" onClick={() => setMusic(undefined)}>
              Remove
            </Button>
          </div>
        )}
      </Card>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" variant="primary" icon={<Wand2 className="size-4" />} loading={start.isPending} disabled={missing.length > 0}>
          Start automatic edit
        </Button>
        {missing.length > 0 && <p className="text-sm text-muted">Still needed: {missing.join(', ')}.</p>}
      </div>
      {start.error && <ErrorNotice error={start.error} />}
    </form>
  )
}

function FootageList({ mode, items, onChange }: { mode: AutoEditMode; items: MediaAsset[]; onChange: (m: MediaAsset[]) => void }) {
  const [open, setOpen] = useState(false)
  const kinds: MediaKind[] = mode === 'SCRIPT_TO_VIDEO' ? ['VIDEO', 'IMAGE'] : ['VIDEO']
  const single = mode === 'HIGHLIGHTS'
  const label = mode === 'SCRIPT_TO_VIDEO' ? 'B-roll (videos and images)' : mode === 'TALKING_HEAD' ? 'Your videos, in order' : 'Long video'
  const move = (i: number, d: number) => {
    const next = [...items]
    const [x] = next.splice(i, 1)
    next.splice(i + d, 0, x)
    onChange(next)
  }
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium">{label}</span>
      {items.length === 0 && (
        <p className="text-sm text-muted">
          {mode === 'SCRIPT_TO_VIDEO' ? 'Optional. Without footage, scenes use AI images (if chosen below) or title cards.' : 'Nothing chosen yet.'}
        </p>
      )}
      {items.length > 0 && (
        <ul className="flex flex-col gap-1">
          {items.map((m, i) => (
            <li key={m.id} className="flex items-center gap-2 rounded-lg border border-line p-1.5">
              <MediaThumb asset={m} className="w-16 aspect-video rounded" />
              <span className="flex-1 min-w-0 truncate text-sm">{m.filename}</span>
              {m.durationMs != null && <span className="text-xs text-faint tabular">{formatDuration(m.durationMs)}</span>}
              {mode === 'TALKING_HEAD' && items.length > 1 && (
                <>
                  <button type="button" aria-label={`Move ${m.filename} up`} disabled={i === 0} onClick={() => move(i, -1)} className="p-1 text-faint hover:text-ink disabled:opacity-30">
                    <ArrowUp className="size-4" />
                  </button>
                  <button type="button" aria-label={`Move ${m.filename} down`} disabled={i === items.length - 1} onClick={() => move(i, 1)} className="p-1 text-faint hover:text-ink disabled:opacity-30">
                    <ArrowDown className="size-4" />
                  </button>
                </>
              )}
              <button type="button" aria-label={`Remove ${m.filename}`} onClick={() => onChange(items.filter((x) => x.id !== m.id))} className="p-1 text-faint hover:text-ink">
                <X className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
      {(!single || items.length === 0) && items.length < 50 && (
        <Button type="button" onClick={() => setOpen(true)} icon={<Library className="size-4" />} className="self-start">
          {items.length === 0 ? 'Choose from library' : 'Add more'}
        </Button>
      )}
      <MediaPicker
        open={open}
        kind={kinds}
        title={label}
        onClose={() => setOpen(false)}
        onPick={(m) => {
          if (!items.some((x) => x.id === m.id)) onChange(single ? [m] : [...items, m])
          setOpen(false)
        }}
      />
    </div>
  )
}

function AiImagePicker({
  accounts,
  accountId,
  setAccountId,
  model,
  setModel,
}: {
  accounts: ReturnType<typeof useUsableAccounts>['usable']
  accountId: string
  setAccountId: (v: string) => void
  model: string
  setModel: (v: string) => void
}) {
  const models = useModels()
  const provider = accounts.find((a) => a.id === accountId)?.provider
  const imageModels = (models.data ?? []).filter((m) => m.capability === 'IMAGE' && m.enabled && m.provider === provider)
  useEffect(() => {
    if (imageModels.length && !imageModels.some((m) => m.modelCode === model)) setModel(imageModels[0].modelCode)
  }, [imageModels, model, setModel])
  if (accounts.length === 0) return null
  return (
    <div className="grid sm:grid-cols-2 gap-4">
      <Field label="AI images for scenes (optional)" hint="One image per scene (up to 12), with slow movement. Billed by the provider.">
        {(id, d) => (
          <Select id={id} aria-describedby={d} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            <option value="">No AI images (title cards)</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} · {a.providerName}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {accountId && (
        <Field label="Image model">
          {(id) => (
            <Select id={id} value={model} onChange={(e) => setModel(e.target.value)}>
              {imageModels.map((m) => (
                <option key={m.id} value={m.modelCode}>
                  {m.displayName}
                </option>
              ))}
            </Select>
          )}
        </Field>
      )}
    </div>
  )
}

// ---- jobs ------------------------------------------------------------------------------------

function JobsPanel() {
  const jobs = useAutoEditJobs()
  return (
    <section aria-labelledby="jobs-heading" className="xl:sticky xl:top-6">
      <h2 id="jobs-heading" className="text-lg font-semibold mb-3">
        Your automatic edits
      </h2>
      {jobs.isPending && <Spinner label="Loading" />}
      {jobs.error && <ErrorNotice error={jobs.error} onRetry={() => jobs.refetch()} />}
      {jobs.data?.length === 0 && <EmptyState title="None yet" body="Start one on the left. It runs in the background; you can leave this page." />}
      <ul className="flex flex-col gap-3">
        {jobs.data?.map((j) => (
          <JobCard key={j.id} job={j} />
        ))}
      </ul>
    </section>
  )
}

function JobCard({ job }: { job: AutoEditJob }) {
  const action = useAutoEditAction()
  const active = job.status === 'QUEUED' || job.status === 'RUNNING'
  const run = (a: 'cancel' | 'rerun' | 'delete') => action.mutate({ id: job.id, action: a }, { onError: (e) => toast.error(e.message) })
  const timelines = job.result?.timelineIds ?? []
  let icon: ReactNode = <Loader2 className="size-4 animate-spin text-marigold-500" aria-hidden />
  if (job.status === 'COMPLETED') icon = <CheckCircle2 className="size-4 text-peacock-500" aria-hidden />
  if (job.status === 'FAILED' || job.status === 'CANCELLED') icon = <XCircle className="size-4 text-rose-signal" aria-hidden />
  return (
    <li className="rounded-xl border border-line bg-surface p-4 flex flex-col gap-2">
      <div className="flex items-start gap-2">
        <span className="mt-1">{icon}</span>
        <div className="min-w-0 flex-1">
          <p className="font-medium truncate">{job.name}</p>
          <p className="text-xs text-faint">
            {MODE_LABEL[job.mode]} · {formatRelative(job.createdAt)}
          </p>
        </div>
        {active ? (
          <Button size="sm" variant="ghost" aria-label="Cancel" onClick={() => run('cancel')} icon={<X className="size-4" />} />
        ) : (
          <>
            <Button size="sm" variant="ghost" aria-label="Run again" title="Run again" onClick={() => run('rerun')} icon={<RotateCcw className="size-4" />} />
            <Button size="sm" variant="ghost" aria-label="Remove from list" title="Remove from list" onClick={() => run('delete')} icon={<Trash2 className="size-4" />} />
          </>
        )}
      </div>
      {active && (
        <>
          <div className="h-1.5 rounded-full bg-surface-2 overflow-hidden" role="progressbar" aria-valuenow={Math.round(job.progress * 100)} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full bg-marigold-500 transition-[width]" style={{ width: `${Math.max(3, job.progress * 100)}%` }} />
          </div>
          <p className="text-sm text-muted" aria-live="polite">
            {job.status === 'QUEUED' ? 'Waiting to start…' : job.step}
          </p>
        </>
      )}
      {job.status === 'FAILED' && <p className="text-sm text-rose-signal">{job.errorMessage}</p>}
      {job.result?.notes && job.result.notes.length > 0 && (
        <ul className="text-xs text-muted list-disc pl-4">
          {job.result.notes.map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ul>
      )}
      {timelines.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {timelines.map((id, i) => (
            <Link
              key={id}
              to={`/edit/timeline/${id}`}
              className="inline-flex items-center gap-1.5 h-8 px-3 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] text-sm font-semibold"
            >
              <Clapperboard className="size-4" aria-hidden />
              {timelines.length > 1 ? `Open short ${i + 1}` : 'Open in editor'}
            </Link>
          ))}
        </div>
      )}
    </li>
  )
}
