import { Copy, LayoutTemplate, Plus, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ThumbnailCanvas } from '../components/ThumbnailCanvas'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import { useCreateDesign, useDeleteDesign, useDesigns, useDuplicateDesign, useUpdateDesign } from '../lib/studioQueries'
import { builtInTemplates, newDocument, SIZE_PRESETS } from '../lib/thumbnails'
import type { ThumbnailSummary } from '../lib/types'
import { toast } from '../stores/ui'

export function ThumbnailsPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const designs = useDesigns(false)
  const templates = useDesigns(true)
  const create = useCreateDesign()
  const [creating, setCreating] = useState(false)
  const started = useRef(false)

  // Coming from the AI image generator with ?background=<media id>: open a new YouTube design with it.
  const background = params.get('background')
  useEffect(() => {
    if (!background || started.current) return
    started.current = true
    create.mutate(
      { name: 'New thumbnail', width: 1280, height: 720, document: newDocument(), template: false },
      { onSuccess: (d) => navigate(`/create/thumbnails/${d.id}?background=${background}`, { replace: true }), onError: (e) => toast.error(e.message) },
    )
  }, [background, create, navigate])

  return (
    <>
      <header className="flex flex-wrap items-end justify-between gap-4 mb-8">
        <div>
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Thumbnail studio</h1>
          <p className="text-muted mt-1 max-w-2xl">Layered designs with Tamil and English lettering. Exports go to your media library as PNG or JPG.</p>
        </div>
        <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
          New design
        </Button>
      </header>

      {background && <Spinner label="Starting a design with your image" />}

      <section aria-labelledby="designs-heading" className="mb-10">
        <h2 id="designs-heading" className="text-lg font-semibold mb-3">
          Designs
        </h2>
        {designs.isPending && <Spinner label="Loading designs" />}
        {designs.error && <ErrorNotice error={designs.error} onRetry={() => designs.refetch()} />}
        {designs.data?.length === 0 && (
          <EmptyState
            title="No designs yet"
            body="Start from a blank canvas or a template. Thumbnails are 1280×720; there are sizes for Instagram and Shorts too."
            action={<Button variant="primary" onClick={() => setCreating(true)}>New design</Button>}
          />
        )}
        {designs.data && designs.data.length > 0 && (
          <ul className="grid sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4 gap-4">
            {designs.data.map((d) => (
              <DesignCard key={d.id} design={d} />
            ))}
          </ul>
        )}
      </section>

      {templates.data && templates.data.length > 0 && (
        <section aria-labelledby="templates-heading">
          <h2 id="templates-heading" className="text-lg font-semibold mb-3">
            My templates
          </h2>
          <ul className="grid sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4 gap-4">
            {templates.data.map((d) => (
              <DesignCard key={d.id} design={d} />
            ))}
          </ul>
        </section>
      )}

      {creating && <NewDesignDialog onClose={() => setCreating(false)} templates={templates.data ?? []} />}
    </>
  )
}

function DesignCard({ design }: { design: ThumbnailSummary }) {
  const navigate = useNavigate()
  const dup = useDuplicateDesign()
  const del = useDeleteDesign()
  const update = useUpdateDesign()
  const [confirm, setConfirm] = useState(false)
  return (
    <li className="rounded-xl border border-line bg-surface overflow-hidden flex flex-col">
      <Link to={`/create/thumbnails/${design.id}`} className="block bg-surface-2" aria-label={`Open ${design.name}`}>
        <div className="aspect-video flex items-center justify-center overflow-hidden">
          {design.export ? (
            <img src={design.export.url} alt="" className="max-h-full max-w-full object-contain" loading="lazy" />
          ) : (
            <span className="text-sm text-faint">Not exported yet</span>
          )}
        </div>
      </Link>
      <div className="p-3 flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="font-medium truncate">{design.name}</p>
          <p className="text-xs text-faint tabular">
            {design.width}×{design.height} · {formatRelative(design.updatedAt)}
          </p>
        </div>
        <Button
          size="sm"
          variant="ghost"
          aria-label={design.template ? `Use template ${design.name}` : `Duplicate ${design.name}`}
          title={design.template ? 'Use this template' : 'Duplicate'}
          icon={<Copy className="size-4" />}
          loading={dup.isPending}
          onClick={() =>
            dup.mutate(
              { id: design.id, name: design.template ? 'New thumbnail' : undefined, template: false },
              { onSuccess: (d) => navigate(`/create/thumbnails/${d.id}`), onError: (e) => toast.error(e.message) },
            )
          }
        />
        <Button
          size="sm"
          variant="ghost"
          aria-label={design.template ? `Turn ${design.name} back into a design` : `Save ${design.name} as a template`}
          title={design.template ? 'Move to designs' : 'Save as template'}
          icon={<LayoutTemplate className="size-4" />}
          onClick={() =>
            update.mutate(
              { id: design.id, template: !design.template },
              { onSuccess: () => toast.success(design.template ? 'Moved to designs' : 'Saved as a template'), onError: (e) => toast.error(e.message) },
            )
          }
        />
        <Button size="sm" variant="ghost" aria-label={`Delete ${design.name}`} icon={<Trash2 className="size-4" />} onClick={() => setConfirm(true)} />
      </div>
      <Dialog
        open={confirm}
        title={`Delete “${design.name}”?`}
        onClose={() => setConfirm(false)}
        footer={
          <>
            <Button onClick={() => setConfirm(false)}>Cancel</Button>
            <Button variant="danger" loading={del.isPending} onClick={() => del.mutate(design.id, { onSuccess: () => setConfirm(false), onError: (e) => toast.error(e.message) })}>
              Delete
            </Button>
          </>
        }
      >
        <p className="text-muted">Exported images stay in your media library.</p>
      </Dialog>
    </li>
  )
}

function NewDesignDialog({ onClose, templates }: { onClose: () => void; templates: ThumbnailSummary[] }) {
  const navigate = useNavigate()
  const create = useCreateDesign()
  const dup = useDuplicateDesign()
  const [presetId, setPresetId] = useState<string>('youtube')
  const [name, setName] = useState('New thumbnail')
  const [start, setStart] = useState<string>('blank')
  const preset = SIZE_PRESETS.find((p) => p.id === presetId) ?? SIZE_PRESETS[0]
  const builtIn = builtInTemplates(preset.width, preset.height)
  const previewScale = 150 / preset.width

  function submit() {
    if (start.startsWith('saved:')) {
      dup.mutate(
        { id: start.slice(6), name: name.trim() || 'New thumbnail', template: false },
        { onSuccess: (d) => navigate(`/create/thumbnails/${d.id}`), onError: (e) => toast.error(e.message) },
      )
      return
    }
    const document = start === 'blank' ? newDocument() : builtIn.find((t) => t.id === start)!.document
    create.mutate(
      { name: name.trim() || 'New thumbnail', width: preset.width, height: preset.height, document, template: false },
      { onSuccess: (d) => navigate(`/create/thumbnails/${d.id}`), onError: (e) => toast.error(e.message) },
    )
  }

  return (
    <Dialog
      open
      size="xl"
      title="New design"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={create.isPending || dup.isPending}>
            Create
          </Button>
        </>
      }
    >
      <div className="grid sm:grid-cols-2 gap-4 mb-5">
        <Field label="Name">{(id) => <Input id={id} value={name} maxLength={160} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Size">
          {(id) => (
            <Select id={id} value={presetId} onChange={(e) => setPresetId(e.target.value)} disabled={start.startsWith('saved:')}>
              {SIZE_PRESETS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label} · {p.width}×{p.height}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
      <fieldset>
        <legend className="text-sm font-medium mb-2">Start from</legend>
        <ul className="grid grid-cols-2 md:grid-cols-4 gap-3" role="radiogroup">
          <li>
            <StartOption checked={start === 'blank'} onSelect={() => setStart('blank')} label="Blank">
              <div style={{ width: preset.width * previewScale, height: preset.height * previewScale }} className="bg-[#1e2240] rounded" />
            </StartOption>
          </li>
          {builtIn.map((t) => (
            <li key={t.id}>
              <StartOption checked={start === t.id} onSelect={() => setStart(t.id)} label={t.name}>
                <ThumbnailCanvas doc={t.document} width={preset.width} height={preset.height} scale={previewScale} />
              </StartOption>
            </li>
          ))}
          {templates.map((t) => (
            <li key={t.id}>
              <StartOption checked={start === `saved:${t.id}`} onSelect={() => setStart(`saved:${t.id}`)} label={`${t.name} (${t.width}×${t.height})`}>
                {t.export ? (
                  <img src={t.export.url} alt="" className="max-h-24 rounded" />
                ) : (
                  <div className="h-20 w-full rounded bg-surface-2" />
                )}
              </StartOption>
            </li>
          ))}
        </ul>
      </fieldset>
    </Dialog>
  )
}

function StartOption({ checked, onSelect, label, children }: { checked: boolean; onSelect: () => void; label: string; children: ReactNode }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      onClick={onSelect}
      className={cn('w-full rounded-xl border-2 p-2 flex flex-col items-center gap-2 text-sm', checked ? 'border-marigold-500' : 'border-line hover:border-faint')}
    >
      <span className="pointer-events-none">{children}</span>
      {label}
    </button>
  )
}
