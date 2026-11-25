import { useQueryClient } from '@tanstack/react-query'
import { Download, Magnet, Redo2, Scissors, Undo2, ZoomIn, ZoomOut } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button, ErrorNotice, Input, Spinner } from '../components/ui'
import { useAutosave, type SaveState } from '../editor/autosave'
import { ExportDialog, RendersList } from '../editor/ExportPanel'
import { useHistory } from '../editor/history'
import { Inspector } from '../editor/Inspector'
import { MediaBin } from '../editor/MediaBin'
import { createPlayhead } from '../editor/playhead'
import { Preview, type PreviewHandle } from '../editor/Preview'
import { TimelinePanel } from '../editor/TimelinePanel'
import { api } from '../lib/api'
import { cn } from '../lib/cn'
import { editorKeys, useTimeline, useUpdateTimeline } from '../lib/editorQueries'
import type { MediaAsset, SubtitleTrack, TimelineResponse } from '../lib/types'
import {
  addClip,
  captionsTrack,
  duplicateClip,
  findClip,
  newMediaClip,
  newTextClip,
  removeClips,
  splitClip,
  TEXT_PRESETS,
  timelineDuration,
  updateClip,
} from '../remotion/timeline'
import type { Clip, MediaRef, TextClip, TimelineDoc } from '../remotion/types'
import { toast } from '../stores/ui'

export function EditorPage() {
  const { id } = useParams()
  const timeline = useTimeline(id)
  if (timeline.isPending) return <Spinner label="Opening the editor" />
  if (timeline.error) return <ErrorNotice error={timeline.error} onRetry={() => timeline.refetch()} />
  return <Editor key={timeline.data.id} initial={timeline.data} />
}

const toRef = (m: MediaAsset): MediaRef => ({
  id: m.id,
  kind: m.kind,
  url: m.url,
  durationMs: m.durationMs,
  width: m.width,
  height: m.height,
  filename: m.filename,
})

const SAVE_LABEL: Record<SaveState, string> = {
  saved: 'All changes saved',
  pending: 'Unsaved changes',
  saving: 'Saving…',
  error: 'Not saved',
  conflict: 'Changed elsewhere',
}

function Editor({ initial }: { initial: TimelineResponse }) {
  const qc = useQueryClient()
  const history = useHistory<TimelineDoc>(initial.document)
  const doc = history.present
  const [assets, setAssets] = useState<Record<string, MediaAsset>>(initial.media)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [pps, setPps] = useState(() => defaultZoom(timelineDuration(initial.document)))
  const [snapping, setSnapping] = useState(true)
  const [exporting, setExporting] = useState(false)
  const [showRenders, setShowRenders] = useState(false)
  const [name, setName] = useState(initial.name)
  const playhead = useMemo(() => createPlayhead(), [])
  const preview = useRef<PreviewHandle>(null)
  const autosave = useAutosave(initial.id, doc, initial.revision, false)
  const rename = useUpdateTimeline()
  const live = useTimeline(initial.id)

  // Fresh signed links every 20 minutes (the query refetches); the document stays ours.
  useEffect(() => {
    if (live.data?.media) setAssets((a) => ({ ...a, ...live.data.media }))
  }, [live.data])

  const media = useMemo(() => Object.fromEntries(Object.values(assets).map((m) => [m.id, toRef(m)])), [assets])
  const selectedClip = selected.size === 1 ? findClip(doc, [...selected][0]) : undefined

  const seek = useCallback(
    (t: number) => {
      const max = timelineDuration(doc)
      const s = Math.max(0, Math.min(max, t))
      playhead.set(s)
      preview.current?.seek(s)
    },
    [doc, playhead],
  )

  // ---- edits -----------------------------------------------------------------------

  const addMedia = useCallback(
    (m: MediaAsset, trackId?: string, time?: number) => {
      setAssets((a) => ({ ...a, [m.id]: m }))
      const clip = newMediaClip(toRef(m), time ?? playhead.get())
      history.set((d) => addClip(d, clip, trackId))
      setSelected(new Set([clip.id]))
    },
    [history, playhead],
  )

  const dropMedia = useCallback(
    async (mediaId: string, trackId: string | undefined, time: number) => {
      let m = assets[mediaId]
      if (!m) {
        try {
          m = await api.get<MediaAsset>(`/media/${mediaId}`)
        } catch (e) {
          toast.error(e instanceof Error ? e.message : 'Could not add the file')
          return
        }
      }
      addMedia(m, trackId, time)
    },
    [assets, addMedia],
  )

  const addText = (presetId: string) => {
    const clip = newTextClip(playhead.get(), TEXT_PRESETS.find((p) => p.id === presetId))
    history.set((d) => addClip(d, clip))
    setSelected(new Set([clip.id]))
  }

  const addCaptions = (track: SubtitleTrack) => {
    // Captions start where the file's clip starts on the timeline.
    let offset = 0
    for (const t of doc.tracks)
      for (const c of t.clips) if (c.type === 'media' && c.mediaId === track.mediaId) offset = c.start - c.trimStart / c.speed
    const cues = track.cues.map((c) => ({ ...c, start: c.start + offset, end: c.end + offset })).filter((c) => c.end > 0)
    const captions = captionsTrack(cues, `${track.label}`)
    history.set((d) => ({ ...d, tracks: [captions, ...d.tracks] }))
    toast.success(`${captions.clips.length} captions added`)
  }

  const split = useCallback(() => {
    const t = playhead.get()
    const ids = selected.size
      ? [...selected]
      : doc.tracks.filter((tr) => !tr.locked).flatMap((tr) => tr.clips.filter((c) => c.start < t && c.start + c.duration > t).map((c) => c.id))
    if (ids.length === 0) return
    history.set((d) => ids.reduce((acc, id) => splitClip(acc, id, t), d))
  }, [doc, history, playhead, selected])

  const remove = useCallback(
    (ripple: boolean) => {
      if (selected.size === 0) return
      const locked = new Set(doc.tracks.filter((t) => t.locked).flatMap((t) => t.clips.map((c) => c.id)))
      const ids = new Set([...selected].filter((id) => !locked.has(id)))
      history.set((d) => removeClips(d, ids, ripple))
      setSelected(new Set())
    },
    [doc, history, selected],
  )

  const duplicate = useCallback(() => {
    if (selected.size !== 1) return
    const r = duplicateClip(doc, [...selected][0])
    history.set(r.doc)
    if (r.newId) setSelected(new Set([r.newId]))
  }, [doc, history, selected])

  const changeClip = (patch: Partial<Clip>, liveChange?: boolean) => {
    if (!selectedClip) return
    const next = (d: TimelineDoc) => updateClip(d, selectedClip.clip.id, patch)
    if (liveChange) history.preview(next)
    else history.set(next)
  }

  const styleAllCaptions = (style: TextClip['style'], y: number) =>
    history.set((d) => ({
      ...d,
      tracks: d.tracks.map((t) => ({ ...t, clips: t.clips.map((c) => (c.type === 'text' && c.caption ? { ...c, style, y } : c)) })),
    }))

  // ---- keyboard ----------------------------------------------------------------------

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = (e.target as HTMLElement)?.closest('input, textarea, select, [contenteditable]')
      const mod = e.ctrlKey || e.metaKey
      const k = e.key.toLowerCase()
      if (mod && k === 's') {
        e.preventDefault()
        void autosave.flush()
        return
      }
      if (typing) return
      if (mod && k === 'z') {
        e.preventDefault()
        if (e.shiftKey) history.redo()
        else history.undo()
      } else if (mod && k === 'y') {
        e.preventDefault()
        history.redo()
      } else if (mod && k === 'd') {
        e.preventDefault()
        duplicate()
      } else if (e.key === ' ') {
        e.preventDefault()
        preview.current?.toggle()
      } else if (k === 's' && !mod) {
        split()
      } else if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault()
        remove(e.shiftKey)
      } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault()
        const step = e.shiftKey ? 1 : 1 / doc.fps
        seek(playhead.get() + (e.key === 'ArrowLeft' ? -step : step))
      } else if (e.key === 'Home') seek(0)
      else if (e.key === 'End') seek(timelineDuration(doc))
      else if (e.key === '+' || e.key === '=') setPps((p) => Math.min(400, p * 1.25))
      else if (e.key === '-') setPps((p) => Math.max(4, p / 1.25))
      else if (e.key === 'Escape') setSelected(new Set())
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [autosave, history, duplicate, split, remove, seek, playhead, doc])

  const videoMedia = useMemo(() => {
    const ids = new Set<string>()
    for (const t of doc.tracks) for (const c of t.clips) if (c.type === 'media' && c.kind !== 'IMAGE') ids.add(c.mediaId)
    return [...ids].map((i) => assets[i]).filter(Boolean)
  }, [doc, assets])

  async function reloadFromServer() {
    const fresh = await qc.fetchQuery({ queryKey: editorKeys.timeline(initial.id), queryFn: () => api.get<TimelineResponse>(`/editor/timelines/${initial.id}`) })
    qc.setQueryData(editorKeys.timeline(initial.id), fresh)
    window.location.reload()
  }

  async function keepMine() {
    const fresh = await api.get<TimelineResponse>(`/editor/timelines/${initial.id}`)
    if (await autosave.overwrite(fresh.revision)) toast.success('Your version was saved')
  }

  return (
    <div className="flex flex-col gap-3 h-[calc(100dvh-5.5rem)] min-h-[640px] -mx-2 sm:mx-0">
      {/* ---- top bar ---- */}
      <div className="flex flex-wrap items-center gap-2">
        <Link to="/edit/timeline" className="text-sm text-muted hover:text-ink">
          ← Timelines
        </Link>
        <div className="w-60">
          <Input
            aria-label="Timeline name"
            value={name}
            maxLength={160}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => name.trim() && name !== initial.name && rename.mutate({ id: initial.id, name: name.trim() })}
            className="h-9"
          />
        </div>
        <span className="text-xs text-faint tabular">
          {doc.width}×{doc.height} · {doc.fps} fps
        </span>
        <span
          role="status"
          className={cn('text-xs', autosave.state === 'error' || autosave.state === 'conflict' ? 'text-rose-signal' : 'text-faint')}
          title={autosave.error}
        >
          {SAVE_LABEL[autosave.state]}
          {autosave.state === 'error' && (
            <button type="button" className="ml-1 underline" onClick={() => void autosave.flush()}>
              Retry
            </button>
          )}
        </span>
        <div className="ml-auto flex items-center gap-1 flex-wrap">
          <Button size="sm" variant="ghost" aria-label="Undo" title="Undo (Ctrl+Z)" disabled={!history.canUndo} onClick={history.undo} icon={<Undo2 className="size-4" />} />
          <Button size="sm" variant="ghost" aria-label="Redo" title="Redo (Ctrl+Shift+Z)" disabled={!history.canRedo} onClick={history.redo} icon={<Redo2 className="size-4" />} />
          <Button size="sm" variant="ghost" title="Split at playhead (S)" onClick={split} icon={<Scissors className="size-4" />}>
            Split
          </Button>
          <Button
            size="sm"
            variant="ghost"
            aria-pressed={snapping}
            title="Snap to clip edges and the playhead"
            onClick={() => setSnapping((s) => !s)}
            icon={<Magnet className={cn('size-4', snapping && 'text-marigold-500')} />}
          >
            Snap
          </Button>
          <Button size="sm" variant="ghost" aria-label="Zoom out" onClick={() => setPps((p) => Math.max(4, p / 1.25))} icon={<ZoomOut className="size-4" />} />
          <input
            type="range"
            aria-label="Timeline zoom"
            min={Math.log(4)}
            max={Math.log(400)}
            step={0.01}
            value={Math.log(pps)}
            onChange={(e) => setPps(Math.exp(Number(e.target.value)))}
            className="w-24 accent-marigold-500"
          />
          <Button size="sm" variant="ghost" aria-label="Zoom in" onClick={() => setPps((p) => Math.min(400, p * 1.25))} icon={<ZoomIn className="size-4" />} />
          <div className="relative">
            <Button size="sm" onClick={() => setShowRenders((s) => !s)} aria-expanded={showRenders}>
              Exports
            </Button>
            {showRenders && (
              <div className="absolute right-0 top-10 z-40 w-80 max-h-96 overflow-y-auto rounded-xl border border-line bg-surface shadow-xl">
                <RendersList timelineId={initial.id} />
              </div>
            )}
          </div>
          <Button size="sm" variant="primary" icon={<Download className="size-4" />} onClick={() => setExporting(true)}>
            Export
          </Button>
        </div>
      </div>

      {autosave.state === 'conflict' && (
        <div role="alert" className="rounded-xl border border-rose-signal/50 bg-rose-signal/8 px-4 py-2 flex flex-wrap items-center gap-3 text-sm">
          <span className="flex-1">This timeline was saved from another tab or window. Your latest changes here are not saved.</span>
          <Button size="sm" onClick={reloadFromServer}>
            Load the other version
          </Button>
          <Button size="sm" variant="primary" onClick={keepMine}>
            Keep mine
          </Button>
        </div>
      )}

      {/* ---- main area ---- */}
      <div className="grid grid-cols-[16rem_minmax(0,1fr)_19rem] gap-3 min-h-0 flex-[3]">
        <aside className="rounded-xl border border-line bg-surface min-h-0 overflow-hidden" aria-label="Media and text">
          <MediaBin onAdd={(m) => addMedia(m)} onAddText={addText} onAddCaptions={addCaptions} videoMedia={videoMedia} />
        </aside>
        <div className="min-h-0 min-w-0">
          <Preview ref={preview} doc={doc} media={media} playhead={playhead} />
        </div>
        <aside className="rounded-xl border border-line bg-surface min-h-0 overflow-y-auto" aria-label="Inspector">
          <Inspector
            doc={doc}
            clip={selectedClip?.clip}
            media={selectedClip?.clip.type === 'media' ? media[selectedClip.clip.mediaId] : undefined}
            asset={selectedClip?.clip.type === 'media' ? assets[selectedClip.clip.mediaId] : undefined}
            selectedCount={selected.size}
            onChange={changeClip}
            onCommit={history.commit}
            onDocChange={(patch) => history.set((d) => ({ ...d, ...patch }))}
            onSplit={split}
            onDuplicate={duplicate}
            onDelete={() => remove(false)}
            onStyleAllCaptions={styleAllCaptions}
          />
        </aside>
      </div>

      <div className="rounded-xl border border-line bg-surface min-h-0 flex-[2] overflow-hidden">
        <TimelinePanel
          history={history}
          media={media}
          assets={assets}
          pps={pps}
          playhead={playhead}
          onSeek={seek}
          selected={selected}
          onSelect={setSelected}
          onDropMedia={dropMedia}
          snapping={snapping}
        />
      </div>

      {exporting && <ExportDialog timelineId={initial.id} doc={doc} onClose={() => setExporting(false)} beforeStart={autosave.flush} />}
    </div>
  )
}

/** Zoom so the whole timeline (or at least 30 s) fits roughly in view. */
function defaultZoom(duration: number) {
  const visible = Math.max(30, duration * 1.1)
  return Math.min(200, Math.max(4, 1100 / visible))
}
