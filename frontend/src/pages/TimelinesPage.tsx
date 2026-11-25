import { Clapperboard, Copy, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { useCreateTimeline, useDeleteTimeline, useDuplicateTimeline, useTimelines } from '../lib/editorQueries'
import { formatDuration, formatRelative } from '../lib/format'
import { useProjects } from '../lib/queries'
import type { TimelineSummary } from '../lib/types'
import { FORMATS, newTimeline } from '../remotion/timeline'
import { toast } from '../stores/ui'

export function TimelinesPage() {
  const [page, setPage] = useState(0)
  const timelines = useTimelines(undefined, page)
  const [creating, setCreating] = useState(false)

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Video editor</h1>
          <p className="text-muted mt-1 max-w-2xl">
            Cut, arrange and layer your clips, voice-overs, music and Tamil or English text, then export an MP4 to your media library.
          </p>
        </div>
        <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
          New timeline
        </Button>
      </header>

      {timelines.isPending && <Spinner label="Loading timelines" />}
      {timelines.error && <ErrorNotice error={timelines.error} onRetry={() => timelines.refetch()} />}
      {timelines.data?.items.length === 0 && (
        <EmptyState
          title="No timelines yet"
          body="Start one for a YouTube video, a Short or an Instagram post. You can add anything from your media library."
          action={<Button variant="primary" onClick={() => setCreating(true)}>New timeline</Button>}
        />
      )}
      {timelines.data && timelines.data.items.length > 0 && (
        <ul className="grid sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4 gap-4">
          {timelines.data.items.map((t) => (
            <TimelineCard key={t.id} t={t} />
          ))}
        </ul>
      )}
      {timelines.data && timelines.data.totalPages > 1 && (
        <div className="flex justify-center gap-2 mt-6">
          <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Newer
          </Button>
          <Button size="sm" disabled={page + 1 >= timelines.data.totalPages} onClick={() => setPage((p) => p + 1)}>
            Older
          </Button>
        </div>
      )}
      {creating && <NewTimelineDialog onClose={() => setCreating(false)} />}
    </>
  )
}

function TimelineCard({ t }: { t: TimelineSummary }) {
  const navigate = useNavigate()
  const dup = useDuplicateTimeline()
  const del = useDeleteTimeline()
  const [confirm, setConfirm] = useState(false)
  const portrait = t.height > t.width
  return (
    <li className="rounded-xl border border-line bg-surface overflow-hidden flex flex-col">
      <Link to={`/edit/timeline/${t.id}`} className="block bg-[#0b0d18]" aria-label={`Open ${t.name}`}>
        <div className="aspect-video flex items-center justify-center overflow-hidden">
          {t.poster?.posterUrl ? (
            <img src={t.poster.posterUrl} alt="" loading="lazy" className={cn('max-h-full', portrait ? 'h-full w-auto' : 'w-full object-cover')} />
          ) : (
            <Clapperboard className="size-8 text-white/30" aria-hidden />
          )}
        </div>
      </Link>
      <div className="p-3 flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="font-medium truncate">{t.name}</p>
          <p className="text-xs text-faint tabular">
            {t.width}×{t.height} · {formatDuration(Number(t.durationSeconds) * 1000)} · edited {formatRelative(t.updatedAt)}
          </p>
        </div>
        <Button
          size="sm"
          variant="ghost"
          aria-label={`Duplicate ${t.name}`}
          title="Duplicate"
          loading={dup.isPending}
          icon={<Copy className="size-4" />}
          onClick={() => dup.mutate(t.id, { onSuccess: (d) => navigate(`/edit/timeline/${d.id}`), onError: (e) => toast.error(e.message) })}
        />
        <Button size="sm" variant="ghost" aria-label={`Delete ${t.name}`} icon={<Trash2 className="size-4" />} onClick={() => setConfirm(true)} />
      </div>
      <Dialog
        open={confirm}
        title={`Delete “${t.name}”?`}
        onClose={() => setConfirm(false)}
        footer={
          <>
            <Button onClick={() => setConfirm(false)}>Cancel</Button>
            <Button variant="danger" loading={del.isPending} onClick={() => del.mutate(t.id, { onSuccess: () => setConfirm(false), onError: (e) => toast.error(e.message) })}>
              Delete
            </Button>
          </>
        }
      >
        <p className="text-muted">The edit is removed. Your media files and exported videos stay in the media library.</p>
      </Dialog>
    </li>
  )
}

function NewTimelineDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const create = useCreateTimeline()
  const projects = useProjects({ page: 0 })
  const [name, setName] = useState('New video')
  const [format, setFormat] = useState<string>('landscape')
  const [fps, setFps] = useState(30)
  const [projectId, setProjectId] = useState('')
  const f = FORMATS.find((x) => x.id === format) ?? FORMATS[0]

  function submit() {
    create.mutate(
      { name: name.trim() || 'New video', document: newTimeline(f.width, f.height, fps), projectId: projectId || undefined },
      { onSuccess: (t) => navigate(`/edit/timeline/${t.id}`), onError: (e) => toast.error(e.message) },
    )
  }

  return (
    <Dialog
      open
      title="New timeline"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={create.isPending}>
            Create
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Name">{(id) => <Input id={id} value={name} maxLength={160} onChange={(e) => setName(e.target.value)} autoFocus />}</Field>
        <fieldset>
          <legend className="text-sm font-medium mb-2">Format</legend>
          <div role="radiogroup" className="grid grid-cols-2 gap-2">
            {FORMATS.map((x) => (
              <button
                key={x.id}
                type="button"
                role="radio"
                aria-checked={format === x.id}
                onClick={() => setFormat(x.id)}
                className={cn('rounded-xl border p-3 flex items-center gap-3 text-left', format === x.id ? 'border-marigold-500 bg-marigold-500/10' : 'border-line hover:bg-surface-2')}
              >
                <span className="shrink-0 border-2 border-current rounded-sm text-faint" style={{ width: (x.width / Math.max(x.width, x.height)) * 28, height: (x.height / Math.max(x.width, x.height)) * 28 }} aria-hidden />
                <span className="text-sm">{x.label}</span>
              </button>
            ))}
          </div>
        </fieldset>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Frame rate">
            {(id) => (
              <Select id={id} value={fps} onChange={(e) => setFps(Number(e.target.value))}>
                {[24, 25, 30, 50, 60].map((v) => (
                  <option key={v} value={v}>
                    {v} fps
                  </option>
                ))}
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
      </div>
    </Dialog>
  )
}
