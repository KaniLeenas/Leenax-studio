import { AlertTriangle, FileUp, X } from 'lucide-react'
import { useCallback, useRef, useState, type DragEvent } from 'react'
import { ApiError } from '../lib/api'
import { DOCUMENT_ACCEPT, highlightParts, type Highlight } from '../lib/documents'
import { formatBytes } from '../lib/format'
import { cn } from '../lib/cn'
import { toast } from '../stores/ui'
import { Button } from './ui'

interface Upload {
  key: number
  name: string
  size: number
  progress: number
  state: 'uploading' | 'saving' | 'done' | 'error'
  error?: string
  controller: AbortController
}

let nextKey = 1

/**
 * Drop zone and file button for course documents. Files go up one at a time;
 * reading them happens afterwards on the server, so "done" here means stored.
 */
export function DocumentDrop({
  send,
  onDone,
  compact,
  narrow,
  label = 'Add documents',
  multiple = true,
}: {
  send: (file: File, onProgress: (f: number) => void, signal: AbortSignal) => Promise<unknown>
  onDone: () => void
  compact?: boolean
  /** For a side column: stacked, with a short line of text. */
  narrow?: boolean
  label?: string
  multiple?: boolean
}) {
  const [items, setItems] = useState<Upload[]>([])
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const patch = (key: number, p: Partial<Upload>) => setItems((l) => l.map((i) => (i.key === key ? { ...i, ...p } : i)))

  const start = useCallback(
    async (files: FileList | File[]) => {
      const queue = Array.from(files).slice(0, multiple ? 20 : 1)
      const added = queue.map((f) => ({
        key: nextKey++,
        name: f.name,
        size: f.size,
        progress: 0,
        state: 'uploading' as const,
        controller: new AbortController(),
      }))
      setItems((l) => [...added, ...l])
      for (let i = 0; i < queue.length; i++) {
        const item = added[i]
        if (item.controller.signal.aborted) continue
        try {
          await send(queue[i], (p) => patch(item.key, { progress: p, state: p >= 1 ? 'saving' : 'uploading' }), item.controller.signal)
          patch(item.key, { progress: 1, state: 'done' })
          onDone()
        } catch (e) {
          const message = e instanceof ApiError ? e.message : 'Upload failed'
          patch(item.key, { state: 'error', error: message })
          if (!(e instanceof ApiError && e.code === 'ABORTED')) toast.error(`${queue[i].name}: ${message}`)
        }
      }
      setTimeout(() => setItems((l) => l.filter((x) => x.state !== 'done')), 4000)
    },
    [send, onDone, multiple],
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
          narrow ? 'px-4 py-4 flex-col text-center gap-3' : compact ? 'px-4 py-4 flex-wrap' : 'px-6 py-8 flex-col text-center',
          dragging ? 'border-marigold-500 bg-marigold-500/5' : 'border-line',
        )}
      >
        {!narrow && <FileUp className={cn('text-faint shrink-0', compact ? 'size-6' : 'size-8')} aria-hidden />}
        {narrow ? (
          <p className="text-sm text-muted">Drop the newer file here. Older versions are kept.</p>
        ) : (
          <div className={cn('min-w-0', compact && 'flex-1')}>
            <p className="font-medium">Drop files here, or choose them</p>
            <p className="text-sm text-muted">
              PDF, Word, PowerPoint, OpenDocument, text, or photos and scans of pages. Up to 100 MB each.
            </p>
          </div>
        )}
        <Button variant="secondary" onClick={() => input.current?.click()}>
          {label}
        </Button>
        <input
          ref={input}
          type="file"
          hidden
          multiple={multiple}
          accept={DOCUMENT_ACCEPT}
          onChange={(e) => {
            if (e.target.files?.length) start(e.target.files)
            e.target.value = ''
          }}
        />
      </div>
      {items.length > 0 && (
        <ul className="mt-3 space-y-2" aria-live="polite">
          {items.map((i) => (
            <li key={i.key} className="rounded-xl border border-line px-4 py-2.5 flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex justify-between gap-3 text-sm">
                  <span className="truncate font-medium">{i.name}</span>
                  <span className="text-muted shrink-0 tabular">
                    {i.state === 'uploading' && `${Math.round(i.progress * 100)}% of ${formatBytes(i.size)}`}
                    {i.state === 'saving' && 'Saving'}
                    {i.state === 'done' && 'Added. Reading starts shortly.'}
                    {i.state === 'error' && <span className="text-rose-signal">{i.error}</span>}
                  </span>
                </div>
                {(i.state === 'uploading' || i.state === 'saving') && (
                  <div className="mt-1.5 h-1 rounded-full bg-surface-2 overflow-hidden">
                    <div className="h-full bg-marigold-500 transition-[width]" style={{ width: `${Math.round(i.progress * 100)}%` }} />
                  </div>
                )}
              </div>
              {i.state === 'uploading' && (
                <button
                  type="button"
                  className="text-faint hover:text-ink"
                  aria-label={`Cancel ${i.name}`}
                  onClick={() => i.controller.abort()}
                >
                  <X className="size-4" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Snippet text with the matched words marked. No HTML from the server is ever rendered. */
export function Marked({ text, highlights, cutBefore, cutAfter }: { text: string; highlights: Highlight[]; cutBefore?: boolean; cutAfter?: boolean }) {
  return (
    <>
      {cutBefore && '… '}
      {highlightParts(text, highlights).map((p, i) =>
        p.mark ? (
          <mark key={i} className="bg-marigold-500/25 text-ink rounded-sm px-0.5">
            {p.text}
          </mark>
        ) : (
          <span key={i}>{p.text}</span>
        ),
      )}
      {cutAfter && ' …'}
    </>
  )
}

export function Warning({ children }: { children: string }) {
  return (
    <p className="flex items-start gap-2 text-sm text-marigold-700 dark:text-marigold-300">
      <AlertTriangle className="size-4 mt-0.5 shrink-0" aria-hidden />
      <span>{children}</span>
    </p>
  )
}
