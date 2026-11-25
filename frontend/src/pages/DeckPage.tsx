import { ArrowDown, ArrowUp, ChevronLeft, Download, ImageIcon, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { MediaPicker } from '../components/MediaPicker'
import { History, ReviewPanel, VersionList } from '../components/review'
import { SlideCanvas } from '../components/SlideCanvas'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import { type Level } from '../lib/materials'
import {
  blankSlide,
  fitOf,
  LAYOUT_FIELDS,
  LAYOUT_LABEL,
  LAYOUTS,
  LEVEL_LABEL,
  measureDeck,
  move,
  SLIDE_TYPES,
  slidesExportUrl,
  STYLE_KEYS,
  STYLES,
  TYPE_LABEL,
  useRegenerateSlide,
  withLayout,
  type Deck,
  type DeckFit,
  type Layout,
  type Proposal,
  type Slide,
  type SlideType,
  type StyleKey,
} from '../lib/slides'
import { toast } from '../stores/ui'

/** /slides/:resourceId — one presentation: the slides, their text fit, speaker notes, review and downloads. */
export function DeckPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<Deck>({ resourceId })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [liveFit, setLiveFit] = useState<DeckFit | null>(null)
  const [measuring, setMeasuring] = useState(false)
  const [regenerating, setRegenerating] = useState<Slide | null>(null)
  const deck = page.content
  const projectId = page.state.data?.resource?.projectId

  // Measure again while editing; the saved version is measured by the server when it's saved.
  const pending = useRef(0)
  useEffect(() => {
    if (!page.dirty || !deck || !projectId) return
    const n = ++pending.current
    const t = setTimeout(() => {
      setMeasuring(true)
      measureDeck(projectId, deck).then(
        (f) => n === pending.current && setLiveFit(f),
        (e) => n === pending.current && setLiveFit({ checked: false, ok: false, error: e instanceof Error ? e.message : 'Couldn’t measure' }),
      ).finally(() => n === pending.current && setMeasuring(false))
    }, 700)
    return () => clearTimeout(t)
  }, [deck, page.dirty, projectId])

  if (page.loading) return <Spinner label="Opening the presentation" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  if (!state.resource || !d || !deck) return <ErrorNotice error={new Error('This presentation has no versions')} />
  const r = state.resource
  const edit = page.editable
  const slides = deck.slides
  // while editing, the latest measurement of the edited slides; otherwise the saved one
  const fit = (page.dirty ? liveFit : null) ?? deck.fit ?? null
  const selected = slides.find((s) => s.id === selectedId) ?? slides[0]
  const index = selected ? slides.indexOf(selected) : -1
  const set = (patch: Partial<Deck>) => page.setContent({ ...deck, ...patch })
  const setSlides = (list: Slide[]) => set({ slides: list })
  const change = (patch: Partial<Slide>) => selected && setSlides(slides.map((s) => (s.id === selected.id ? { ...s, ...patch } : s)))
  const over = (fit?.slides ?? []).filter((s) => !s.ok).length

  return (
    <>
      <Link to={`/projects/${r.projectId}/slides`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Presentations
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{deck.title || deck.topicTitle}</h1>
        <p className="text-muted mt-1">
          {deck.topicTitle}, {(LEVEL_LABEL[deck.level as Level] ?? deck.level).toLowerCase()} notes. {slides.length} slides in {STYLES[deck.style]?.name ?? deck.style}
          {deck.model ? `. Made with ${deck.model}` : ''}
        </p>
      </header>

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-6">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes{measuring ? '. Measuring…' : ''}</span>
              <Button variant="ghost" onClick={page.discard}>Discard</Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>Save draft</Button>
            </div>
          )}
          {page.reviewerEditing && <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm">You're editing as the reviewer. Approving with your edits saves them as a new, approved version.</p>}

          <FitSummary fit={fit} over={over} />

          <div className="flex flex-wrap items-center gap-3">
            <label htmlFor="deck-style" className="text-sm font-medium">Style</label>
            <div className="w-60">
              <Select id="deck-style" value={deck.style} disabled={!edit} onChange={(e) => set({ style: e.target.value as StyleKey })}>
                {STYLE_KEYS.map((k) => <option key={k} value={k}>{STYLES[k].name}</option>)}
              </Select>
            </div>
            <span className="text-sm text-muted">{slides.length} slides</span>
          </div>

          <Filmstrip deck={deck} fit={fit} selectedId={selected?.id} onSelect={setSelectedId} projectId={r.projectId} />

          {selected && (
            <section aria-labelledby="slide-h" className="space-y-4">
              <h2 id="slide-h" className="text-lg font-semibold">
                Slide {index + 1}: {TYPE_LABEL[selected.type] ?? selected.type}
              </h2>
              <div className="flex flex-wrap items-center gap-2">
                {edit && (
                  <>
                    <IconButton label="Move up" disabled={index === 0} onClick={() => setSlides(move(slides, index, -1))}><ArrowUp className="size-4" /></IconButton>
                    <IconButton label="Move down" disabled={index === slides.length - 1} onClick={() => setSlides(move(slides, index, 1))}><ArrowDown className="size-4" /></IconButton>
                    <IconButton label="Delete slide" disabled={slides.length <= 1} onClick={() => {
                      setSlides(slides.filter((s) => s.id !== selected.id))
                      setSelectedId(slides[index + 1]?.id ?? slides[index - 1]?.id ?? null)
                    }}><Trash2 className="size-4" /></IconButton>
                    <AddSlide onAdd={(t) => {
                      const s = blankSlide(t)
                      setSlides([...slides.slice(0, index + 1), s, ...slides.slice(index + 1)])
                      setSelectedId(s.id)
                    }} />
                  </>
                )}
                {d.version.status === 'DRAFT' && d.you.canEdit && selected.type !== 'REFERENCES' && (
                  <Button size="sm" variant="ghost" icon={<RefreshCw className="size-4" />} onClick={() => setRegenerating(selected)}
                    disabled={page.dirty} title={page.dirty ? 'Save or discard your changes first' : undefined}>
                    Redo with AI
                  </Button>
                )}
              </div>
              <SlideCanvas slide={selected} style={deck.style} fit={fitOf(fit, selected.id)} number={index + 1} total={slides.length} projectId={r.projectId}
                className="rounded-xl border border-line shadow-sm" />
              <SlideProblems fit={fit} slide={selected} />
              {edit ? (
                <SlideEditor slide={selected} projectId={r.projectId} change={change} />
              ) : (
                <SlideReadOnly slide={selected} />
              )}
            </section>
          )}
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel detail={d} kindLabel="presentation" dirty={page.dirty} edited={deck} saveFirst={page.saveDraft} editing={page.reviewerEditing}
            onReviewerEdit={(on) => { page.setReviewerEditing(on); if (!on) page.discard() }} onEdit={page.startDraft}
            originLabel={d.version.origin === 'AI' ? 'made with AI from the approved notes' : undefined} />
          <section aria-labelledby="export-h">
            <h2 id="export-h" className="font-semibold mb-2">Download version {d.version.versionNo}</h2>
            <div className="flex flex-wrap gap-2">
              {([['pptx', 'PowerPoint'], ['pdf', 'PDF']] as const).map(([f, label]) => (
                <a key={f} className={cn('inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-3 h-9 text-sm hover:bg-surface-2', page.dirty && 'pointer-events-none opacity-50')}
                  aria-disabled={page.dirty || undefined} href={slidesExportUrl(d.version.id, f)}>
                  <Download className="size-4" aria-hidden /> {label}
                </a>
              ))}
            </div>
            <p className="text-sm text-muted mt-1">
              {page.dirty ? 'Save first: downloads are of the saved version.' : 'Each file is drawn and checked again before it downloads; text that doesn’t fit stops the download.'}
            </p>
          </section>
          <section aria-labelledby="versions-h">
            <h2 id="versions-h" className="font-semibold mb-2">Versions</h2>
            <VersionList versions={state.versions} current={d.version.id} approvedId={state.approvedVersionId} onPick={page.pick} />
          </section>
          <section aria-labelledby="history-h">
            <h2 id="history-h" className="font-semibold mb-2">History</h2>
            <History resourceId={r.id} />
          </section>
        </aside>
      </div>
      {regenerating && (
        <RegenerateDialog resourceId={r.id} versionId={d.version.id} slide={regenerating} deck={deck} projectId={r.projectId}
          onUse={(s) => { setSlides(slides.map((x) => (x.id === s.id ? s : x))); setSelectedId(s.id); setRegenerating(null); toast.success('Slide replaced. Save the draft to keep it.') }}
          onClose={() => setRegenerating(null)} />
      )}
    </>
  )
}

// ---- the deck -----------------------------------------------------------------------------------

function FitSummary({ fit, over }: { fit: DeckFit | null; over: number }) {
  if (!fit) return null
  if (!fit.checked) {
    return <p role="status" className="rounded-xl border border-rose-500/50 px-4 py-3 text-sm">The text fit couldn’t be measured: {fit.error ?? 'unknown reason'}. It can’t be approved until it is.</p>
  }
  return (
    <p role="status" className={cn('rounded-xl border px-4 py-3 text-sm', over ? 'border-rose-500/50' : 'border-line text-muted')}>
      {over ? `Text doesn’t fit on ${over} slide${over === 1 ? '' : 's'} (outlined). Shorten it or split the slide.` : 'All text fits its box at a readable size.'}
      {fit.method && <span className="block text-xs text-muted mt-1">{fit.method}</span>}
    </p>
  )
}

function Filmstrip({ deck, fit, selectedId, onSelect, projectId }: { deck: Deck; fit: DeckFit | null; selectedId?: string; onSelect: (id: string) => void; projectId: string }) {
  return (
    <ol aria-label="Slides" className="flex gap-3 overflow-x-auto pb-2 -mx-1 px-1 snap-x">
      {deck.slides.map((s, i) => {
        const f = fitOf(fit, s.id)
        return (
          <li key={s.id} className="shrink-0 w-40 snap-start">
            <button type="button" onClick={() => onSelect(s.id)} aria-current={s.id === selectedId ? 'true' : undefined}
              className={cn('block w-full text-left rounded-lg p-1 focus-visible:ring-2 ring-marigold-500', s.id === selectedId ? 'bg-surface-2 ring-2 ring-ink' : 'hover:bg-surface-2')}>
              <SlideCanvas slide={s} style={deck.style} fit={f} number={i + 1} total={deck.slides.length} projectId={projectId} className="rounded border border-line" />
              <span className="flex items-center gap-1 mt-1 text-xs">
                <span className="tabular-nums text-muted">{i + 1}</span>
                <span className="truncate flex-1">{s.title || TYPE_LABEL[s.type]}</span>
                {f && !f.ok && <span className="text-rose-signal font-medium">doesn’t fit</span>}
              </span>
            </button>
          </li>
        )
      })}
    </ol>
  )
}

function SlideProblems({ fit, slide }: { fit: DeckFit | null; slide: Slide }) {
  const f = fitOf(fit, slide.id)
  if (!f) return null
  const sizes = f.boxes.map((b) => `${FIELD_LABEL[b.field] ?? b.field} ${b.size} pt`).join(', ')
  return (
    <div className="text-sm">
      {f.problems.length > 0 && (
        <ul className="text-rose-signal space-y-0.5 mb-1">{f.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
      )}
      {sizes && <p className="text-muted">Sizes: {sizes}</p>}
    </div>
  )
}

const FIELD_LABEL: Record<string, string> = {
  title: 'title', subtitle: 'subtitle', bullets: 'bullets', term: 'term', text: 'text', table: 'table', caption: 'caption', code: 'code',
}

function IconButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} disabled={disabled} onClick={onClick}
      className="grid place-items-center size-9 rounded-md text-muted hover:text-ink hover:bg-surface-2 disabled:opacity-40 disabled:pointer-events-none">
      {children}
    </button>
  )
}

function AddSlide({ onAdd }: { onAdd: (t: SlideType) => void }) {
  const [type, setType] = useState<SlideType>('CONCEPT')
  return (
    <span className="inline-flex items-center gap-1">
      <Select aria-label="Type of slide to add" value={type} onChange={(e) => setType(e.target.value as SlideType)} className="h-9 w-44">
        {SLIDE_TYPES.filter((t) => t !== 'REFERENCES').map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
      </Select>
      <Button size="sm" variant="secondary" icon={<Plus className="size-4" />} onClick={() => onAdd(type)}>Add after</Button>
    </span>
  )
}

// ---- one slide ----------------------------------------------------------------------------------

function SlideEditor({ slide, projectId, change }: { slide: Slide; projectId: string; change: (p: Partial<Slide>) => void }) {
  const f = LAYOUT_FIELDS[slide.layout]
  const [picking, setPicking] = useState(false)
  const refs = slide.type === 'REFERENCES'
  return (
    <div className="space-y-4">
      <div className="grid sm:grid-cols-2 gap-4">
        <Field label="Type">
          {(fid) => (
            <Select id={fid} value={slide.type} disabled={refs} onChange={(e) => change({ type: e.target.value as SlideType })}>
              {SLIDE_TYPES.filter((t) => refs || t !== 'REFERENCES').map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
            </Select>
          )}
        </Field>
        <Field label="Layout">
          {(fid) => (
            <Select id={fid} value={slide.layout} disabled={refs} onChange={(e) => change(withLayout(slide, e.target.value as Layout))}>
              {LAYOUTS.map((l) => <option key={l} value={l}>{LAYOUT_LABEL[l]}</option>)}
            </Select>
          )}
        </Field>
      </div>
      <Field label="Title">{(fid) => <Input id={fid} maxLength={200} value={slide.title ?? ''} onChange={(e) => change({ title: e.target.value })} />}</Field>
      {f.includes('subtitle') && <Field label="Subtitle">{(fid) => <Input id={fid} maxLength={300} value={slide.subtitle ?? ''} onChange={(e) => change({ subtitle: e.target.value })} />}</Field>}
      {f.includes('bullets') && (
        <Field label="Bullet points" hint={refs ? 'The notes’ own references; only those can be listed' : 'One per line; up to 10'}>
          {(fid, hid) => (
            <Textarea id={fid} aria-describedby={hid} rows={Math.min(10, Math.max(3, (slide.bullets ?? []).length + 1))} readOnly={refs}
              value={(slide.bullets ?? []).join('\n')} onChange={(e) => change({ bullets: e.target.value.split('\n').slice(0, 10) })} />
          )}
        </Field>
      )}
      {f.includes('term') && <Field label="Term">{(fid) => <Input id={fid} maxLength={200} value={slide.term ?? ''} onChange={(e) => change({ term: e.target.value })} />}</Field>}
      {f.includes('text') && (
        <Field label={slide.layout === 'DEFINITION' ? 'Definition' : 'Text'}>
          {(fid) => <Textarea id={fid} rows={3} maxLength={2000} value={slide.text ?? ''} onChange={(e) => change({ text: e.target.value })} />}
        </Field>
      )}
      {f.includes('table') && <TableEditor slide={slide} change={change} />}
      {f.includes('figure') && (
        <div className="grid sm:grid-cols-[10rem_1fr] gap-4">
          <Field label="Kind">
            {(fid) => (
              <Select id={fid} value={slide.figure?.kind ?? 'MERMAID'} onChange={(e) => change({ figure: { kind: e.target.value as 'MERMAID' | 'MATH', source: slide.figure?.source ?? '' } })}>
                <option value="MERMAID">Diagram (Mermaid)</option>
                <option value="MATH">Equation (LaTeX)</option>
              </Select>
            )}
          </Field>
          <Field label="Source" hint="Drawn on the slide above as you type">
            {(fid, hid) => (
              <Textarea id={fid} aria-describedby={hid} rows={6} maxLength={20000} className="font-mono text-sm" value={slide.figure?.source ?? ''}
                onChange={(e) => change({ figure: { kind: slide.figure?.kind ?? 'MERMAID', source: e.target.value } })} />
            )}
          </Field>
        </div>
      )}
      {f.includes('image') && (
        <div className="space-y-2">
          <span className="text-sm font-medium">Image</span>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" icon={<ImageIcon className="size-4" />} onClick={() => setPicking(true)}>{slide.image ? 'Replace image' : 'Choose image'}</Button>
            {slide.image && <Button size="sm" variant="ghost" onClick={() => change({ image: null })}>Remove</Button>}
          </div>
          {slide.image && (
            <Field label="Image description" hint="For people who can’t see it">
              {(fid, hid) => <Input id={fid} aria-describedby={hid} maxLength={300} value={slide.image?.alt ?? ''} onChange={(e) => change({ image: { assetId: slide.image!.assetId, alt: e.target.value } })} />}
            </Field>
          )}
          <MediaPicker open={picking} kind="IMAGE" projectId={projectId} title="Choose an image from this project" onClose={() => setPicking(false)}
            onPick={(m) => { change({ image: { assetId: m.id, alt: slide.image?.alt ?? '' } }); setPicking(false) }} />
        </div>
      )}
      {f.includes('caption') && <Field label="Caption">{(fid) => <Input id={fid} maxLength={300} value={slide.caption ?? ''} onChange={(e) => change({ caption: e.target.value })} />}</Field>}
      {f.includes('code') && (
        <div className="grid sm:grid-cols-[10rem_1fr] gap-4">
          <Field label="Language">{(fid) => <Input id={fid} maxLength={30} value={slide.code?.language ?? ''} onChange={(e) => change({ code: { language: e.target.value, text: slide.code?.text ?? '' } })} />}</Field>
          <Field label="Code">
            {(fid) => <Textarea id={fid} rows={8} maxLength={4000} className="font-mono text-sm" value={slide.code?.text ?? ''} onChange={(e) => change({ code: { language: slide.code?.language ?? '', text: e.target.value } })} />}
          </Field>
        </div>
      )}
      <Field label="Speaker notes" hint="Said aloud while the slide shows; in the PowerPoint notes">
        {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={5} maxLength={5000} value={slide.notes ?? ''} onChange={(e) => change({ notes: e.target.value })} />}
      </Field>
      <Sources slide={slide} />
    </div>
  )
}

function TableEditor({ slide, change }: { slide: Slide; change: (p: Partial<Slide>) => void }) {
  const t = slide.table ?? { headers: ['', ''], rows: [['', '']] }
  const cols = Math.max(t.headers.length, ...t.rows.map((r) => r.length), 1)
  const pad = (r: string[]) => [...r, ...Array(cols - r.length).fill('')]
  const setCell = (row: number, col: number, v: string) =>
    change({ table: row < 0 ? { ...t, headers: pad(t.headers).map((h, j) => (j === col ? v : h)) } : { ...t, rows: t.rows.map((r, i) => (i === row ? pad(r).map((c, j) => (j === col ? v : c)) : r)) } })
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium mb-1">Table</legend>
      <div className="overflow-x-auto">
        <table className="text-sm border-separate border-spacing-1">
          <tbody>
            {[-1, ...t.rows.map((_, i) => i)].map((row) => (
              <tr key={row}>
                {Array.from({ length: cols }, (_, col) => (
                  <td key={col}>
                    <Input aria-label={row < 0 ? `Heading ${col + 1}` : `Row ${row + 1}, column ${col + 1}`} className={cn('h-9 min-w-[8rem]', row < 0 && 'font-semibold')}
                      maxLength={row < 0 ? 200 : 300} value={(row < 0 ? t.headers[col] : t.rows[row][col]) ?? ''} onChange={(e) => setCell(row, col, e.target.value)} />
                  </td>
                ))}
                {row >= 0 && (
                  <td><IconButton label={`Remove row ${row + 1}`} onClick={() => change({ table: { ...t, rows: t.rows.filter((_, i) => i !== row) } })}><Trash2 className="size-4" /></IconButton></td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="ghost" icon={<Plus className="size-4" />} disabled={t.rows.length >= 15} onClick={() => change({ table: { ...t, rows: [...t.rows, Array(cols).fill('')] } })}>Row</Button>
        <Button size="sm" variant="ghost" icon={<Plus className="size-4" />} disabled={cols >= 8} onClick={() => change({ table: { headers: [...pad(t.headers), ''], rows: t.rows.map((r) => [...pad(r), '']) } })}>Column</Button>
        <Button size="sm" variant="ghost" disabled={cols <= 1} onClick={() => change({ table: { headers: pad(t.headers).slice(0, cols - 1), rows: t.rows.map((r) => pad(r).slice(0, cols - 1)) } })}>Remove last column</Button>
      </div>
    </fieldset>
  )
}

function SlideReadOnly({ slide }: { slide: Slide }) {
  return (
    <div className="space-y-3">
      <div>
        <h3 className="text-sm font-medium">Speaker notes</h3>
        <p className="text-sm whitespace-pre-line mt-1">{slide.notes || <span className="text-muted">None</span>}</p>
      </div>
      {slide.figure && (
        <details>
          <summary className="text-sm cursor-pointer">{slide.figure.kind === 'MATH' ? 'Equation source' : 'Diagram source'}</summary>
          <pre className="text-xs bg-surface-2 rounded-lg p-3 mt-1 overflow-x-auto">{slide.figure.source}</pre>
        </details>
      )}
      <Sources slide={slide} />
    </div>
  )
}

function Sources({ slide }: { slide: Slide }) {
  if (!slide.sources?.length) return null
  return <p className="text-sm text-muted">From the notes: {slide.sources.join('; ')}</p>
}

// ---- one slide again, with AI -------------------------------------------------------------------

function RegenerateDialog({ resourceId, versionId, slide, deck, projectId, onUse, onClose }: {
  resourceId: string; versionId: string; slide: Slide; deck: Deck; projectId: string; onUse: (s: Slide) => void; onClose: () => void
}) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [guidance, setGuidance] = useState('')
  const [proposal, setProposal] = useState<Proposal | null>(null)
  const redo = useRegenerateSlide(resourceId)
  useDefaultAccount(usable, accountId, setAccountId)
  const n = deck.slides.findIndex((s) => s.id === slide.id) + 1
  const pf = proposal ? fitOf(proposal.fit, proposal.slide.id) : undefined
  return (
    <Dialog
      open
      size="xl"
      title={`Redo slide ${n} with AI`}
      onClose={onClose}
      footer={
        proposal ? (
          <>
            <Button variant="ghost" onClick={onClose}>Keep the current slide</Button>
            <Button variant="secondary" loading={redo.isPending} onClick={() => setProposal(null)}>Try again</Button>
            <Button variant="primary" onClick={() => onUse(proposal.slide)}>Use this slide</Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>Cancel</Button>
            <Button variant="primary" loading={redo.isPending} disabled={!accountId || !model}
              onClick={() => redo.mutate({ slideId: slide.id, versionId, accountId, model, guidance: guidance.trim() || undefined },
                { onSuccess: setProposal, onError: (e) => toast.error(e.message) })}>
              Write a new version
            </Button>
          </>
        )
      }
    >
      {proposal ? (
        <div className="grid md:grid-cols-2 gap-4">
          <figure>
            <figcaption className="text-sm text-muted mb-1">Now</figcaption>
            <SlideCanvas slide={slide} style={deck.style} fit={fitOf(deck.fit, slide.id)} number={n} total={deck.slides.length} projectId={projectId} className="rounded-lg border border-line" />
          </figure>
          <figure>
            <figcaption className="text-sm text-muted mb-1">New{pf && !pf.ok ? ': its text doesn’t fit yet' : ''}</figcaption>
            <SlideCanvas slide={proposal.slide} style={deck.style} fit={pf} number={n} total={deck.slides.length} projectId={projectId} className="rounded-lg border border-line" />
            {proposal.slide.notes && <p className="text-sm mt-2 whitespace-pre-line"><span className="font-medium">Speaker notes: </span>{proposal.slide.notes}</p>}
          </figure>
        </div>
      ) : (
        <div className="space-y-4">
          <p className="text-muted">The AI rewrites this slide from the approved notes. You’ll see it beside the current one and choose; nothing changes until you save the draft.</p>
          {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account: 1 AI call." />}
          <Field label="What to change (optional)" hint="For example: “Use a table” or “Simpler words”">
            {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={2000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
          </Field>
        </div>
      )}
    </Dialog>
  )
}
