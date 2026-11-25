import { Copy, Scissors, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button, Select, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import type { MediaAsset } from '../lib/types'
import { EDITOR_FONTS } from '../remotion/fonts'
import { maxDuration, setSpeed } from '../remotion/timeline'
import type { Clip, MediaClip, MediaRef, Motion, TextAnimation, TextClip, TimelineDoc, TransitionType } from '../remotion/types'
import { formatTime } from './playhead'

const TRANSITIONS: { id: TransitionType; label: string }[] = [
  { id: 'none', label: 'None' },
  { id: 'fade', label: 'Fade' },
  { id: 'slide-left', label: 'Slide in from right' },
  { id: 'slide-right', label: 'Slide in from left' },
  { id: 'slide-up', label: 'Slide up' },
  { id: 'zoom', label: 'Zoom' },
  { id: 'wipe', label: 'Wipe' },
  { id: 'blur', label: 'Blur' },
]
const MOTIONS: { id: Motion; label: string }[] = [
  { id: 'none', label: 'Still' },
  { id: 'zoom-in', label: 'Slow zoom in' },
  { id: 'zoom-out', label: 'Slow zoom out' },
  { id: 'pan-left', label: 'Pan left' },
  { id: 'pan-right', label: 'Pan right' },
]
const TEXT_ANIMS: { id: TextAnimation; label: string }[] = [
  { id: 'none', label: 'None' },
  { id: 'fade', label: 'Fade' },
  { id: 'slide-up', label: 'Rise' },
  { id: 'pop', label: 'Pop' },
  { id: 'typewriter', label: 'Typewriter (in only)' },
]

interface Props {
  doc: TimelineDoc
  clip?: Clip
  media?: MediaRef
  asset?: MediaAsset
  selectedCount: number
  /** `live` = while dragging a slider (one undo step for the whole drag). */
  onChange: (patch: Partial<Clip>, live?: boolean) => void
  onCommit: () => void
  onDocChange: (patch: Partial<TimelineDoc>) => void
  onSplit: () => void
  onDuplicate: () => void
  onDelete: () => void
  onStyleAllCaptions: (style: TextClip['style'], y: number) => void
}

export function Inspector(p: Props) {
  if (p.selectedCount > 1) {
    return (
      <Panel title={`${p.selectedCount} clips selected`}>
        <p className="text-sm text-muted">Delete removes them all; Shift+Delete also closes the gaps.</p>
        <Button variant="danger" size="sm" icon={<Trash2 className="size-4" />} onClick={p.onDelete} className="self-start">
          Delete clips
        </Button>
      </Panel>
    )
  }
  if (!p.clip) return <DocSettings doc={p.doc} onChange={p.onDocChange} />
  const c = p.clip
  return (
    <Panel title={c.type === 'text' ? 'Text' : c.type === 'scene' ? 'Lesson or quiz scene' : (p.asset?.filename ?? 'Clip')}>
      <div className="flex gap-1 -mt-1">
        <Button size="sm" variant="ghost" icon={<Scissors className="size-4" />} onClick={p.onSplit} title="Split at playhead (S)">
          Split
        </Button>
        <Button size="sm" variant="ghost" icon={<Copy className="size-4" />} onClick={p.onDuplicate} title="Duplicate (Ctrl+D)">
          Duplicate
        </Button>
        <Button size="sm" variant="ghost" icon={<Trash2 className="size-4" />} onClick={p.onDelete} title="Delete (Del)" aria-label="Delete clip" />
      </div>
      <p className="text-xs text-faint tabular -mt-2">
        {formatTime(c.start)} – {formatTime(c.start + c.duration)} · {c.duration.toFixed(2)} s
      </p>
      {c.type === 'media' ? (
        <MediaFields {...p} clip={c} />
      ) : c.type === 'text' ? (
        <TextFields {...p} clip={c} />
      ) : (
        <p className="text-sm text-muted">{c.scene.title ?? c.scene.kind}. Scenes are made from an approved presentation or MCQ set; change them on the video’s page.</p>
      )}
    </Panel>
  )
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-3 p-4">
      <h2 className="font-semibold truncate">{title}</h2>
      {children}
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="border-t border-line pt-3 flex flex-col gap-2.5">
      <legend className="text-xs font-semibold text-muted pr-2">{title}</legend>
      {children}
    </fieldset>
  )
}

function Range({
  label,
  value,
  min,
  max,
  step,
  format,
  onChange,
  onCommit,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  format?: (v: number) => string
  onChange: (v: number, live: boolean) => void
  onCommit: () => void
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="flex justify-between">
        {label}
        <span className="tabular text-muted">{format ? format(value) : value}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value), true)}
        onPointerUp={onCommit}
        onKeyUp={onCommit}
        className="accent-marigold-500"
      />
    </label>
  )
}

function Choice<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { id: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <label className="flex items-center justify-between gap-2 text-sm">
      {label}
      <Select value={value} onChange={(e) => onChange(e.target.value as T)} className="h-8 w-40 text-sm">
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.label}
          </option>
        ))}
      </Select>
    </label>
  )
}

function Colour({ label, value, onChange, allowNone }: { label: string; value: string; onChange: (v: string) => void; allowNone?: boolean }) {
  const none = !value
  const hex = /^#[0-9a-f]{6}$/i.test(value) ? value : '#000000'
  return (
    <div className="flex items-center justify-between gap-2 text-sm">
      {label}
      <span className="flex items-center gap-2">
        {allowNone && (
          <label className="flex items-center gap-1 text-xs text-muted">
            <input type="checkbox" checked={!none} onChange={(e) => onChange(e.target.checked ? '#f5a524' : '')} className="accent-marigold-500" />
            On
          </label>
        )}
        <input type="color" aria-label={label} value={hex} disabled={none && allowNone} onChange={(e) => onChange(e.target.value)} className="h-7 w-10 rounded border border-line disabled:opacity-40" />
      </span>
    </div>
  )
}

const pct = (v: number) => `${Math.round(v * 100)}%`
const secs = (v: number) => (v === 0 ? 'Off' : `${v.toFixed(1)} s`)

function MediaFields({ clip, media, onChange, onCommit }: Props & { clip: MediaClip }) {
  const picture = clip.kind !== 'AUDIO'
  const sound = clip.kind !== 'IMAGE'
  const set = (patch: Partial<MediaClip>, live = false) => onChange(patch, live)
  return (
    <>
      {sound && (
        <Section title="Sound">
          <Range label="Volume" value={clip.volume} min={0} max={2} step={0.05} format={pct} onChange={(v, l) => set({ volume: v }, l)} onCommit={onCommit} />
          <Range label="Fade in" value={clip.fadeIn} min={0} max={Math.min(10, clip.duration / 2)} step={0.1} format={secs} onChange={(v, l) => set({ fadeIn: v }, l)} onCommit={onCommit} />
          <Range label="Fade out" value={clip.fadeOut} min={0} max={Math.min(10, clip.duration / 2)} step={0.1} format={secs} onChange={(v, l) => set({ fadeOut: v }, l)} onCommit={onCommit} />
        </Section>
      )}
      {clip.kind !== 'IMAGE' && (
        <Section title="Speed">
          <div className="flex flex-wrap gap-1">
            {[0.5, 0.75, 1, 1.25, 1.5, 2].map((s) => (
              <button
                key={s}
                type="button"
                aria-pressed={clip.speed === s}
                onClick={() => {
                  const next = setSpeed(clip, s)
                  onChange({ speed: next.speed, duration: Math.min(next.duration, maxDuration({ ...next }, media)) })
                }}
                className={cn('rounded-md border px-2 py-0.5 text-sm', clip.speed === s ? 'border-marigold-500 bg-marigold-500/10' : 'border-line')}
              >
                {s}×
              </button>
            ))}
          </div>
          <p className="text-xs text-faint">Changing speed keeps the same footage, so the clip gets shorter or longer. Pitch stays natural.</p>
        </Section>
      )}
      {picture && (
        <>
          <Section title="Picture">
            <Choice label="Fit" value={clip.fit} options={[{ id: 'cover', label: 'Fill frame' }, { id: 'contain', label: 'Show all' }]} onChange={(v) => set({ fit: v })} />
            <Range label="Size" value={clip.scale} min={0.1} max={3} step={0.01} format={pct} onChange={(v, l) => set({ scale: v }, l)} onCommit={onCommit} />
            <Range label="Left / right" value={clip.x} min={-1} max={1} step={0.01} format={(v) => v.toFixed(2)} onChange={(v, l) => set({ x: v }, l)} onCommit={onCommit} />
            <Range label="Up / down" value={clip.y} min={-1} max={1} step={0.01} format={(v) => v.toFixed(2)} onChange={(v, l) => set({ y: v }, l)} onCommit={onCommit} />
            <Range label="Rotation" value={clip.rotation} min={-180} max={180} step={1} format={(v) => `${v}°`} onChange={(v, l) => set({ rotation: v }, l)} onCommit={onCommit} />
            <Range label="Opacity" value={clip.opacity} min={0} max={1} step={0.01} format={pct} onChange={(v, l) => set({ opacity: v }, l)} onCommit={onCommit} />
            <Choice label="Movement" value={clip.motion} options={MOTIONS} onChange={(v) => set({ motion: v })} />
          </Section>
          <Section title="Colour">
            <Range label="Brightness" value={clip.filters.brightness} min={0.3} max={2} step={0.01} format={pct} onChange={(v, l) => set({ filters: { ...clip.filters, brightness: v } }, l)} onCommit={onCommit} />
            <Range label="Contrast" value={clip.filters.contrast} min={0.3} max={2} step={0.01} format={pct} onChange={(v, l) => set({ filters: { ...clip.filters, contrast: v } }, l)} onCommit={onCommit} />
            <Range label="Saturation" value={clip.filters.saturation} min={0} max={2} step={0.01} format={pct} onChange={(v, l) => set({ filters: { ...clip.filters, saturation: v } }, l)} onCommit={onCommit} />
            <Range label="Blur" value={clip.filters.blur} min={0} max={30} step={0.5} format={(v) => (v === 0 ? 'Off' : `${v}px`)} onChange={(v, l) => set({ filters: { ...clip.filters, blur: v } }, l)} onCommit={onCommit} />
            <Button size="sm" variant="ghost" className="self-start" onClick={() => set({ filters: { brightness: 1, contrast: 1, saturation: 1, blur: 0 } })}>
              Reset colour
            </Button>
          </Section>
          <Section title="Transitions">
            <Choice label="In" value={clip.transitionIn.type} options={TRANSITIONS} onChange={(v) => set({ transitionIn: { ...clip.transitionIn, type: v } })} />
            {clip.transitionIn.type !== 'none' && (
              <Range label="In length" value={clip.transitionIn.duration} min={0.1} max={Math.min(3, clip.duration)} step={0.1} format={secs} onChange={(v, l) => set({ transitionIn: { ...clip.transitionIn, duration: v } }, l)} onCommit={onCommit} />
            )}
            <Choice label="Out" value={clip.transitionOut.type} options={TRANSITIONS} onChange={(v) => set({ transitionOut: { ...clip.transitionOut, type: v } })} />
            {clip.transitionOut.type !== 'none' && (
              <Range label="Out length" value={clip.transitionOut.duration} min={0.1} max={Math.min(3, clip.duration)} step={0.1} format={secs} onChange={(v, l) => set({ transitionOut: { ...clip.transitionOut, duration: v } }, l)} onCommit={onCommit} />
            )}
            <p className="text-xs text-faint">For a cross-dissolve, overlap two clips on different video tracks and fade the top one in.</p>
          </Section>
        </>
      )}
    </>
  )
}

function TextFields({ clip, onChange, onCommit, onStyleAllCaptions }: Props & { clip: TextClip }) {
  const s = clip.style
  const style = (patch: Partial<TextClip['style']>, live = false) => onChange({ style: { ...s, ...patch } } as Partial<Clip>, live)
  const set = (patch: Partial<TextClip>, live = false) => onChange(patch as Partial<Clip>, live)
  return (
    <>
      <Textarea aria-label="Text" rows={3} value={clip.text} onChange={(e) => set({ text: e.target.value }, true)} onBlur={onCommit} className="min-h-0 text-base" />
      <Section title="Style">
        <Choice label="Font" value={s.fontFamily} options={EDITOR_FONTS.map((f) => ({ id: f.family, label: f.label }))} onChange={(v) => style({ fontFamily: v })} />
        <Choice
          label="Weight"
          value={String(s.fontWeight)}
          options={[{ id: '400', label: 'Regular' }, { id: '700', label: 'Bold' }, { id: '800', label: 'Extra bold' }]}
          onChange={(v) => style({ fontWeight: Number(v) as 400 | 700 | 800 })}
        />
        <Range label="Size" value={s.fontSize} min={16} max={300} step={1} format={(v) => `${v}px`} onChange={(v, l) => style({ fontSize: v }, l)} onCommit={onCommit} />
        <Colour label="Colour" value={s.color} onChange={(v) => style({ color: v })} />
        <Colour label="Outline" value={s.stroke} onChange={(v) => style({ stroke: v })} />
        <Range label="Outline width" value={s.strokeWidth} min={0} max={20} step={0.5} format={(v) => (v === 0 ? 'Off' : `${v}px`)} onChange={(v, l) => style({ strokeWidth: v }, l)} onCommit={onCommit} />
        <Colour label="Box behind" value={s.background.startsWith('#') || !s.background ? s.background : '#000000'} allowNone onChange={(v) => style({ background: v })} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={s.shadow} onChange={(e) => style({ shadow: e.target.checked })} className="accent-marigold-500" />
          Shadow
        </label>
        <Choice label="Align" value={s.align} options={[{ id: 'left', label: 'Left' }, { id: 'center', label: 'Centre' }, { id: 'right', label: 'Right' }]} onChange={(v) => style({ align: v })} />
      </Section>
      <Section title="Position">
        <Range label="Left / right" value={clip.x} min={0} max={1} step={0.005} format={pct} onChange={(v, l) => set({ x: v }, l)} onCommit={onCommit} />
        <Range label="Up / down" value={clip.y} min={0} max={1} step={0.005} format={pct} onChange={(v, l) => set({ y: v }, l)} onCommit={onCommit} />
        <Range label="Box width" value={s.width} min={0.1} max={1} step={0.01} format={pct} onChange={(v, l) => style({ width: v }, l)} onCommit={onCommit} />
      </Section>
      <Section title="Animation">
        <Choice label="In" value={clip.animationIn} options={TEXT_ANIMS} onChange={(v) => set({ animationIn: v })} />
        <Choice label="Out" value={clip.animationOut} options={TEXT_ANIMS.filter((a) => a.id !== 'typewriter')} onChange={(v) => set({ animationOut: v })} />
      </Section>
      {clip.caption && (
        <Button size="sm" onClick={() => onStyleAllCaptions(s, clip.y)} className="self-start">
          Use this style for all captions
        </Button>
      )}
    </>
  )
}

const FPS = [24, 25, 30, 50, 60]

function DocSettings({ doc, onChange }: { doc: TimelineDoc; onChange: (patch: Partial<TimelineDoc>) => void }) {
  return (
    <Panel title="Video settings">
      <p className="text-sm text-muted -mt-2">
        {doc.width}×{doc.height} · {doc.fps} fps. Select a clip to edit it.
      </p>
      <Colour label="Background" value={doc.background} onChange={(v) => onChange({ background: v })} />
      <label className="flex items-center justify-between gap-2 text-sm">
        Frame rate
        <Select value={doc.fps} onChange={(e) => onChange({ fps: Number(e.target.value) })} className="h-8 w-28 text-sm">
          {FPS.map((f) => (
            <option key={f} value={f}>
              {f} fps
            </option>
          ))}
        </Select>
      </label>
      <div className="border-t border-line pt-3 text-xs text-faint flex flex-col gap-1">
        <p className="font-semibold text-muted">Shortcuts</p>
        <p>Space play/pause · S split · Del delete · Shift+Del delete and close gap</p>
        <p>Ctrl+D duplicate · Ctrl+Z undo · Ctrl+Shift+Z redo · ←/→ one frame (Shift: 1 s)</p>
        <p>+/− zoom · Home/End jump · Shift+click select several</p>
      </div>
    </Panel>
  )
}
