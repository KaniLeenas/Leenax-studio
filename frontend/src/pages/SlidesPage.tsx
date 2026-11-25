import { Check, ChevronLeft, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { type Level } from '../lib/materials'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import { LEVEL_LABEL, STYLE_KEYS, STYLES, useGenerateDeck, useSlidesOverview, type StyleKey, type TopicSlides } from '../lib/slides'
import { toast } from '../stores/ui'

/** /projects/:id/slides — presentations for each approved topic, made after the notes, QA and SEO are approved. */
export function SlidesPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useSlidesOverview(id)
  const [making, setMaking] = useState<TopicSlides | null>(null)

  if (project.isPending || data.isPending) return <Spinner label="Loading presentations" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const p = project.data
  const d = data.data

  return (
    <>
      <Link to={`/projects/${p.id}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <PageHeader
        title="Presentations"
        description="Slides made from approved notes, once the topic’s academic QA report and SEO report are approved. Edit them here, then download PowerPoint or PDF."
      />
      {!d.topicsApproved || d.topics.length === 0 ? (
        <EmptyState title="The topic list isn't approved yet" body="Presentations are made per approved topic."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/topics`}>Go to topics</Link>} />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {d.topics.map((t) => {
            const ready = t.approvedNotes.length > 0 && t.qaApproved && t.seoApproved
            return (
              <li key={t.topicId} className="py-4 grid gap-3 lg:grid-cols-[minmax(0,18rem)_1fr_auto] lg:items-start">
                <div style={{ paddingLeft: `${(t.depth - 1) * 1.25}rem` }}>
                  <p className={cn('break-words', t.depth === 1 ? 'font-semibold' : 'font-medium')}>{t.title}</p>
                  <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted" aria-label="Approvals">
                    <Gate ok={t.approvedNotes.length > 0} label={t.approvedNotes.length > 0 ? `Notes: ${t.approvedNotes.map((l) => LEVEL_LABEL[l as Level] ?? l).join(', ')}` : 'Notes'} />
                    <Gate ok={t.qaApproved} label="QA report" />
                    <Gate ok={t.seoApproved} label="SEO report" />
                  </ul>
                </div>
                <div className="text-sm min-w-0 space-y-1">
                  {t.decks.length === 0 ? (
                    <p className="text-muted">{ready ? 'No presentation yet' : 'Needs the approvals on the left'}</p>
                  ) : (
                    t.decks.map((k) => (
                      <div key={k.level}>
                        {k.running ? (
                          <p className="text-muted" role="status">{LEVEL_LABEL[k.level as Level] ?? k.level}: making the slides… about a minute.</p>
                        ) : k.resourceId ? (
                          <Link to={`/slides/${k.resourceId}`} className="inline-flex flex-wrap items-center gap-2 hover:underline">
                            {LEVEL_LABEL[k.level as Level] ?? k.level} slides, version {k.versionNo}, {k.slides} slides
                            {k.status && <StatusPill tone={statusTone(k.status as ReviewStatus)}>{STATUS_LABEL[k.status as ReviewStatus]}</StatusPill>}
                          </Link>
                        ) : null}
                        {k.error && !k.running && <p className="text-rose-signal">{LEVEL_LABEL[k.level as Level] ?? k.level}: last attempt failed. {k.error}</p>}
                      </div>
                    ))
                  )}
                </div>
                {d.canEdit && (
                  <Button size="sm" variant={t.decks.length ? 'ghost' : 'secondary'} disabled={!ready} onClick={() => setMaking(t)}
                    title={ready ? undefined : 'Approve the notes, the QA report and the SEO report first'}>
                    {t.decks.length ? 'Make slides again' : 'Make slides'}
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {making && <MakeDialog projectId={id} topic={making} onClose={() => setMaking(null)} />}
    </>
  )
}

function Gate({ ok, label }: { ok: boolean; label: string }) {
  return (
    <li className={cn('inline-flex items-center gap-1', ok ? 'text-ink' : 'text-muted')}>
      {ok ? <Check className="size-3.5 text-emerald-600" aria-hidden /> : <X className="size-3.5" aria-hidden />}
      <span>{label}{ok ? ' approved' : ' not approved'}</span>
    </li>
  )
}

function MakeDialog({ projectId, topic, onClose }: { projectId: string; topic: TopicSlides; onClose: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [level, setLevel] = useState(topic.approvedNotes[0] ?? '')
  const [style, setStyle] = useState<StyleKey>('LECTURE')
  const [count, setCount] = useState(14)
  const [guidance, setGuidance] = useState('')
  const start = useGenerateDeck(projectId)
  useDefaultAccount(usable, accountId, setAccountId)
  const existing = topic.decks.find((k) => k.level === level)
  const inReview = existing?.status === 'IN_REVIEW'
  const badCount = !(count >= 5 && count <= 40)
  return (
    <Dialog
      open
      title={`Make slides: ${topic.title}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={start.isPending} disabled={!accountId || !model || !level || inReview || badCount}
            onClick={() => start.mutate(
              { topicId: topic.topicId, level, accountId, model, style, slides: count, guidance: guidance.trim() || undefined },
              { onSuccess: () => { toast.success('Making the slides'); onClose() }, onError: (e) => toast.error(e.message) },
            )}>
            Make slides
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">
        The AI writes the slides and speaker notes from the approved notes only, reusing their diagrams. Leenax adds the notes’ own references and
        measures every text box. Everything lands in a draft for you to edit and approve.
        {existing?.status === 'DRAFT' && ' The current draft will be rewritten.'}
      </p>
      <div className="space-y-4">
        <div className="grid sm:grid-cols-[1fr_8rem] gap-4">
          <Field label="From the notes for" error={inReview ? 'That presentation is in review' : undefined}>
            {(fid) => (
              <Select id={fid} value={level} onChange={(e) => setLevel(e.target.value)}>
                {topic.approvedNotes.map((l) => <option key={l} value={l}>{LEVEL_LABEL[l as Level] ?? l}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Slides" hint="About; 5 to 40" error={badCount ? 'Use 5 to 40' : undefined}>
            {(fid, hid) => <Input id={fid} aria-describedby={hid} type="number" min={5} max={40} value={count} onChange={(e) => setCount(Number(e.target.value))} />}
          </Field>
        </div>
        <StylePicker value={style} onChange={setStyle} />
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account: 1 AI call." />}
        <Field label="Guidance (optional)" hint="For example: “More worked examples; first-year students.”">
          {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={2000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
      </div>
    </Dialog>
  )
}

/** The 7 styles as small slide swatches. */
export function StylePicker({ value, onChange, disabled }: { value: StyleKey; onChange: (s: StyleKey) => void; disabled?: boolean }) {
  return (
    <fieldset disabled={disabled}>
      <legend className="font-medium mb-2">Style</legend>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {STYLE_KEYS.map((k) => {
          const s = STYLES[k]
          return (
            <label key={k} className={cn('rounded-lg border p-1.5 cursor-pointer text-xs has-[:focus-visible]:ring-2 ring-marigold-500',
              value === k ? 'border-ink' : 'border-line hover:border-muted', disabled && 'cursor-default opacity-70')}>
              <input type="radio" name="slide-style" className="sr-only" checked={value === k} onChange={() => onChange(k)} />
              <span className="block aspect-video rounded overflow-hidden relative" style={{ background: s.bg }} aria-hidden>
                {s.band && <span className="absolute inset-x-0 top-0 h-[22%]" style={{ background: s.band }} />}
                <span className="absolute left-[8%] top-[9%] h-[7%] w-[45%] rounded-sm" style={{ background: s.title }} />
                {s.bar && <span className="absolute left-[8%] top-[21%] h-[2%] w-[14%]" style={{ background: s.accent }} />}
                {[38, 52, 66].map((y) => (
                  <span key={y} className="absolute left-[8%] h-[5%] w-[60%] rounded-sm opacity-80" style={{ top: `${y}%`, background: s.text }} />
                ))}
              </span>
              <span className="block mt-1 truncate">{s.name}</span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
