import { AudioLines, Film, ImageIcon, Upload, X } from 'lucide-react'
import { useCallback, useRef, useState, type DragEvent } from 'react'
import { ApiError, uploadFile } from '../lib/api'
import { cn } from '../lib/cn'
import { formatBytes, formatDuration } from '../lib/format'
import { useInvalidateAfterUpload } from '../lib/queries'
import type { MediaAsset, MediaKind } from '../lib/types'
import { toast } from '../stores/ui'

export const KIND_ICON: Record<MediaKind, typeof Film> = { VIDEO: Film, IMAGE: ImageIcon, AUDIO: AudioLines }
export const KIND_LABEL: Record<MediaKind, string> = { VIDEO: 'Video', IMAGE: 'Image', AUDIO: 'Audio' }

export const ACCEPT =
  'video/mp4,video/quicktime,video/webm,video/x-matroska,.mkv,image/jpeg,image/png,image/webp,image/gif,audio/mpeg,audio/wav,audio/x-wav,audio/mp4,audio/aac,audio/flac,audio/ogg,.m4a'

export function MediaThumb({ asset, className }: { asset: MediaAsset; className?: string }) {
  const Icon = KIND_ICON[asset.kind]
  const [broken, setBroken] = useState(false)
  const showImage = asset.posterUrl && !broken
  return (
    <div className={cn('relative overflow-hidden bg-surface-2', className)}>
      {showImage ? (
        <img
          src={asset.posterUrl}
          alt=""
          loading="lazy"
          onError={() => setBroken(true)}
          className="absolute inset-0 size-full object-cover"
        />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center text-faint">
          {asset.kind === 'AUDIO' ? <Waveform seed={asset.id} /> : <Icon className="size-7" aria-hidden />}
        </div>
      )}
      {asset.durationMs != null && (
        <span className="absolute bottom-1.5 right-1.5 rounded bg-[#0d1020]/75 px-1.5 py-px text-[0.72rem] text-white tabular">
          {formatDuration(asset.durationMs)}
        </span>
      )}
    </div>
  )
}

/** Deterministic pseudo-waveform for audio files, seeded by id so it is stable per file. */
function waveformBars(seed: string): number[] {
  let h = 0
  for (const c of seed) h = (h * 31 + c.charCodeAt(0)) >>> 0
  const bars: number[] = []
  for (let i = 0; i < 28; i++) {
    h = (h * 1103515245 + 12345) >>> 0
    bars.push(20 + ((h >> 8) % 70) * (0.6 + 0.4 * Math.sin(i / 3)))
  }
  return bars
}

function Waveform({ seed }: { seed: string }) {
  const bars = waveformBars(seed)
  return (
    <div className="flex items-center gap-[3px] h-1/2 w-3/4" aria-hidden>
      {bars.map((v, i) => (
        <span key={i} className="flex-1 rounded-full bg-peacock-500/60" style={{ height: `${Math.max(12, Math.min(100, v))}%` }} />
      ))}
    </div>
  )
}

// ---- Upload ---------------------------------------------------------------

interface UploadItem {
  key: number
  name: string
  size: number
  progress: number
  state: 'uploading' | 'processing' | 'done' | 'error'
  error?: string
  controller: AbortController
}

let uploadKey = 1

export function UploadZone({ projectId, compact }: { projectId?: string; compact?: boolean }) {
  const [items, setItems] = useState<UploadItem[]>([])
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const invalidate = useInvalidateAfterUpload()

  const patch = (key: number, p: Partial<UploadItem>) =>
    setItems((list) => list.map((i) => (i.key === key ? { ...i, ...p } : i)))

  const start = useCallback(
    async (files: FileList | File[]) => {
      const queue = Array.from(files)
      const added = queue.map((f) => ({
        key: uploadKey++,
        name: f.name,
        size: f.size,
        progress: 0,
        state: 'uploading' as const,
        controller: new AbortController(),
      }))
      setItems((list) => [...added, ...list])

      // One at a time keeps large video uploads from fighting for bandwidth.
      for (let i = 0; i < queue.length; i++) {
        const item = added[i]
        if (item.controller.signal.aborted) continue
        try {
          await uploadFile<MediaAsset>(
            projectId ? `/media?projectId=${encodeURIComponent(projectId)}` : '/media',
            queue[i],
            (p) => patch(item.key, { progress: p, state: p >= 1 ? 'processing' : 'uploading' }),
            item.controller.signal,
          )
          patch(item.key, { progress: 1, state: 'done' })
          invalidate()
        } catch (e) {
          const message = e instanceof ApiError ? e.message : 'Upload failed'
          patch(item.key, { state: 'error', error: message })
          if (!(e instanceof ApiError && e.code === 'ABORTED')) toast.error(`${queue[i].name}: ${message}`)
        }
      }
      setTimeout(() => setItems((list) => list.filter((x) => x.state !== 'done')), 4000)
    },
    [projectId, invalidate],
  )

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    if (e.dataTransfer.files.length) start(e.dataTransfer.files)
  }

  return (
    <div>
      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn(
          'rounded-2xl border-2 border-dashed transition-colors flex items-center gap-4',
          compact ? 'px-4 py-4' : 'px-6 py-8 flex-col sm:flex-row justify-center text-center sm:text-left',
          dragging ? 'border-marigold-500 bg-marigold-500/8' : 'border-line',
        )}
      >
        <div className="rounded-xl bg-surface-2 p-3 text-muted">
          <Upload className="size-5" aria-hidden />
        </div>
        <div className="flex-1">
          <p className="font-medium">Drop videos, images or audio here</p>
          <p className="text-sm text-faint">MP4, MOV, WebM, MKV, JPG, PNG, WebP, MP3, WAV, M4A and more</p>
        </div>
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          className="h-10 px-4 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] font-semibold"
        >
          Choose files
        </button>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPT}
          className="sr-only"
          tabIndex={-1}
          onChange={(e) => {
            if (e.target.files?.length) start(e.target.files)
            e.target.value = ''
          }}
        />
      </div>

      {items.length > 0 && (
        <ul className="mt-3 flex flex-col gap-2" aria-label="Uploads">
          {items.map((i) => (
            <li key={i.key} className="rounded-xl border border-line bg-surface px-4 py-3">
              <div className="flex items-center gap-3">
                <p className="flex-1 truncate text-sm font-medium">{i.name}</p>
                <span className="text-xs text-faint tabular">
                  {i.state === 'uploading' && `${Math.round(i.progress * 100)}% of ${formatBytes(i.size)}`}
                  {i.state === 'processing' && 'Processing…'}
                  {i.state === 'done' && 'Uploaded'}
                  {i.state === 'error' && <span className="text-rose-signal">{i.error}</span>}
                </span>
                {(i.state === 'uploading' || i.state === 'error') && (
                  <button
                    onClick={() => {
                      i.controller.abort()
                      setItems((l) => l.filter((x) => x.key !== i.key))
                    }}
                    className="text-faint hover:text-ink"
                    aria-label={i.state === 'error' ? `Dismiss ${i.name}` : `Cancel upload of ${i.name}`}
                  >
                    <X className="size-4" />
                  </button>
                )}
              </div>
              <div className="mt-2 h-1 rounded-full bg-track overflow-hidden">
                <div
                  className={cn(
                    'h-full rounded-full transition-[width] duration-300',
                    i.state === 'error' ? 'bg-rose-signal' : i.state === 'done' ? 'bg-peacock-500' : 'bg-marigold-500',
                    i.state === 'processing' && 'animate-pulse',
                  )}
                  style={{ width: `${Math.round(i.progress * 100)}%` }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
