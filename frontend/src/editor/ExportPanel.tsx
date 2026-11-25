import { CheckCircle2, Download, Loader2, X, XCircle } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Dialog, Field, Select } from '../components/ui'
import { cn } from '../lib/cn'
import { useRenderAction, useRenderAvailable, useRenders, useStartRender } from '../lib/editorQueries'
import { formatRelative } from '../lib/format'
import type { RenderJob, RenderQuality } from '../lib/types'
import type { TimelineDoc } from '../remotion/types'
import { toast } from '../stores/ui'

const QUALITY: { id: RenderQuality; label: string; body: string }[] = [
  { id: 'STANDARD', label: 'Standard', body: 'Fast. Right for most uploads.' },
  { id: 'HIGH', label: 'High', body: 'Sharper, larger file.' },
  { id: 'BEST', label: 'Best', body: 'Slowest encode, smallest file at top quality.' },
]

export function outputSize(doc: Pick<TimelineDoc, 'width' | 'height'>, resolution: number) {
  const s = Math.min(2, Math.max(0.25, resolution / Math.min(doc.width, doc.height)))
  const even = (v: number) => {
    const r = Math.round(v)
    return r - (r % 2)
  }
  return { width: even(doc.width * s), height: even(doc.height * s) }
}

export function ExportDialog({ timelineId, doc, onClose, beforeStart }: { timelineId: string; doc: TimelineDoc; onClose: () => void; beforeStart: () => Promise<boolean> }) {
  const available = useRenderAvailable()
  const start = useStartRender()
  const short = Math.min(doc.width, doc.height)
  const options = [720, 1080, 1440, 2160].filter((r) => r <= short * 2)
  const [resolution, setResolution] = useState(options.includes(1080) ? 1080 : options[0])
  const [quality, setQuality] = useState<RenderQuality>('STANDARD')
  const size = outputSize(doc, resolution)

  async function go() {
    if (!(await beforeStart())) {
      toast.error('Save your changes first')
      return
    }
    start.mutate(
      { timelineId, resolution, quality },
      {
        onSuccess: () => {
          toast.success('Export started. You can keep editing.')
          onClose()
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  return (
    <Dialog
      open
      title="Export video"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={go} loading={start.isPending} disabled={available.data?.available === false}>
            Export MP4
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {available.data?.available === false && (
          <p className="text-sm text-rose-signal">The render service isn’t set up. Start the renderer service (see README).</p>
        )}
        <Field label="Resolution" hint={`${size.width}×${size.height}, ${doc.fps} fps, H.264 + AAC`}>
          {(id, d) => (
            <Select id={id} aria-describedby={d} value={resolution} onChange={(e) => setResolution(Number(e.target.value))}>
              {options.map((r) => (
                <option key={r} value={r}>
                  {r === 2160 ? '4K (2160p)' : `${r}p`}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <fieldset>
          <legend className="text-sm font-medium mb-2">Quality</legend>
          <div role="radiogroup" className="grid grid-cols-3 gap-2">
            {QUALITY.map((q) => (
              <button
                key={q.id}
                type="button"
                role="radio"
                aria-checked={quality === q.id}
                onClick={() => setQuality(q.id)}
                className={cn('rounded-xl border p-2.5 text-left', quality === q.id ? 'border-marigold-500 bg-marigold-500/10' : 'border-line hover:bg-surface-2')}
              >
                <span className="block text-sm font-medium">{q.label}</span>
                <span className="block text-xs text-muted">{q.body}</span>
              </button>
            ))}
          </div>
        </fieldset>
        <p className="text-xs text-faint">The finished video is saved to your media library{doc ? '' : ''}. Exports run on your server; a 10-minute 1080p video usually takes several minutes.</p>
      </div>
    </Dialog>
  )
}

/** Exports of this timeline with live progress. */
export function RendersList({ timelineId }: { timelineId: string }) {
  const renders = useRenders(timelineId)
  const action = useRenderAction()
  const jobs = renders.data ?? []
  if (jobs.length === 0) return <p className="text-sm text-muted p-4">No exports yet.</p>
  return (
    <ul className="flex flex-col divide-y divide-[var(--line)]">
      {jobs.map((j) => (
        <RenderRow key={j.id} job={j} onCancel={() => action.mutate({ id: j.id, action: 'cancel' })} onRemove={() => action.mutate({ id: j.id, action: 'delete' })} />
      ))}
    </ul>
  )
}

function RenderRow({ job, onCancel, onRemove }: { job: RenderJob; onCancel: () => void; onRemove: () => void }) {
  const active = job.status === 'QUEUED' || job.status === 'RENDERING'
  return (
    <li className="p-3 flex flex-col gap-1.5 text-sm">
      <div className="flex items-center gap-2">
        {active && <Loader2 className="size-4 animate-spin text-marigold-500" aria-hidden />}
        {job.status === 'COMPLETED' && <CheckCircle2 className="size-4 text-peacock-500" aria-hidden />}
        {(job.status === 'FAILED' || job.status === 'CANCELLED') && <XCircle className="size-4 text-rose-signal" aria-hidden />}
        <span className="font-medium">
          {job.outputWidth}×{job.outputHeight}
        </span>
        <span className="text-faint">{job.quality.toLowerCase()}</span>
        <span className="ml-auto text-xs text-faint">{formatRelative(job.createdAt)}</span>
        {active ? (
          <button type="button" aria-label="Cancel export" onClick={onCancel} className="p-1 rounded hover:bg-surface-2 text-faint hover:text-ink">
            <X className="size-4" />
          </button>
        ) : (
          <button type="button" aria-label="Remove from list" onClick={onRemove} className="p-1 rounded hover:bg-surface-2 text-faint hover:text-ink">
            <X className="size-4" />
          </button>
        )}
      </div>
      {active && (
        <div className="flex items-center gap-2">
          <div className="h-1.5 flex-1 rounded-full bg-surface-2 overflow-hidden" role="progressbar" aria-valuenow={Math.round(job.progress * 100)} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full bg-marigold-500 transition-[width]" style={{ width: `${Math.max(2, job.progress * 100)}%` }} />
          </div>
          <span className="tabular text-xs text-muted w-24 text-right">{job.status === 'QUEUED' ? 'Waiting' : `${Math.round(job.progress * 100)}%`}</span>
        </div>
      )}
      {job.status === 'FAILED' && <p className="text-xs text-rose-signal">{job.errorMessage}</p>}
      {job.status === 'COMPLETED' && job.output && (
        <div className="flex gap-3 text-xs">
          <a href={job.output.url} target="_blank" rel="noreferrer" className="text-peacock-600 dark:text-peacock-400 hover:underline">
            Watch
          </a>
          <Link to="/media" className="text-muted hover:text-ink inline-flex items-center gap-1">
            <Download className="size-3.5" aria-hidden /> In media library
          </Link>
        </div>
      )}
    </li>
  )
}
