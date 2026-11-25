import { AlertCircle, Check, Copy, Film, RotateCcw, Square, Trash2, X } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { useJobAction } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatAmount, formatRelative } from '../lib/format'
import { isActive, prettyRatio, statusLine } from '../lib/generation'
import type { GenerationJob } from '../lib/types'
import { toast } from '../stores/ui'
import { Button, Dialog } from './ui'

export function TakeGroup({
  jobs,
  selected,
  onToggleSelect,
  onReuse,
}: {
  jobs: GenerationJob[]
  selected: Set<string>
  onToggleSelect: (id: string) => void
  onReuse: (j: GenerationJob) => void
}) {
  const first = jobs[0]
  const [copied, setCopied] = useState(false)
  const settings = [
    first.modelName,
    first.durationSeconds ? `${first.durationSeconds} s` : undefined,
    prettyRatio(first.aspectRatio),
    first.resolution,
    first.audio ? 'with sound' : undefined,
    first.mode === 'IMAGE_TO_VIDEO' ? 'from image' : undefined,
  ].filter(Boolean)

  return (
    <article className="rounded-2xl border border-line bg-surface p-4 sm:p-5">
      <header className="flex flex-col sm:flex-row sm:items-start gap-x-3 gap-y-1 mb-4">
        <div className="min-w-0 flex-1">
          <p className="line-clamp-2 font-medium">{first.prompt}</p>
          <p className="text-xs text-faint mt-1">
            {settings.join(', ')}. {first.accountName}, {formatRelative(first.createdAt)}
          </p>
        </div>
        <div className="flex shrink-0 -ml-2 sm:ml-0">
          <button
            onClick={() => {
              navigator.clipboard?.writeText(first.prompt)
              setCopied(true)
              setTimeout(() => setCopied(false), 1500)
            }}
            className="p-2 rounded-lg text-faint hover:text-ink"
            aria-label="Copy prompt"
            title="Copy prompt"
          >
            {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
          </button>
          <Button size="sm" variant="ghost" onClick={() => onReuse(first)}>
            Use these settings
          </Button>
        </div>
      </header>
      <ul className="grid gap-3 sm:grid-cols-2">
        {jobs.map((j) => (
          <TakeTile key={j.id} job={j} selected={selected.has(j.id)} onToggleSelect={() => onToggleSelect(j.id)} />
        ))}
      </ul>
    </article>
  )
}

function TakeTile({ job: j, selected, onToggleSelect }: { job: GenerationJob; selected: boolean; onToggleSelect: () => void }) {
  const action = useJobAction()
  const run = (a: 'cancel' | 'regenerate' | 'delete') =>
    action.mutate(
      { id: j.id, action: a },
      {
        onSuccess: () => toast.success(a === 'cancel' ? 'Generation cancelled' : a === 'regenerate' ? 'New take queued' : 'Removed from history'),
        onError: (e) => toast.error(e.message),
      },
    )
  const cost = j.actualCost ?? j.estimatedCost

  return (
    <li className="min-w-0">
      <div className={cn('relative aspect-video rounded-xl overflow-hidden bg-[#0d1020]', selected && 'ring-2 ring-marigold-500')}>
        {j.status === 'COMPLETED' && j.output ? (
          <video src={j.output.url} poster={j.output.posterUrl} controls preload="metadata" playsInline className="size-full object-contain" />
        ) : (
          <RenderPlaceholder job={j} />
        )}
        {j.status === 'COMPLETED' && j.output && (
          <button
            onClick={onToggleSelect}
            aria-pressed={selected}
            className={cn(
              'absolute top-2 left-2 inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium',
              selected ? 'bg-marigold-500 text-[#1e2240]' : 'bg-[#0d1020]/70 text-white hover:bg-[#0d1020]/90',
            )}
          >
            {selected ? <Check className="size-3.5" /> : <Square className="size-3.5" />}
            Compare
          </button>
        )}
      </div>
      <div className="mt-2 flex items-center gap-2 text-xs">
        <span className={cn('font-medium', j.status === 'FAILED' ? 'text-rose-signal' : j.status === 'COMPLETED' ? 'text-peacock-600 dark:text-peacock-400' : 'text-muted')}>
          {statusLine(j)}
        </span>
        {cost != null && <span className="text-faint tabular">{formatAmount(cost, j.costUnit)}{j.actualCost == null ? ' est.' : ''}</span>}
        <span className="ml-auto flex">
          {isActive(j) ? (
            <IconButton label="Cancel" onClick={() => run('cancel')} busy={action.isPending}>
              <X className="size-4" />
            </IconButton>
          ) : (
            <>
              <IconButton label="Generate again" onClick={() => run('regenerate')} busy={action.isPending}>
                <RotateCcw className="size-4" />
              </IconButton>
              <IconButton label="Remove from history (keeps the video in your library)" onClick={() => run('delete')} busy={action.isPending}>
                <Trash2 className="size-4" />
              </IconButton>
            </>
          )}
        </span>
      </div>
      {(j.status === 'FAILED' || (j.errorMessage && isActive(j))) && (
        <p className={cn('mt-1 text-xs', j.status === 'FAILED' ? 'text-rose-signal' : 'text-faint')}>{j.errorMessage}</p>
      )}
    </li>
  )
}

function IconButton({ label, onClick, busy, children }: { label: string; onClick: () => void; busy?: boolean; children: React.ReactNode }) {
  return (
    <button onClick={onClick} disabled={busy} aria-label={label} title={label} className="p-1.5 rounded text-faint hover:text-ink disabled:opacity-50">
      {children}
    </button>
  )
}

/** While rendering: a playhead sweeping a strip of frames, filling with marigold as progress arrives. */
function RenderPlaceholder({ job: j }: { job: GenerationJob }) {
  const pct = j.status === 'PROCESSING' ? Math.max(0.04, j.progress ?? 0.04) : 0
  const failed = j.status === 'FAILED' || j.status === 'CANCELLED'
  return (
    <div className="absolute inset-0 flex flex-col justify-end p-3">
      <div className="absolute inset-0 flex items-center justify-center text-[#a3a8c6]/50">
        {failed ? <AlertCircle className="size-8" aria-hidden /> : <Film className="size-8" aria-hidden />}
      </div>
      {!failed && (
        <div className="relative h-1.5 rounded-full bg-white/10 overflow-hidden" role="progressbar" aria-valuenow={Math.round(pct * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Progress">
          <div className={cn('h-full bg-marigold-500 transition-[width] duration-700', j.status === 'QUEUED' && 'animate-pulse w-full opacity-30')} style={j.status === 'PROCESSING' ? { width: `${pct * 100}%` } : undefined} />
        </div>
      )}
    </div>
  )
}

/** Side-by-side playback of up to four takes, started and paused together. */
export function CompareDialog({ jobs, onClose }: { jobs: GenerationJob[]; onClose: () => void }) {
  const refs = useRef<(HTMLVideoElement | null)[]>([])
  const playable = useMemo(() => jobs.filter((j) => j.output), [jobs])

  const all = (fn: (v: HTMLVideoElement) => void) => refs.current.forEach((v) => v && fn(v))

  return (
    <Dialog open size="xl" title={`Compare ${playable.length} takes`} onClose={onClose}>
      <div>
        <div className={cn('grid gap-3', playable.length > 2 ? 'grid-cols-2' : playable.length === 2 ? 'sm:grid-cols-2' : '')}>
          {playable.map((j, i) => (
            <figure key={j.id}>
              <video
                ref={(el) => {
                  refs.current[i] = el
                }}
                src={j.output!.url}
                poster={j.output!.posterUrl}
                controls
                muted
                playsInline
                preload="auto"
                className="w-full aspect-video rounded-lg bg-[#0d1020] object-contain"
              />
              <figcaption className="text-xs text-muted mt-1">
                {j.modelName}
                {j.variationIndex > 0 ? `, take ${j.variationIndex + 1}` : ''}
              </figcaption>
            </figure>
          ))}
        </div>
        <div className="mt-4 flex gap-2">
          <Button
            variant="primary"
            onClick={() => {
              all((v) => {
                v.currentTime = 0
              })
              all((v) => void v.play())
            }}
          >
            Play all from start
          </Button>
          <Button onClick={() => all((v) => v.pause())}>Pause all</Button>
        </div>
      </div>
    </Dialog>
  )
}
