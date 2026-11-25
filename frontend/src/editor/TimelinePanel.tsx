import { AudioLines, Eye, EyeOff, Film, Lock, Plus, Trash2, Type, Unlock, Volume2, VolumeX } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type PointerEvent as RPointerEvent, type ReactNode, type RefObject } from 'react'
import { cn } from '../lib/cn'
import type { MediaAsset } from '../lib/types'
import { canHold, moveClip, newTrack, snap, snapEdges, trimLeft, trimRight, updateClip } from '../remotion/timeline'
import type { Clip, MediaRef, TimelineDoc, Track, TrackKind } from '../remotion/types'
import type { History } from './history'
import { formatTime, rulerStep, usePlayheadTime, type Playhead } from './playhead'

export const HEADER_WIDTH = 168
const TRACK_HEIGHT: Record<TrackKind, number> = { video: 64, audio: 48, text: 40 }
const SNAP_PX = 8

export const MEDIA_DRAG_TYPE = 'application/x-leenax-media'

interface Props {
  history: History<TimelineDoc>
  media: Record<string, MediaRef>
  assets: Record<string, MediaAsset>
  pps: number
  playhead: Playhead
  onSeek: (t: number) => void
  selected: Set<string>
  onSelect: (ids: Set<string>) => void
  onDropMedia: (mediaId: string, trackId: string | undefined, time: number) => void
  snapping: boolean
}

export function TimelinePanel({ history, media, assets, pps, playhead, onSeek, selected, onSelect, onDropMedia, snapping }: Props) {
  const doc = history.present
  const scroller = useRef<HTMLDivElement>(null)
  const [guide, setGuide] = useState<number>()
  const end = useMemo(() => {
    let e = 0
    for (const t of doc.tracks) for (const c of t.clips) e = Math.max(e, c.start + c.duration)
    return e
  }, [doc])
  const width = HEADER_WIDTH + Math.max(end + 30, 60) * pps

  // ---- drag / trim -----------------------------------------------------------------

  function startDrag(e: RPointerEvent, clip: Clip, track: Track, mode: 'move' | 'left' | 'right') {
    if (track.locked || e.button !== 0) return
    e.stopPropagation()
    const multi = e.shiftKey || e.metaKey || e.ctrlKey
    if (multi) {
      const next = new Set(selected)
      if (next.has(clip.id)) next.delete(clip.id)
      else next.add(clip.id)
      onSelect(next)
      return
    }
    if (!selected.has(clip.id) || selected.size > 1) onSelect(new Set([clip.id]))
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    const doc0 = doc
    const x0 = e.clientX
    const edges = snapEdges(doc0, new Set([clip.id]), playhead.get())
    const threshold = SNAP_PX / pps
    const neighbours = track.clips.filter((c) => c.id !== clip.id)
    const prevEnd = Math.max(0, ...neighbours.filter((c) => c.start + c.duration <= clip.start + 1e-6).map((c) => c.start + c.duration))
    const nextStart = Math.min(Infinity, ...neighbours.filter((c) => c.start >= clip.start + clip.duration - 1e-6).map((c) => c.start))
    let moved = false

    const onMove = (ev: PointerEvent) => {
      const dx = (ev.clientX - x0) / pps
      if (!moved && Math.abs(ev.clientX - x0) < 3) return
      moved = true
      const snapTo = (t: number) => (snapping ? snap(t, edges, threshold) : { time: t })
      if (mode === 'move') {
        let start = clip.start + dx
        const a = snapTo(start)
        const b = snapTo(start + clip.duration)
        if (a.edge !== undefined && (b.edge === undefined || Math.abs(a.time - start) <= Math.abs(b.time - start - clip.duration))) {
          start = a.time
          setGuide(a.edge)
        } else if (b.edge !== undefined) {
          start = b.time - clip.duration
          setGuide(b.edge)
        } else setGuide(undefined)
        const lane = (document.elementsFromPoint(ev.clientX, ev.clientY).find((n) => (n as HTMLElement).dataset?.lane) as HTMLElement | undefined)?.dataset.lane
        const target = doc0.tracks.find((t) => t.id === lane)
        const trackId = target && canHold(target, clip) && !target.locked ? target.id : track.id
        history.preview(moveClip(doc0, clip.id, trackId, Math.max(0, start)))
      } else if (mode === 'left') {
        const s = snapTo(Math.max(prevEnd, clip.start + dx))
        setGuide(s.edge)
        history.preview(updateClip(doc0, clip.id, trimLeft(clip, Math.max(prevEnd, s.time), clip.type === 'media' ? media[clip.mediaId] : undefined)))
      } else {
        const s = snapTo(Math.min(nextStart, clip.start + clip.duration + dx))
        setGuide(s.edge)
        history.preview(updateClip(doc0, clip.id, trimRight(clip, Math.min(nextStart, s.time), clip.type === 'media' ? media[clip.mediaId] : undefined)))
      }
    }
    let ended = false
    const onUp = () => {
      if (ended) return
      ended = true
      el.removeEventListener('pointermove', onMove)
      el.removeEventListener('pointerup', onUp)
      el.removeEventListener('pointercancel', onUp)
      el.removeEventListener('lostpointercapture', onUp)
      setGuide(undefined)
      if (moved) history.commit()
    }
    el.addEventListener('pointermove', onMove)
    el.addEventListener('pointerup', onUp)
    el.addEventListener('pointercancel', onUp)
    el.addEventListener('lostpointercapture', onUp)
  }

  // ---- seeking -----------------------------------------------------------------------

  function timeAt(clientX: number) {
    const rect = scroller.current!.getBoundingClientRect()
    return Math.max(0, (clientX - rect.left + scroller.current!.scrollLeft - HEADER_WIDTH) / pps)
  }

  function scrub(e: RPointerEvent) {
    if (e.button !== 0) return
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    onSeek(timeAt(e.clientX))
    const move = (ev: PointerEvent) => onSeek(timeAt(ev.clientX))
    const up = () => {
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
    }
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
  }

  // ---- tracks ------------------------------------------------------------------------

  const patchTrack = (id: string, patch: Partial<Track>) => history.set((d) => ({ ...d, tracks: d.tracks.map((t) => (t.id === id ? { ...t, ...patch } : t)) }))
  const addTrack = (kind: TrackKind) =>
    history.set((d) => {
      const t = newTrack(kind, d.tracks.filter((x) => x.kind === kind).length + 1)
      // text above video, audio at the bottom
      const idx = kind === 'audio' ? d.tracks.length : kind === 'text' ? 0 : d.tracks.findIndex((x) => x.kind === 'video')
      const tracks = [...d.tracks]
      tracks.splice(idx < 0 ? d.tracks.length : idx, 0, t)
      return { ...d, tracks }
    })

  const ticks = useMemo(() => {
    const step = rulerStep(pps)
    const out: number[] = []
    for (let t = 0; t * pps < width; t += step) out.push(Math.round(t * 1000) / 1000)
    return { step, out }
  }, [pps, width])

  return (
    <section aria-label="Timeline" className="flex flex-col min-h-0 h-full">
      <div className="flex items-center gap-1 px-2 py-1.5 border-b border-line text-sm">
        <span className="text-muted mr-1">Add track:</span>
        <TrackButton icon={<Film className="size-4" />} label="Video" onClick={() => addTrack('video')} />
        <TrackButton icon={<AudioLines className="size-4" />} label="Audio" onClick={() => addTrack('audio')} />
        <TrackButton icon={<Type className="size-4" />} label="Text" onClick={() => addTrack('text')} />
      </div>
      <div ref={scroller} className="relative flex-1 min-h-0 overflow-auto overscroll-contain select-none bg-surface-2/40" data-testid="timeline-scroll">
        <div style={{ width }} className="relative">
          {/* ruler */}
          <div className="sticky top-0 z-20 flex h-7 bg-surface border-b border-line">
            <div style={{ width: HEADER_WIDTH }} className="sticky left-0 z-30 shrink-0 bg-surface border-r border-line" />
            <div className="relative flex-1 cursor-pointer" onPointerDown={scrub} aria-label="Timeline ruler. Click to move the playhead.">
              {ticks.out.map((t) => (
                <div key={t} className="absolute top-0 h-full border-l border-line" style={{ left: t * pps }}>
                  <span className="absolute left-1 top-1 text-[0.68rem] text-faint tabular whitespace-nowrap">{formatTime(t, ticks.step < 1)}</span>
                </div>
              ))}
            </div>
          </div>

          {/* tracks */}
          {doc.tracks.map((track) => (
            <div key={track.id} className="flex border-b border-line" style={{ height: TRACK_HEIGHT[track.kind] }}>
              <TrackHeader track={track} onPatch={(p) => patchTrack(track.id, p)} onDelete={() => history.set((d) => ({ ...d, tracks: d.tracks.filter((t) => t.id !== track.id) }))} />
              <div
                data-lane={track.id}
                className={cn('relative flex-1', track.locked && 'bg-[repeating-linear-gradient(135deg,transparent_0_8px,var(--line)_8px_9px)]')}
                onPointerDown={(e) => {
                  if (e.target === e.currentTarget) {
                    onSelect(new Set())
                    scrub(e)
                  }
                }}
                onDragOver={(e) => {
                  if (e.dataTransfer.types.includes(MEDIA_DRAG_TYPE)) {
                    e.preventDefault()
                    e.dataTransfer.dropEffect = 'copy'
                  }
                }}
                onDrop={(e) => {
                  const id = e.dataTransfer.getData(MEDIA_DRAG_TYPE)
                  if (!id) return
                  e.preventDefault()
                  onDropMedia(id, track.id, timeAt(e.clientX))
                }}
              >
                {track.clips.map((clip) => (
                  <ClipBlock
                    key={clip.id}
                    clip={clip}
                    track={track}
                    pps={pps}
                    selected={selected.has(clip.id)}
                    asset={clip.type === 'media' ? assets[clip.mediaId] : undefined}
                    missing={clip.type === 'media' && !media[clip.mediaId]}
                    onPointerDown={(e, mode) => startDrag(e, clip, track, mode)}
                  />
                ))}
              </div>
            </div>
          ))}
          <div
            className="h-16 flex items-center text-sm text-faint"
            style={{ paddingLeft: HEADER_WIDTH + 12 }}
            onDragOver={(e) => e.dataTransfer.types.includes(MEDIA_DRAG_TYPE) && e.preventDefault()}
            onDrop={(e) => {
              const id = e.dataTransfer.getData(MEDIA_DRAG_TYPE)
              if (id) onDropMedia(id, undefined, timeAt(e.clientX))
            }}
          >
            {doc.tracks.every((t) => t.clips.length === 0) ? 'Drag files from the left onto a track, or press “Add” on a file.' : ''}
          </div>

          {guide !== undefined && <div className="absolute top-0 bottom-0 w-px bg-peacock-500 z-30 pointer-events-none" style={{ left: HEADER_WIDTH + guide * pps }} />}
          <PlayheadLine playhead={playhead} pps={pps} scroller={scroller} />
        </div>
      </div>
    </section>
  )
}

function TrackButton({ icon, label, onClick }: { icon: ReactNode; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-muted hover:text-ink hover:bg-surface-2">
      <Plus className="size-3" aria-hidden />
      {icon}
      {label}
    </button>
  )
}

function TrackHeader({ track, onPatch, onDelete }: { track: Track; onPatch: (p: Partial<Track>) => void; onDelete: () => void }) {
  const [editing, setEditing] = useState(false)
  const Icon = track.kind === 'video' ? Film : track.kind === 'audio' ? AudioLines : Type
  return (
    <div style={{ width: HEADER_WIDTH }} className="sticky left-0 z-10 shrink-0 bg-surface border-r border-line flex items-center gap-1 px-2 text-sm">
      <Icon className="size-3.5 text-faint shrink-0" aria-hidden />
      {editing ? (
        <input
          autoFocus
          defaultValue={track.name}
          aria-label="Track name"
          className="min-w-0 flex-1 rounded border border-line bg-surface px-1"
          onBlur={(e) => {
            setEditing(false)
            if (e.target.value.trim()) onPatch({ name: e.target.value.trim().slice(0, 40) })
          }}
          onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
        />
      ) : (
        <button type="button" className="min-w-0 flex-1 truncate text-left" onDoubleClick={() => setEditing(true)} title="Double-click to rename">
          {track.name}
        </button>
      )}
      {track.kind !== 'text' && (
        <IconBtn label={track.muted ? `Unmute ${track.name}` : `Mute ${track.name}`} active={track.muted} onClick={() => onPatch({ muted: !track.muted })}>
          {track.muted ? <VolumeX className="size-3.5" /> : <Volume2 className="size-3.5" />}
        </IconBtn>
      )}
      {track.kind !== 'audio' && (
        <IconBtn label={track.hidden ? `Show ${track.name}` : `Hide ${track.name}`} active={track.hidden} onClick={() => onPatch({ hidden: !track.hidden })}>
          {track.hidden ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
        </IconBtn>
      )}
      <IconBtn label={track.locked ? `Unlock ${track.name}` : `Lock ${track.name}`} active={track.locked} onClick={() => onPatch({ locked: !track.locked })}>
        {track.locked ? <Lock className="size-3.5" /> : <Unlock className="size-3.5" />}
      </IconBtn>
      {track.clips.length === 0 && (
        <IconBtn label={`Remove ${track.name}`} onClick={onDelete}>
          <Trash2 className="size-3.5" />
        </IconBtn>
      )}
    </div>
  )
}

function IconBtn({ label, active, onClick, children }: { label: string; active?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} aria-pressed={active} onClick={onClick} className={cn('p-1 rounded hover:bg-surface-2', active ? 'text-marigold-600 dark:text-marigold-300' : 'text-faint')}>
      {children}
    </button>
  )
}

const CLIP_COLOURS = {
  VIDEO: 'bg-peacock-500/25 border-peacock-500/70',
  IMAGE: 'bg-[#7b61ff]/20 border-[#7b61ff]/70',
  AUDIO: 'bg-[#2fa36b]/20 border-[#2fa36b]/70',
  TEXT: 'bg-marigold-500/25 border-marigold-500/80',
}

function ClipBlock({
  clip,
  track,
  pps,
  selected,
  asset,
  missing,
  onPointerDown,
}: {
  clip: Clip
  track: Track
  pps: number
  selected: boolean
  asset?: MediaAsset
  missing: boolean
  onPointerDown: (e: RPointerEvent, mode: 'move' | 'left' | 'right') => void
}) {
  const kind = clip.type === 'text' ? 'TEXT' : clip.type === 'scene' ? 'IMAGE' : clip.kind
  const label = clip.type === 'text' ? clip.text.split('\n')[0] || 'Text' : clip.type === 'scene' ? (clip.scene.title ?? 'Scene') : (asset?.filename ?? 'Missing file')
  const w = Math.max(4, clip.duration * pps)
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={`${label}, ${formatTime(clip.start)} to ${formatTime(clip.start + clip.duration)}`}
      aria-pressed={selected}
      data-clip={clip.id}
      onPointerDown={(e) => onPointerDown(e, 'move')}
      className={cn(
        'absolute top-1 bottom-1 rounded-md border overflow-hidden text-xs flex items-center',
        CLIP_COLOURS[kind],
        selected && 'ring-2 ring-marigold-500 z-10',
        track.locked ? 'cursor-not-allowed opacity-70' : 'cursor-grab active:cursor-grabbing',
        missing && 'bg-rose-signal/20 border-rose-signal',
        track.hidden && 'opacity-40',
      )}
      style={{ left: clip.start * pps, width: w }}
    >
      {clip.type === 'media' && clip.kind !== 'AUDIO' && asset?.posterUrl && w > 40 && (
        <div className="absolute inset-0 opacity-45 bg-repeat-x bg-contain pointer-events-none" style={{ backgroundImage: `url(${asset.posterUrl})` }} aria-hidden />
      )}
      {clip.type === 'media' && clip.kind === 'AUDIO' && (
        <div className="absolute inset-x-0 bottom-0 h-1/2 opacity-50 pointer-events-none bg-[repeating-linear-gradient(90deg,currentColor_0_2px,transparent_2px_5px)] text-[#2fa36b] [mask-image:linear-gradient(transparent,black)]" aria-hidden />
      )}
      <span className="relative truncate px-2 font-medium text-ink drop-shadow-[0_1px_0_rgba(255,255,255,0.4)] dark:drop-shadow-none pointer-events-none">
        {label}
        {clip.type === 'media' && clip.speed !== 1 ? ` · ${clip.speed}×` : ''}
      </span>
      {!track.locked && (
        <>
          <div onPointerDown={(e) => onPointerDown(e, 'left')} className="absolute left-0 top-0 bottom-0 w-2 cursor-ew-resize hover:bg-white/40" aria-hidden />
          <div onPointerDown={(e) => onPointerDown(e, 'right')} className="absolute right-0 top-0 bottom-0 w-2 cursor-ew-resize hover:bg-white/40" aria-hidden />
        </>
      )}
    </div>
  )
}

function PlayheadLine({ playhead, pps, scroller }: { playhead: Playhead; pps: number; scroller: RefObject<HTMLDivElement | null> }) {
  const t = usePlayheadTime(playhead)
  const x = HEADER_WIDTH + t * pps
  // keep the playhead in view while playing
  useEffect(() => {
    const el = scroller.current
    if (!el || !playhead.isPlaying()) return
    if (x > el.scrollLeft + el.clientWidth - 40 || x < el.scrollLeft + HEADER_WIDTH) el.scrollLeft = Math.max(0, x - HEADER_WIDTH - 40)
  }, [x, scroller, playhead])
  return (
    <div className="absolute top-0 bottom-0 z-20 pointer-events-none" style={{ left: x }}>
      <div className="w-0.5 h-full bg-rose-signal" />
      <div className="absolute -top-0 -left-[5px] size-3 rounded-sm bg-rose-signal rotate-45" />
    </div>
  )
}

