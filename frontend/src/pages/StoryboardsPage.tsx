import { Clapperboard, Film, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { MediaThumb } from '../components/media'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import { useProjects } from '../lib/queries'
import { FORMAT_CLASS, FORMAT_LABEL, FORMATS, formatSeconds } from '../lib/story'
import { useCreateStoryboard, useScripts, useStoryboardFromScript, useStoryboards } from '../lib/storyQueries'
import type { Language, VideoFormat } from '../lib/types'
import { toast } from '../stores/ui'

export function StoryboardsPage() {
  const [params, setParams] = useSearchParams()
  const projectId = params.get('project') ?? ''
  const boards = useStoryboards(projectId || undefined)
  const projects = useProjects({ page: 0 })
  const [creating, setCreating] = useState(false)

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div className="min-w-0">
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Storyboard studio</h1>
          <p className="text-muted mt-1 max-w-2xl">
            Plan a video scene by scene: shots, camera, narration and dialogue. Make an image and a video for each scene, then send it all to the editor.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="board-project" className="sr-only">
            Project
          </label>
          <div className="w-60">
          <Select
            id="board-project"
            value={projectId}
            onChange={(e) => {
              const next = new URLSearchParams(params)
              if (e.target.value) next.set('project', e.target.value)
              else next.delete('project')
              setParams(next)
            }}
          >
            <option value="">All projects</option>
            {(projects.data?.items ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </Select>
          </div>
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
            New storyboard
          </Button>
        </div>
      </header>

      {boards.isPending && <Spinner label="Loading storyboards" />}
      {boards.error && <ErrorNotice error={boards.error} onRetry={() => boards.refetch()} />}
      {boards.data && boards.data.length === 0 && (
        <EmptyState
          title="No storyboards yet"
          body="Start from a script in Scripts & ideas and the AI writer plans the scenes, or start with a blank board."
          action={
            <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
              New storyboard
            </Button>
          }
        />
      )}
      {boards.data && boards.data.length > 0 && (
        <ul className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {boards.data.map((b) => (
            <li key={b.id}>
              <Link to={`/create/storyboard/${b.id}`} className="group block rounded-2xl border border-line bg-surface overflow-hidden hover:border-marigold-500">
                <div className="aspect-video bg-surface-2 grid place-items-center overflow-hidden">
                  {b.cover ? (
                    <MediaThumb asset={b.cover} className={cn('h-full', FORMAT_CLASS[b.aspectRatio])} />
                  ) : (
                    <Clapperboard className="size-8 text-faint" aria-hidden />
                  )}
                </div>
                <div className="p-4">
                  <h2 className="font-semibold truncate group-hover:underline" lang={b.language === 'TA' ? 'ta' : undefined}>
                    {b.title}
                  </h2>
                  <p className="text-sm text-muted mt-1">
                    {b.sceneCount} {b.sceneCount === 1 ? 'scene' : 'scenes'}, {formatSeconds(b.totalSeconds)}, {FORMAT_LABEL[b.aspectRatio]}
                  </p>
                  <div className="mt-3 flex flex-col gap-1.5" aria-label="Progress">
                    <Meter label="Images" value={b.imagesReady} total={b.sceneCount} />
                    <Meter label="Videos" value={b.videosReady} total={b.sceneCount} />
                  </div>
                  <p className="text-xs text-faint mt-3 flex items-center gap-1.5">
                    {b.timelineId && <Film className="size-3.5" aria-hidden />}
                    {b.timelineId ? 'In the editor, ' : ''}updated {formatRelative(b.updatedAt)}
                  </p>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {creating && <NewStoryboardDialog projectId={projectId} onClose={() => setCreating(false)} />}
    </>
  )
}

function Meter({ label, value, total }: { label: string; value: number; total: number }) {
  const pct = total ? Math.round((value / total) * 100) : 0
  return (
    <div className="grid grid-cols-[4rem_minmax(0,1fr)_3rem] items-center gap-2 text-xs">
      <span className="text-muted">{label}</span>
      <span className="h-1.5 rounded-full bg-track overflow-hidden" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={total} aria-valuenow={value}>
        <span className="block h-full rounded-full bg-peacock-500" style={{ width: `${pct}%` }} />
      </span>
      <span className="tabular text-right text-muted">
        {value}/{total}
      </span>
    </div>
  )
}

function NewStoryboardDialog({ projectId: initialProject, onClose }: { projectId: string; onClose: () => void }) {
  const [source, setSource] = useState<'script' | 'blank'>('script')
  const [projectId, setProjectId] = useState(initialProject)
  const [scriptId, setScriptId] = useState('')
  const [title, setTitle] = useState('')
  const [format, setFormat] = useState<VideoFormat>('LANDSCAPE')
  const [language, setLanguage] = useState<Language>('TA')
  const [style, setStyle] = useState('')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const text = useUsableAccounts('TEXT')
  useDefaultAccount(text.usable, accountId, setAccountId)
  const scripts = useScripts(projectId || undefined)
  const projects = useProjects({ page: 0 })
  const fromScript = useStoryboardFromScript()
  const blank = useCreateStoryboard()
  const navigate = useNavigate()
  const script = (scripts.data ?? []).find((s) => s.id === scriptId)
  const busy = fromScript.isPending || blank.isPending
  const ready = source === 'script' ? !!scriptId && !!accountId && !!model : !!title.trim()

  function go() {
    const done = (id: string) => navigate(`/create/storyboard/${id}`)
    if (source === 'script') {
      fromScript.mutate(
        { scriptId, writer: { accountId, model }, aspectRatio: format, style: style.trim() || undefined, title: title.trim() || undefined },
        { onSuccess: (b) => { toast.success(`${b.scenes.length} scenes planned`); done(b.id) }, onError: (e) => toast.error(e.message) },
      )
    } else {
      blank.mutate(
        { title: title.trim(), aspectRatio: format, language, style: style.trim() || undefined, projectId: projectId || undefined },
        { onSuccess: (b) => done(b.id), onError: (e) => toast.error(e.message) },
      )
    }
  }

  return (
    <Dialog
      open
      title="New storyboard"
      onClose={onClose}
      size="xl"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={busy} disabled={!ready} onClick={go}>
            {source === 'script' ? 'Plan scenes' : 'Create storyboard'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 max-w-2xl">
        <div role="radiogroup" aria-label="Start from" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1 text-sm">
          {(['script', 'blank'] as const).map((v) => (
            <button
              key={v}
              type="button"
              role="radio"
              aria-checked={source === v}
              onClick={() => setSource(v)}
              className={cn('rounded-lg py-2 font-medium', source === v ? 'bg-surface shadow-sm text-ink' : 'text-muted hover:text-ink')}
            >
              {v === 'script' ? 'From a script' : 'Blank'}
            </button>
          ))}
        </div>
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="Project">
            {(id) => (
              <Select id={id} value={projectId} onChange={(e) => { setProjectId(e.target.value); setScriptId('') }}>
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
        </div>
        {source === 'script' ? (
          <>
            <Field label="Script">
              {(id) =>
                scripts.isPending ? (
                  <Spinner label="Loading scripts" />
                ) : (scripts.data ?? []).length === 0 ? (
                  <p className="text-sm text-muted" id={id}>
                    No scripts{projectId ? ' in this project' : ''} yet. <Link to="/create/scripts" className="underline">Write one in Scripts & ideas</Link>.
                  </p>
                ) : (
                  <Select id={id} value={scriptId} onChange={(e) => setScriptId(e.target.value)}>
                    <option value="">Choose a script</option>
                    {(scripts.data ?? []).map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.title}
                        {s.versionNo > 1 ? ` (version ${s.versionNo})` : ''}
                      </option>
                    ))}
                  </Select>
                )
              }
            </Field>
            {script && <p className="text-sm text-muted -mt-2">{script.language === 'TA' ? 'Tamil' : 'English'}, about {Math.max(1, Math.round(script.spokenMinutes))} min spoken. The storyboard uses the script's language.</p>}
            <TextModelPicker
              accounts={text.usable}
              accountId={accountId}
              setAccountId={setAccountId}
              model={model}
              setModel={setModel}
              required
              hint="Plans the scenes, shots and camera moves."
            />
          </>
        ) : (
          <div className="grid sm:grid-cols-2 gap-4">
            <Field label="Title">{(id) => <Input id={id} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
            <Field label="Language">
              {(id) => (
                <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
                  <option value="TA">Tamil</option>
                  <option value="EN">English</option>
                </Select>
              )}
            </Field>
          </div>
        )}
        <Field label="Look (optional)" hint="Goes into every image and video prompt, so the scenes match.">
          {(id, d) => <Input id={id} aria-describedby={d} maxLength={1000} value={style} onChange={(e) => setStyle(e.target.value)} placeholder="Warm 35mm film, golden hour, shallow depth of field" />}
        </Field>
        {source === 'script' && (
          <Field label="Title (optional)">{(id) => <Input id={id} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} placeholder={script?.title ?? 'Uses the script title'} />}</Field>
        )}
      </div>
    </Dialog>
  )
}
