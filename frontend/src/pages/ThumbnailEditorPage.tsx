import '@fontsource/anton'
import '@fontsource/bebas-neue'
import '@fontsource/catamaran/400.css'
import '@fontsource/catamaran/800.css'
import type Konva from 'konva'
import {
  ArrowRight,
  ArrowDown,
  ArrowUp,
  Circle,
  Copy,
  Download,
  Eye,
  EyeOff,
  ImagePlus,
  Lock,
  Redo2,
  Save,
  Shapes,
  Sparkles,
  Square,
  Trash2,
  Type,
  Undo2,
  Unlock,
  Wallpaper,
} from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { MediaPicker } from '../components/MediaPicker'
import { exportStage, loadMediaImage, ThumbnailCanvas } from '../components/ThumbnailCanvas'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { useCreateDesign, useDesign, useExportDesign, useUpdateDesign } from '../lib/studioQueries'
import { duplicateLayer, FONTS, makeVariation, moveLayer, newImage, newShape, newText, resizeDocument, SIZE_PRESETS } from '../lib/thumbnails'
import type { Layer, TextLayer, ThumbnailDesign, ThumbnailDocument } from '../lib/types'
import { toast } from '../stores/ui'

// ---- undo / redo -------------------------------------------------------------------

function useHistory(initial: ThumbnailDocument) {
  const [state, setState] = useState({ past: [] as ThumbnailDocument[], present: initial, future: [] as ThumbnailDocument[] })
  const set = useCallback((next: ThumbnailDocument | ((d: ThumbnailDocument) => ThumbnailDocument), merge = false) => {
    setState((s) => {
      const value = typeof next === 'function' ? next(s.present) : next
      if (value === s.present) return s
      return { past: merge ? s.past : [...s.past, s.present].slice(-80), present: value, future: [] }
    })
  }, [])
  const undo = useCallback(() => setState((s) => (s.past.length ? { past: s.past.slice(0, -1), present: s.past[s.past.length - 1], future: [s.present, ...s.future] } : s)), [])
  const redo = useCallback(() => setState((s) => (s.future.length ? { past: [...s.past, s.present], present: s.future[0], future: s.future.slice(1) } : s)), [])
  const reset = useCallback((d: ThumbnailDocument) => setState({ past: [], present: d, future: [] }), [])
  return { doc: state.present, set, undo, redo, reset, canUndo: state.past.length > 0, canRedo: state.future.length > 0 }
}

function useFontsReady() {
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let live = true
    const loads = FONTS.flatMap((f) => [`400 48px "${f.family}"`, `700 48px "${f.family}"`]).map((spec) =>
      document.fonts?.load(spec, 'Aa தமிழ்').catch(() => undefined),
    )
    Promise.all(loads).then(() => live && setVersion((v) => v + 1))
    return () => {
      live = false
    }
  }, [])
  return version
}

// ---- page -------------------------------------------------------------------------------

export function ThumbnailEditorPage() {
  const { id } = useParams()
  const design = useDesign(id)
  if (design.isPending) return <Spinner label="Opening design" />
  if (design.error) return <ErrorNotice error={design.error} onRetry={() => design.refetch()} />
  return <Editor key={design.data.id} design={design.data} />
}

function Editor({ design }: { design: ThumbnailDesign }) {
  const navigate = useNavigate()
  const history = useHistory(design.document)
  const { doc, set } = history
  const [name, setName] = useState(design.name)
  const [selectedId, setSelectedId] = useState<string>()
  const [savedDoc, setSavedDoc] = useState(design.document)
  const [savedName, setSavedName] = useState(design.name)
  const [picker, setPicker] = useState<'image' | 'background'>()
  const [variations, setVariations] = useState(false)
  const [copying, setCopying] = useState(false)
  const update = useUpdateDesign()
  const exportDesign = useExportDesign()
  const stage = useRef<Konva.Stage>(null)
  const area = useRef<HTMLDivElement>(null)
  const [scale, setScale] = useState(0.5)
  const fontsVersion = useFontsReady()
  const W = design.width
  const H = design.height
  const dirty = doc !== savedDoc || name !== savedName
  const selected = doc.layers.find((l) => l.id === selectedId)

  // Fit the canvas to the available width (and most of the screen height).
  useLayoutEffect(() => {
    const el = area.current
    if (!el) return
    const fit = () => setScale(Math.min(el.clientWidth / W, (window.innerHeight * 0.68) / H, 1))
    fit()
    const ro = new ResizeObserver(fit)
    ro.observe(el)
    return () => ro.disconnect()
  }, [W, H])

  const patchLayer = useCallback(
    (id: string, patch: Partial<Layer>, merge = false) => set((d) => ({ ...d, layers: d.layers.map((l) => (l.id === id ? ({ ...l, ...patch } as Layer) : l)) }), merge),
    [set],
  )
  const addLayer = (l: Layer) => {
    set((d) => ({ ...d, layers: [...d.layers, l] }))
    setSelectedId(l.id)
  }
  const removeLayer = useCallback(
    (id: string) => {
      set((d) => ({ ...d, layers: d.layers.filter((l) => l.id !== id) }))
      setSelectedId(undefined)
    },
    [set],
  )

  const save = useCallback(() => {
    update.mutate(
      { id: design.id, name: name.trim() || design.name, document: doc },
      {
        onSuccess: () => {
          setSavedDoc(doc)
          setSavedName(name)
          toast.success('Design saved')
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }, [update, design.id, design.name, name, doc])

  // Keyboard: undo/redo, save, delete, nudge.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = (e.target as HTMLElement)?.closest('input, textarea, select, [contenteditable]')
      const mod = e.ctrlKey || e.metaKey
      if (mod && e.key.toLowerCase() === 's') {
        e.preventDefault()
        save()
        return
      }
      if (typing) return
      if (mod && e.key.toLowerCase() === 'z') {
        e.preventDefault()
        if (e.shiftKey) history.redo()
        else history.undo()
      } else if (mod && e.key.toLowerCase() === 'y') {
        e.preventDefault()
        history.redo()
      } else if (selected && !selected.locked && (e.key === 'Delete' || e.key === 'Backspace')) {
        e.preventDefault()
        removeLayer(selected.id)
      } else if (selected && !selected.locked && e.key.startsWith('Arrow')) {
        e.preventDefault()
        const step = e.shiftKey ? 10 : 1
        const dx = e.key === 'ArrowLeft' ? -step : e.key === 'ArrowRight' ? step : 0
        const dy = e.key === 'ArrowUp' ? -step : e.key === 'ArrowDown' ? step : 0
        patchLayer(selected.id, { x: selected.x + dx, y: selected.y + dy }, true)
      } else if (e.key === 'Escape') {
        setSelectedId(undefined)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [history, selected, save, removeLayer, patchLayer])

  useEffect(() => {
    if (!dirty) return
    const h = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  async function addMedia(mediaId: string, asBackground: boolean) {
    try {
      const img = await loadMediaImage(mediaId)
      const layer = newImage(mediaId, { width: img.naturalWidth, height: img.naturalHeight }, { width: W, height: H }, asBackground)
      if (asBackground) {
        set((d) => ({ ...d, layers: [layer, ...d.layers.filter((l) => !(l.type === 'image' && l.name === 'Background image'))] }))
        setSelectedId(undefined)
      } else addLayer(layer)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not load the image')
    }
  }

  async function doExport(mime: 'image/png' | 'image/jpeg') {
    if (!stage.current) return
    setSelectedId(undefined)
    try {
      const blob = await exportStage(stage.current, scale, mime)
      const ext = mime === 'image/png' ? 'png' : 'jpg'
      exportDesign.mutate(
        { id: design.id, blob, filename: `${name.trim() || 'thumbnail'}.${ext}` },
        {
          onSuccess: (d) => {
            toast.success('Exported to your media library')
            if (d.export) window.open(d.export.url, '_blank', 'noopener')
          },
          onError: (e) => toast.error(e.message),
        },
      )
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Export failed')
    }
  }

  // Load an image from ?background= once (from the AI image generator).
  useEffect(() => {
    const bg = new URLSearchParams(window.location.search).get('background')
    if (bg && !doc.layers.some((l) => l.type === 'image' && l.mediaId === bg)) {
      addMedia(bg, true)
      window.history.replaceState(null, '', window.location.pathname)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="flex flex-col gap-4">
      {/* ---- top bar ---- */}
      <div className="flex flex-wrap items-center gap-2">
        <Link to="/create/thumbnails" className="text-sm text-muted hover:text-ink mr-1">
          ← Designs
        </Link>
        <div className="w-64">
          <Input aria-label="Design name" value={name} onChange={(e) => setName(e.target.value)} maxLength={160} className="h-9" />
        </div>
        <span className="text-sm text-faint tabular">
          {W}×{H}
        </span>
        <div className="flex gap-1 ml-auto flex-wrap">
          <Button size="sm" variant="ghost" aria-label="Undo" title="Undo (Ctrl+Z)" disabled={!history.canUndo} onClick={history.undo} icon={<Undo2 className="size-4" />} />
          <Button size="sm" variant="ghost" aria-label="Redo" title="Redo (Ctrl+Shift+Z)" disabled={!history.canRedo} onClick={history.redo} icon={<Redo2 className="size-4" />} />
          <Button size="sm" onClick={() => setVariations(true)} icon={<Sparkles className="size-4" />}>
            Variations
          </Button>
          <Button size="sm" onClick={() => setCopying(true)} icon={<Copy className="size-4" />}>
            Other sizes
          </Button>
          <Button size="sm" onClick={save} loading={update.isPending} disabled={!dirty} icon={<Save className="size-4" />}>
            {dirty ? 'Save' : 'Saved'}
          </Button>
          <Button size="sm" onClick={() => doExport('image/jpeg')} loading={exportDesign.isPending} icon={<Download className="size-4" />}>
            JPG
          </Button>
          <Button size="sm" variant="primary" onClick={() => doExport('image/png')} loading={exportDesign.isPending} icon={<Download className="size-4" />}>
            Export PNG
          </Button>
        </div>
      </div>

      <div className="grid lg:grid-cols-[13rem_minmax(0,1fr)_17rem] gap-4 items-start">
        {/* ---- add & layers ---- */}
        <aside className="flex flex-col gap-4" aria-label="Add and layers">
          <div className="rounded-xl border border-line bg-surface p-3 grid grid-cols-2 gap-2">
            <ToolButton icon={<Type className="size-4" />} label="Text" onClick={() => addLayer(newText(W, H))} />
            <ToolButton icon={<ImagePlus className="size-4" />} label="Image" onClick={() => setPicker('image')} />
            <ToolButton icon={<Wallpaper className="size-4" />} label="Background" onClick={() => setPicker('background')} />
            <ToolButton icon={<Square className="size-4" />} label="Box" onClick={() => addLayer(newShape('rect', W, H))} />
            <ToolButton icon={<Circle className="size-4" />} label="Circle" onClick={() => addLayer(newShape('circle', W, H))} />
            <ToolButton icon={<ArrowRight className="size-4" />} label="Arrow" onClick={() => addLayer(newShape('arrow', W, H))} />
          </div>
          <Link to="/create/image" className="text-sm text-muted hover:text-ink inline-flex items-center gap-1.5 px-1">
            <Sparkles className="size-4" aria-hidden /> Make an AI background
          </Link>
          <div className="rounded-xl border border-line bg-surface">
            <h2 className="px-3 pt-3 pb-2 text-sm font-semibold flex items-center gap-1.5">
              <Shapes className="size-4" aria-hidden /> Layers
            </h2>
            {doc.layers.length === 0 && <p className="px-3 pb-3 text-sm text-faint">Nothing yet.</p>}
            <ol className="flex flex-col-reverse">
              {doc.layers.map((l) => (
                <li key={l.id} className={cn('flex items-center gap-1 px-2 py-1 text-sm', l.id === selectedId && 'bg-marigold-500/12')}>
                  <button className="flex-1 min-w-0 truncate text-left py-1" onClick={() => setSelectedId(l.id)} aria-current={l.id === selectedId}>
                    {l.type === 'text' ? (l as TextLayer).text.split('\n')[0] || 'Text' : l.name}
                  </button>
                  <IconToggle on={!l.hidden} onLabel={`Hide ${l.name}`} offLabel={`Show ${l.name}`} onIcon={<Eye className="size-3.5" />} offIcon={<EyeOff className="size-3.5" />} onClick={() => patchLayer(l.id, { hidden: !l.hidden })} />
                  <IconToggle on={!l.locked} onLabel={`Lock ${l.name}`} offLabel={`Unlock ${l.name}`} onIcon={<Unlock className="size-3.5" />} offIcon={<Lock className="size-3.5" />} onClick={() => patchLayer(l.id, { locked: !l.locked })} />
                </li>
              ))}
            </ol>
          </div>
        </aside>

        {/* ---- canvas ---- */}
        <div ref={area} className="min-w-0 flex justify-center">
          <div className="rounded-lg overflow-hidden shadow-[0_8px_30px_rgba(0,0,0,0.25)] bg-[repeating-conic-gradient(var(--surface-2)_0_25%,transparent_0_50%)] bg-[length:16px_16px]">
            <ThumbnailCanvas
              ref={stage}
              doc={doc}
              width={W}
              height={H}
              scale={scale}
              selectedId={selectedId}
              onSelect={setSelectedId}
              onChange={(id, patch) => patchLayer(id, patch)}
              fontsVersion={fontsVersion}
            />
          </div>
        </div>

        {/* ---- properties ---- */}
        <aside className="rounded-xl border border-line bg-surface p-4 flex flex-col gap-4" aria-label="Properties">
          {!selected && (
            <>
              <h2 className="text-sm font-semibold">Canvas</h2>
              <ColourField label="Background colour" value={doc.background} onChange={(v) => set((d) => ({ ...d, background: v }), true)} />
              <p className="text-xs text-faint">Select a layer to edit it. Arrow keys nudge, Shift+arrow moves 10 px, Delete removes.</p>
            </>
          )}
          {selected && (
            <LayerProperties
              layer={selected}
              onChange={(patch, merge) => patchLayer(selected.id, patch, merge)}
              onDelete={() => removeLayer(selected.id)}
              onDuplicate={() => set((d) => ({ ...d, layers: duplicateLayer(d.layers, selected.id) }))}
              onMove={(dir) => set((d) => ({ ...d, layers: moveLayer(d.layers, selected.id, dir) }))}
            />
          )}
        </aside>
      </div>

      <MediaPicker
        open={!!picker}
        kind="IMAGE"
        title={picker === 'background' ? 'Pick a background image' : 'Pick an image'}
        onClose={() => setPicker(undefined)}
        onPick={(m) => {
          addMedia(m.id, picker === 'background')
          setPicker(undefined)
        }}
      />
      {variations && <VariationsDialog design={design} doc={doc} name={name} onClose={() => setVariations(false)} onOpen={(id) => navigate(`/create/thumbnails/${id}`)} />}
      {copying && <OtherSizesDialog design={design} doc={doc} name={name} onClose={() => setCopying(false)} onOpen={(id) => navigate(`/create/thumbnails/${id}`)} />}
    </div>
  )
}

function ToolButton({ icon, label, onClick }: { icon: ReactNode; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="flex flex-col items-center gap-1 rounded-lg py-2 text-xs text-muted hover:text-ink hover:bg-surface-2">
      {icon}
      {label}
    </button>
  )
}

function IconToggle({ on, onLabel, offLabel, onIcon, offIcon, onClick }: { on: boolean; onLabel: string; offLabel: string; onIcon: ReactNode; offIcon: ReactNode; onClick: () => void }) {
  return (
    <button type="button" aria-label={on ? onLabel : offLabel} onClick={onClick} className={cn('p-1 rounded hover:bg-surface-2', on ? 'text-faint' : 'text-ink')}>
      {on ? onIcon : offIcon}
    </button>
  )
}

function ColourField({ label, value, onChange, allowNone }: { label: string; value: string; onChange: (v: string) => void; allowNone?: boolean }) {
  const none = !value || value === 'transparent'
  return (
    <div className="flex items-center justify-between gap-2 text-sm">
      <span>{label}</span>
      <span className="flex items-center gap-2">
        {allowNone && (
          <label className="flex items-center gap-1 text-xs text-muted">
            <input type="checkbox" checked={!none} onChange={(e) => onChange(e.target.checked ? '#ffd23f' : '')} className="accent-marigold-500" />
            On
          </label>
        )}
        <input
          type="color"
          aria-label={label}
          value={none ? '#000000' : value}
          disabled={none && allowNone}
          onChange={(e) => onChange(e.target.value)}
          className="h-8 w-10 rounded border border-line bg-transparent disabled:opacity-40"
        />
      </span>
    </div>
  )
}

function NumberField({ label, value, onChange, min, max, step = 1 }: { label: string; value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number }) {
  return (
    <label className="flex items-center justify-between gap-2 text-sm">
      {label}
      <input
        type="number"
        value={Number.isFinite(value) ? value : 0}
        min={min}
        max={max}
        step={step}
        onChange={(e) => e.target.value !== '' && onChange(Number(e.target.value))}
        className="w-20 h-8 rounded-md border border-line bg-surface px-2 tabular text-right"
      />
    </label>
  )
}

function LayerProperties({
  layer,
  onChange,
  onDelete,
  onDuplicate,
  onMove,
}: {
  layer: Layer
  onChange: (patch: Partial<Layer>, merge?: boolean) => void
  onDelete: () => void
  onDuplicate: () => void
  onMove: (dir: 'up' | 'down' | 'top' | 'bottom') => void
}) {
  return (
    <>
      <div className="flex items-center gap-1">
        <h2 className="text-sm font-semibold flex-1 truncate">{layer.type === 'text' ? 'Text' : layer.name}</h2>
        <Button size="sm" variant="ghost" aria-label="Bring forward" onClick={() => onMove('up')} icon={<ArrowUp className="size-4" />} />
        <Button size="sm" variant="ghost" aria-label="Send backward" onClick={() => onMove('down')} icon={<ArrowDown className="size-4" />} />
        <Button size="sm" variant="ghost" aria-label="Duplicate" onClick={onDuplicate} icon={<Copy className="size-4" />} />
        <Button size="sm" variant="ghost" aria-label="Delete layer" onClick={onDelete} icon={<Trash2 className="size-4" />} />
      </div>

      {layer.type === 'text' && (
        <>
          <Field label="Text">
            {(id) => (
              <Textarea id={id} rows={3} value={layer.text} onChange={(e) => onChange({ text: e.target.value }, true)} className="min-h-0 text-base" />
            )}
          </Field>
          <Field label="Font">
            {(id) => (
              <Select id={id} value={layer.fontFamily} onChange={(e) => onChange({ fontFamily: e.target.value })}>
                {FONTS.map((f) => (
                  <option key={f.family} value={f.family}>
                    {f.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <div className="flex gap-1">
            {(['normal', 'bold'] as const).map((w) => (
              <button
                key={w}
                type="button"
                aria-pressed={layer.fontStyle.includes('bold') === (w === 'bold')}
                onClick={() => onChange({ fontStyle: w })}
                className={cn('flex-1 rounded-md border py-1 text-sm', layer.fontStyle.includes('bold') === (w === 'bold') ? 'border-marigold-500 bg-marigold-500/10' : 'border-line')}
              >
                {w === 'bold' ? 'Bold' : 'Regular'}
              </button>
            ))}
            {(['left', 'center', 'right'] as const).map((a) => (
              <button
                key={a}
                type="button"
                aria-pressed={layer.align === a}
                aria-label={`Align ${a}`}
                onClick={() => onChange({ align: a })}
                className={cn('flex-1 rounded-md border py-1 text-sm capitalize', layer.align === a ? 'border-marigold-500 bg-marigold-500/10' : 'border-line')}
              >
                {a[0].toUpperCase()}
              </button>
            ))}
          </div>
          <NumberField label="Size" value={layer.fontSize} min={8} max={600} onChange={(v) => onChange({ fontSize: v }, true)} />
          <NumberField label="Line height" value={layer.lineHeight} min={0.7} max={2} step={0.05} onChange={(v) => onChange({ lineHeight: v }, true)} />
          <NumberField label="Letter spacing" value={layer.letterSpacing} min={-10} max={40} onChange={(v) => onChange({ letterSpacing: v }, true)} />
          <ColourField label="Colour" value={layer.fill} onChange={(v) => onChange({ fill: v }, true)} />
          <ColourField label="Outline" value={layer.stroke} onChange={(v) => onChange({ stroke: v }, true)} />
          <NumberField label="Outline width" value={layer.strokeWidth} min={0} max={60} onChange={(v) => onChange({ strokeWidth: v }, true)} />
          <ColourField label="Label behind" value={layer.background} allowNone onChange={(v) => onChange({ background: v })} />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={layer.shadow} onChange={(e) => onChange({ shadow: e.target.checked })} className="accent-marigold-500" />
            Drop shadow
          </label>
        </>
      )}

      {layer.type === 'image' && (
        <>
          <NumberField label="Corner radius" value={layer.cornerRadius} min={0} max={1000} onChange={(v) => onChange({ cornerRadius: v }, true)} />
          <ColourField label="Border" value={layer.stroke} onChange={(v) => onChange({ stroke: v }, true)} />
          <NumberField label="Border width" value={layer.strokeWidth} min={0} max={80} onChange={(v) => onChange({ strokeWidth: v }, true)} />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={layer.shadow} onChange={(e) => onChange({ shadow: e.target.checked })} className="accent-marigold-500" />
            Drop shadow
          </label>
          <p className="text-xs text-faint">Tip: cut the person out in Image tools → Remove background, then place the PNG here.</p>
        </>
      )}

      {(layer.type === 'rect' || layer.type === 'circle' || layer.type === 'arrow') && (
        <>
          <ColourField label="Fill" value={layer.fill} allowNone={layer.type !== 'arrow'} onChange={(v) => onChange({ fill: v || 'transparent' }, true)} />
          {layer.type !== 'arrow' && (
            <>
              <ColourField label="Outline" value={layer.stroke} onChange={(v) => onChange({ stroke: v }, true)} />
              <NumberField label="Outline width" value={layer.strokeWidth} min={0} max={80} onChange={(v) => onChange({ strokeWidth: v }, true)} />
            </>
          )}
          {layer.type === 'rect' && <NumberField label="Corner radius" value={layer.cornerRadius} min={0} max={500} onChange={(v) => onChange({ cornerRadius: v }, true)} />}
        </>
      )}

      <div className="border-t border-line pt-3 flex flex-col gap-2">
        <div className="grid grid-cols-2 gap-2">
          <NumberField label="X" value={layer.x} onChange={(v) => onChange({ x: v }, true)} />
          <NumberField label="Y" value={layer.y} onChange={(v) => onChange({ y: v }, true)} />
        </div>
        <NumberField label="Rotation" value={layer.rotation} min={-180} max={180} onChange={(v) => onChange({ rotation: v }, true)} />
        <label className="flex items-center justify-between gap-2 text-sm">
          Opacity
          <input type="range" min={0.05} max={1} step={0.05} value={layer.opacity} onChange={(e) => onChange({ opacity: Number(e.target.value) }, true)} className="w-28 accent-marigold-500" />
        </label>
      </div>
    </>
  )
}

function VariationsDialog({ design, doc, name, onClose, onOpen }: { design: ThumbnailDesign; doc: ThumbnailDocument; name: string; onClose: () => void; onOpen: (id: string) => void }) {
  const create = useCreateDesign()
  const options = [1, 2, 3, 4].map((n) => makeVariation(doc, n))
  const [chosen, setChosen] = useState<Set<number>>(new Set([0, 1]))
  const previewScale = 220 / design.width

  async function saveChosen() {
    const ids: string[] = []
    for (const i of chosen) {
      const d = await create.mutateAsync({ name: `${name} · variation ${i + 1}`, width: design.width, height: design.height, document: options[i], template: false, projectId: design.projectId })
      ids.push(d.id)
    }
    toast.success(`${ids.length} variations saved under Designs`)
    onClose()
    if (ids.length === 1) onOpen(ids[0])
  }

  return (
    <Dialog
      open
      size="xl"
      title="Variations"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={chosen.size === 0} loading={create.isPending} onClick={() => saveChosen().catch((e) => toast.error(e.message))}>
            Save {chosen.size} as new designs
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">Same layout with different colours and lettering. Save a few and compare them, or A/B test on YouTube.</p>
      <ul className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {options.map((d, i) => (
          <li key={i}>
            <label className={cn('block rounded-lg border-2 p-1 cursor-pointer', chosen.has(i) ? 'border-marigold-500' : 'border-transparent')}>
              <ThumbnailCanvas doc={d} width={design.width} height={design.height} scale={previewScale} />
              <span className="flex items-center gap-2 text-sm mt-1 px-1">
                <input
                  type="checkbox"
                  checked={chosen.has(i)}
                  onChange={(e) =>
                    setChosen((s) => {
                      const n = new Set(s)
                      if (e.target.checked) n.add(i)
                      else n.delete(i)
                      return n
                    })
                  }
                  className="accent-marigold-500"
                />
                Variation {i + 1}
              </span>
            </label>
          </li>
        ))}
      </ul>
    </Dialog>
  )
}

function OtherSizesDialog({ design, doc, name, onClose, onOpen }: { design: ThumbnailDesign; doc: ThumbnailDocument; name: string; onClose: () => void; onOpen: (id: string) => void }) {
  const create = useCreateDesign()
  const options = SIZE_PRESETS.filter((p) => p.width !== design.width || p.height !== design.height)
  const [preset, setPreset] = useState<string>(options[0]?.id ?? '')
  const p = options.find((x) => x.id === preset)
  return (
    <Dialog
      open
      title="Copy to another size"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!p}
            loading={create.isPending}
            onClick={() =>
              p &&
              create.mutate(
                { name: `${name} · ${p.label}`, width: p.width, height: p.height, document: resizeDocument(doc, design, p), template: false, projectId: design.projectId },
                { onSuccess: (d) => { onClose(); onOpen(d.id) }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Make copy
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">A new design is made with everything scaled to fit. You may want to nudge a few layers afterwards.</p>
      <Field label="Size">
        {(id) => (
          <Select id={id} value={preset} onChange={(e) => setPreset(e.target.value)}>
            {options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label} · {o.width}×{o.height}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </Dialog>
  )
}
