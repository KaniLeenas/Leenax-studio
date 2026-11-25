import { Clapperboard, Copy, FileText, Lightbulb, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Slider, TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import { useProjects } from '../lib/queries'
import {
  FORMATS,
  formatMinutes,
  postText,
  spokenMinutes,
  TEXT_PLATFORMS,
  VIDEO_TYPES,
  videoTypeLabel,
  type Idea,
  type Script,
  type VideoType,
  type Writer,
} from '../lib/story'
import {
  useCreateScript,
  useDeleteScript,
  useIdeas,
  useRewriteScript,
  useScript,
  useScriptMetadata,
  useScripts,
  useStoryboardFromScript,
  useUpdateScript,
  useWriteScript,
} from '../lib/storyQueries'
import type { Language, VideoFormat } from '../lib/types'
import { toast } from '../stores/ui'

/** The AI text account and model, shared by every writing action on the page. */
function useWriter() {
  const accounts = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  useDefaultAccount(accounts.usable, accountId, setAccountId)
  const writer: Writer | undefined = accountId && model ? { accountId, model } : undefined
  return { accounts: accounts.usable, loading: accounts.isPending, accountId, setAccountId, model, setModel, writer }
}

type WriterState = ReturnType<typeof useWriter>

export function ScriptsPage() {
  const [params, setParams] = useSearchParams()
  const selected = params.get('id') ?? undefined
  const projectFilter = params.get('project') ?? ''
  const writer = useWriter()

  function select(id?: string) {
    const next = new URLSearchParams(params)
    if (id) next.set('id', id)
    else next.delete('id')
    setParams(next)
  }

  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Scripts & ideas</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Find an idea, have it written in Tamil or English, then polish it, write the post text for each platform, and turn it into a storyboard.
        </p>
      </header>
      <div className="grid lg:grid-cols-[18rem_minmax(0,1fr)] gap-8 items-start">
        <ScriptList selected={selected} projectId={projectFilter} onSelect={select} />
        <div className="min-w-0 flex flex-col gap-5">
          <Card label="AI writer">
            <TextModelPicker
              accounts={writer.accounts}
              accountId={writer.accountId}
              setAccountId={writer.setAccountId}
              model={writer.model}
              setModel={writer.setModel}
              required
              hint="Writes ideas, scripts, rewrites and post text. Billed by the provider."
            />
          </Card>
          {selected ? (
            <ScriptEditor key={selected} id={selected} writer={writer} onSelect={select} />
          ) : (
            <NewScript writer={writer} projectId={projectFilter} onCreated={(s) => select(s.id)} />
          )}
        </div>
      </div>
    </>
  )
}

// ---- list ---------------------------------------------------------------------------------------

function ScriptList({ selected, projectId, onSelect }: { selected?: string; projectId: string; onSelect: (id?: string) => void }) {
  const scripts = useScripts(projectId || undefined)
  return (
    <aside aria-labelledby="scripts-heading" className="flex flex-col gap-3 lg:sticky lg:top-6">
      <div className="flex items-center justify-between">
        <h2 id="scripts-heading" className="text-lg font-semibold">
          Your scripts
        </h2>
        <Button size="sm" variant={selected ? 'secondary' : 'ghost'} icon={<Plus className="size-4" />} onClick={() => onSelect(undefined)}>
          New
        </Button>
      </div>
      {scripts.isPending && <Spinner label="Loading scripts" />}
      {scripts.error && <ErrorNotice error={scripts.error} onRetry={() => scripts.refetch()} />}
      {scripts.data && scripts.data.length === 0 && <p className="text-sm text-muted">Nothing written yet. Your scripts and their versions appear here.</p>}
      <ul className="flex flex-col gap-1">
        {(scripts.data ?? []).map((s) => (
          <li key={s.id}>
            <button
              type="button"
              onClick={() => onSelect(s.id)}
              aria-current={selected === s.id ? 'true' : undefined}
              className={cn(
                'w-full text-left rounded-lg px-3 py-2.5 border',
                selected === s.id ? 'border-marigold-500 bg-marigold-500/10' : 'border-transparent hover:bg-surface-2',
              )}
            >
              <span className="block font-medium truncate" lang={s.language === 'TA' ? 'ta' : undefined}>
                {s.title}
              </span>
              <span className="block text-xs text-faint mt-0.5">
                {videoTypeLabel(s.videoType)}, {s.language === 'TA' ? 'Tamil' : 'English'}, {formatMinutes(s.spokenMinutes)}
                {s.versionNo > 1 ? `, version ${s.versionNo}` : ''}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  )
}

// ---- new --------------------------------------------------------------------------------------------

function NewScript({ writer, projectId: initialProject, onCreated }: { writer: WriterState; projectId: string; onCreated: (s: Script) => void }) {
  const [source, setSource] = useState<'ai' | 'own'>('ai')
  const [projectId, setProjectId] = useState(initialProject)
  const [videoType, setVideoType] = useState<VideoType>('YOUTUBE_SHORT')
  const [language, setLanguage] = useState<Language>('TA')
  const [minutes, setMinutes] = useState(1)
  const [topic, setTopic] = useState('')
  const [audience, setAudience] = useState('')
  const [ideas, setIdeas] = useState<Idea[]>([])
  const [title, setTitle] = useState('')
  const [idea, setIdea] = useState('')
  const [tone, setTone] = useState('')
  const [notes, setNotes] = useState('')
  const [body, setBody] = useState('')
  const projects = useProjects({ page: 0 })
  const suggest = useIdeas()
  const write = useWriteScript()
  const create = useCreateScript()

  function chooseType(t: VideoType) {
    setVideoType(t)
    setMinutes(VIDEO_TYPES.find((v) => v.id === t)?.minutes ?? 3)
  }

  function findIdeas(e: FormEvent) {
    e.preventDefault()
    if (!writer.writer || !topic.trim()) return
    suggest.mutate(
      { writer: writer.writer, topic: topic.trim(), videoType, language, count: 6, audience: audience.trim() || undefined },
      { onSuccess: setIdeas, onError: (err) => toast.error(err.message) },
    )
  }

  function pick(i: Idea) {
    setTitle(i.title)
    setIdea([i.hook, i.angle].filter(Boolean).join('\n'))
    document.getElementById('write-heading')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (source === 'ai') {
      if (!writer.writer || !idea.trim()) return
      write.mutate(
        {
          writer: writer.writer,
          projectId: projectId || undefined,
          title: title.trim() || undefined,
          idea: idea.trim(),
          videoType,
          language,
          minutes,
          tone: tone.trim() || undefined,
          notes: notes.trim() || undefined,
        },
        { onSuccess: (s) => { toast.success('Script written'); onCreated(s) }, onError: (err) => toast.error(err.message) },
      )
    } else {
      if (!title.trim() || !body.trim()) return
      create.mutate(
        { title: title.trim(), body, idea: idea.trim() || undefined, language, videoType, projectId: projectId || undefined },
        { onSuccess: (s) => { toast.success('Script saved'); onCreated(s) }, onError: (err) => toast.error(err.message) },
      )
    }
  }

  const shared = (
    <div className="grid sm:grid-cols-3 gap-4">
      <Field label="Video type">
        {(id) => (
          <Select id={id} value={videoType} onChange={(e) => chooseType(e.target.value as VideoType)}>
            {VIDEO_TYPES.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label}
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
    </div>
  )

  const ownWords = spokenMinutes(body, language)
  const busy = write.isPending || create.isPending

  return (
    <>
      <Card label="Video">{shared}</Card>

      {source === 'ai' && (
        <form onSubmit={findIdeas}>
          <Card label="Ideas">
            <div className="flex items-center gap-2">
              <Lightbulb className="size-5 text-marigold-500" aria-hidden />
              <h2 className="font-semibold">Find an idea</h2>
              <span className="text-sm text-faint">Optional</span>
            </div>
            <div className="grid sm:grid-cols-[minmax(0,1fr)_14rem] gap-4">
              <Field label="Topic">{(id) => <Input id={id} maxLength={500} value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="Madurai street food at night" />}</Field>
              <Field label="Audience">{(id) => <Input id={id} maxLength={300} value={audience} onChange={(e) => setAudience(e.target.value)} placeholder="Tamil food lovers abroad" />}</Field>
            </div>
            <div>
              <Button type="submit" loading={suggest.isPending} disabled={!writer.writer || !topic.trim()} icon={<Sparkles className="size-4" />}>
                Suggest 6 ideas
              </Button>
            </div>
            {ideas.length > 0 && (
              <ul className="grid sm:grid-cols-2 gap-3" aria-label="Suggested ideas">
                {ideas.map((i, n) => (
                  <li key={n} className="rounded-xl border border-line p-3.5 flex flex-col gap-2">
                    <p className="font-medium" lang={language === 'TA' ? 'ta' : undefined}>{i.title}</p>
                    {i.hook && <p className="text-sm">{i.hook}</p>}
                    {i.angle && <p className="text-sm text-muted">{i.angle}</p>}
                    <div className="mt-auto pt-1">
                      <Button type="button" size="sm" onClick={() => pick(i)}>
                        Use this idea
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </form>
      )}

      <form onSubmit={submit}>
        <Card label="Script">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 id="write-heading" className="font-semibold">
              {source === 'ai' ? 'Write the script' : 'Your own script'}
            </h2>
            <div role="radiogroup" aria-label="Script source" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1 text-sm">
              {(['ai', 'own'] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  role="radio"
                  aria-checked={source === v}
                  onClick={() => setSource(v)}
                  className={cn('rounded-lg px-3 py-1.5 font-medium', source === v ? 'bg-surface shadow-sm text-ink' : 'text-muted hover:text-ink')}
                >
                  {v === 'ai' ? 'Write it for me' : 'I have a script'}
                </button>
              ))}
            </div>
          </div>
          <Field label={source === 'ai' ? 'Working title (optional)' : 'Title'}>
            {(id) => <Input id={id} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />}
          </Field>
          {source === 'ai' ? (
            <>
              <Field label="Idea" hint="What happens, the hook and anything it must say.">
                {(id, d) => <Textarea id={id} aria-describedby={d} rows={4} maxLength={2000} value={idea} onChange={(e) => setIdea(e.target.value)} />}
              </Field>
              <div className="grid sm:grid-cols-2 gap-4">
                <Slider label="Length" min={1} max={60} step={1} value={minutes} onChange={setMinutes} format={(v) => `about ${v} min`} />
                <Field label="Tone">{(id) => <Input id={id} maxLength={200} value={tone} onChange={(e) => setTone(e.target.value)} placeholder="Warm, playful" />}</Field>
              </div>
              <Field label="Notes for the writer (optional)">
                {(id) => <Textarea id={id} rows={2} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Mention the stall owner's name, end with a question" />}
              </Field>
            </>
          ) : (
            <Field label="Script" hint={`[SCENE 1: Heading] lines and (Visual: …) notes are kept for the storyboard but not spoken. About ${formatMinutes(ownWords)} spoken.`}>
              {(id, d) => <Textarea id={id} aria-describedby={d} rows={14} maxLength={100_000} value={body} onChange={(e) => setBody(e.target.value)} lang={language === 'TA' ? 'ta' : undefined} />}
            </Field>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button
              type="submit"
              variant="primary"
              loading={busy}
              disabled={source === 'ai' ? !writer.writer || !idea.trim() : !title.trim() || !body.trim()}
              icon={source === 'ai' ? <Sparkles className="size-4" /> : <FileText className="size-4" />}
            >
              {source === 'ai' ? 'Write script' : 'Save script'}
            </Button>
            {source === 'ai' && !writer.writer && !writer.loading && <span className="text-sm text-muted">Choose an AI writer above first.</span>}
          </div>
        </Card>
      </form>
    </>
  )
}

// ---- editor ---------------------------------------------------------------------------------------

const QUICK_REWRITES = ['Make it shorter', 'Stronger hook in the first 5 seconds', 'More casual and funny', 'Add a call to action at the end']

function ScriptEditor({ id, writer, onSelect }: { id: string; writer: WriterState; onSelect: (id?: string) => void }) {
  const script = useScript(id)
  if (script.isPending) return <Spinner label="Opening script" />
  if (script.error) return <ErrorNotice error={script.error} onRetry={() => script.refetch()} />
  return <ScriptForm script={script.data} writer={writer} onSelect={onSelect} />
}

function ScriptForm({ script, writer, onSelect }: { script: Script; writer: WriterState; onSelect: (id?: string) => void }) {
  const [title, setTitle] = useState(script.title)
  const [body, setBody] = useState(script.body)
  const [idea, setIdea] = useState(script.idea ?? '')
  const [language, setLanguage] = useState<Language>(script.language)
  const [videoType, setVideoType] = useState<VideoType>(script.videoType)
  const [instruction, setInstruction] = useState('')
  const [platforms, setPlatforms] = useState<string[]>(() => Object.keys(script.metadata).length ? Object.keys(script.metadata) : ['YOUTUBE', 'INSTAGRAM', 'TIKTOK'])
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [boardOpen, setBoardOpen] = useState(false)
  const update = useUpdateScript()
  const rewrite = useRewriteScript()
  const metadata = useScriptMetadata()
  const remove = useDeleteScript()

  const dirty = title !== script.title || body !== script.body || idea !== (script.idea ?? '') || language !== script.language || videoType !== script.videoType
  const minutes = spokenMinutes(body, language)
  const ta = language === 'TA' ? 'ta' : undefined

  function save() {
    update.mutate(
      { id: script.id, title: title.trim(), body, idea: idea.trim() || undefined, language, videoType, projectId: script.projectId },
      { onSuccess: () => toast.success('Saved'), onError: (e) => toast.error(e.message) },
    )
  }

  function doRewrite(text: string) {
    if (!writer.writer || !text.trim()) return
    if (dirty) {
      toast.info('Save your changes first, so the rewrite starts from them.')
      return
    }
    rewrite.mutate(
      { id: script.id, writer: writer.writer, instruction: text.trim() },
      {
        onSuccess: (s) => {
          toast.success(`Version ${s.versionNo} written. The earlier version is kept.`)
          setInstruction('')
          onSelect(s.id)
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  return (
    <>
      <Card label="Script">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-muted">
            Version {script.versionNo}
            {script.parentId && (
              <>
                {' '}
                of{' '}
                <button type="button" className="underline hover:text-ink" onClick={() => onSelect(script.parentId)}>
                  the earlier script
                </button>
              </>
            )}
            , updated {formatRelative(script.updatedAt)}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="primary" icon={<Clapperboard className="size-4" />} onClick={() => setBoardOpen(true)} disabled={dirty || !body.trim()}>
              Make storyboard
            </Button>
            <Button size="sm" variant="ghost" icon={<Trash2 className="size-4" />} onClick={() => setConfirmDelete(true)}>
              Delete
            </Button>
          </div>
        </div>
        <Field label="Title">{(fid) => <Input id={fid} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} lang={ta} />}</Field>
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="Video type">
            {(fid) => (
              <Select id={fid} value={videoType} onChange={(e) => setVideoType(e.target.value as VideoType)}>
                {VIDEO_TYPES.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Language">
            {(fid) => (
              <Select id={fid} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
                <option value="TA">Tamil</option>
                <option value="EN">English</option>
              </Select>
            )}
          </Field>
        </div>
        <Field label="Script" hint={`About ${formatMinutes(minutes)} spoken. Lines in [brackets] and (Visual: …) notes aren't read aloud.`}>
          {(fid, d) => <Textarea id={fid} aria-describedby={d} rows={18} maxLength={100_000} value={body} onChange={(e) => setBody(e.target.value)} lang={ta} className="leading-relaxed" />}
        </Field>
        <Field label="Idea">{(fid) => <Textarea id={fid} rows={2} maxLength={2000} value={idea} onChange={(e) => setIdea(e.target.value)} />}</Field>
        <div className="flex items-center gap-3">
          <Button variant="primary" onClick={save} loading={update.isPending} disabled={!dirty || !title.trim()}>
            Save changes
          </Button>
          {dirty && <span className="text-sm text-muted">Unsaved changes</span>}
        </div>
      </Card>

      <Card label="Rewrite">
        <div>
          <h2 className="font-semibold">Rewrite with AI</h2>
          <p className="text-sm text-muted">Makes a new version; this one stays as it is.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {QUICK_REWRITES.concat(language === 'TA' ? ['Translate to English'] : ['Translate to Tamil']).map((q) => (
            <Button key={q} size="sm" onClick={() => doRewrite(q)} disabled={!writer.writer || rewrite.isPending}>
              {q}
            </Button>
          ))}
        </div>
        <form
          className="flex flex-col sm:flex-row gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            doRewrite(instruction)
          }}
        >
          <label htmlFor="rewrite-instruction" className="sr-only">
            Your own instruction
          </label>
          <Input id="rewrite-instruction" maxLength={1000} value={instruction} onChange={(e) => setInstruction(e.target.value)} placeholder="Or say what to change" className="flex-1" />
          <Button type="submit" loading={rewrite.isPending} disabled={!writer.writer || !instruction.trim()} icon={<Sparkles className="size-4" />}>
            Rewrite
          </Button>
        </form>
      </Card>

      <Card label="Post text">
        <div>
          <h2 className="font-semibold">Post text for each platform</h2>
          <p className="text-sm text-muted">Titles, descriptions, captions and hashtags written from this script. The publisher can fill them in for you.</p>
        </div>
        <fieldset className="flex flex-wrap gap-x-4 gap-y-2">
          <legend className="sr-only">Platforms</legend>
          {TEXT_PLATFORMS.map((p) => (
            <label key={p.id} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                className="accent-marigold-500"
                checked={platforms.includes(p.id)}
                onChange={(e) => setPlatforms((ps) => (e.target.checked ? [...ps, p.id] : ps.filter((x) => x !== p.id)))}
              />
              {p.label}
            </label>
          ))}
        </fieldset>
        <div>
          <Button
            loading={metadata.isPending}
            disabled={!writer.writer || platforms.length === 0 || platforms.length > 10 || dirty}
            icon={<Sparkles className="size-4" />}
            onClick={() =>
              writer.writer &&
              metadata.mutate(
                { id: script.id, writer: writer.writer, platforms },
                { onSuccess: () => toast.success('Post text written'), onError: (e) => toast.error(e.message) },
              )
            }
          >
            Write post text
          </Button>
          {platforms.length > 10 && <p className="text-sm text-muted mt-2">Choose up to 10 at a time.</p>}
        </div>
        <PlatformTexts script={script} />
      </Card>

      <StoryboardDialog open={boardOpen} onClose={() => setBoardOpen(false)} script={script} writer={writer} />
      <Dialog
        open={confirmDelete}
        title="Delete this script?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                remove.mutate(script.id, {
                  onSuccess: () => {
                    toast.success('Script deleted')
                    setConfirmDelete(false)
                    onSelect(undefined)
                  },
                  onError: (e) => toast.error(e.message),
                })
              }
            >
              Delete script
            </Button>
          </>
        }
      >
        <p className="text-muted">“{script.title}” will be deleted. Later versions made from it stay. Storyboards made from it stay too.</p>
      </Dialog>
    </>
  )
}

function PlatformTexts({ script }: { script: Script }) {
  const entries = Object.entries(script.metadata)
  if (entries.length === 0) return null
  return (
    <ul className="grid md:grid-cols-2 gap-3">
      {entries.map(([platform, t]) => {
        const label = TEXT_PLATFORMS.find((p) => p.id === platform)?.label ?? platform
        const text = [t.title, postText(t)].filter(Boolean).join('\n\n')
        return (
          <li key={platform} className="rounded-xl border border-line p-3.5 flex flex-col gap-2 min-w-0">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{label}</span>
              <Button
                size="sm"
                variant="ghost"
                icon={<Copy className="size-4" />}
                onClick={() =>
                  navigator.clipboard?.writeText(text).then(
                    () => toast.success(`${label} text copied`),
                    () => toast.error('Copying isn\'t allowed here. Select the text instead.'),
                  )
                }
              >
                Copy
              </Button>
            </div>
            {t.title && <p className="font-medium text-sm">{t.title}</p>}
            <p className="text-sm whitespace-pre-line break-words line-clamp-6">{t.caption || t.description}</p>
            {t.hashtags && t.hashtags.length > 0 && <p className="text-sm text-peacock-500 break-words">{t.hashtags.map((h) => `#${h}`).join(' ')}</p>}
          </li>
        )
      })}
    </ul>
  )
}

function StoryboardDialog({ open, onClose, script, writer }: { open: boolean; onClose: () => void; script: Script; writer: WriterState }) {
  const vertical = useMemo(() => ['YOUTUBE_SHORT', 'REEL', 'TIKTOK'].includes(script.videoType), [script.videoType])
  const [format, setFormat] = useState<VideoFormat>(vertical ? 'PORTRAIT' : 'LANDSCAPE')
  const [style, setStyle] = useState('')
  const make = useStoryboardFromScript()
  const navigate = useNavigate()
  return (
    <Dialog
      open={open}
      title="Make a storyboard"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={make.isPending}
            disabled={!writer.writer}
            onClick={() =>
              writer.writer &&
              make.mutate(
                { scriptId: script.id, writer: writer.writer, aspectRatio: format, style: style.trim() || undefined },
                {
                  onSuccess: (b) => {
                    toast.success(`${b.scenes.length} scenes planned`)
                    navigate(`/create/storyboard/${b.id}`)
                  },
                  onError: (e) => toast.error(e.message),
                },
              )
            }
          >
            Plan scenes
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-muted text-sm">
          The AI writer splits “{script.title}” into scenes with shots, camera moves and narration, and links any characters from Character studio that it mentions.
        </p>
        <Field label="Format">
          {(fid) => (
            <Select id={fid} value={format} onChange={(e) => setFormat(e.target.value as VideoFormat)}>
              {FORMATS.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Look (optional)" hint="Used in every image and video prompt.">
          {(fid, d) => <Input id={fid} aria-describedby={d} maxLength={1000} value={style} onChange={(e) => setStyle(e.target.value)} placeholder="Warm 35mm film, shallow depth of field" />}
        </Field>
        {!writer.writer && <p className="text-sm text-muted">Choose an AI writer on the page first.</p>}
        <p className="text-xs text-faint">
          Characters live in <Link to="/create/characters" className="underline">Character studio</Link>.
        </p>
      </div>
    </Dialog>
  )
}

