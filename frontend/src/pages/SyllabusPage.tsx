import { ChevronLeft, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { SourceChip, TreeEditor } from '../components/TreeEditor'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, Textarea } from '../components/ui'
import { useReviewPage } from '../components/useReviewPage'
import { formatRelative } from '../lib/format'
import { useProject } from '../lib/queries'
import {
  ASSESSMENT_KINDS,
  ASSESSMENT_LABEL,
  isRunning,
  newId,
  useAnalysis,
  useCancelAnalysis,
  useStartAnalysis,
  useWriteByHand,
  type AnalysisOverview,
  type AssessmentKind,
  type Syllabus,
} from '../lib/syllabus'
import { toast } from '../stores/ui'

/** /projects/:id/syllabus — the syllabus report, its versions and its review. */
export function SyllabusPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const page = useReviewPage<Syllabus>(id, 'syllabus')
  const analysis = useAnalysis(id)
  const [analyseOpen, setAnalyseOpen] = useState(false)

  if (project.isPending || page.loading || analysis.isPending) return <Spinner label="Loading the syllabus" />
  if (project.error || page.error || analysis.error) return <ErrorNotice error={project.error ?? page.error ?? analysis.error} onRetry={() => page.state.refetch()} />
  const p = project.data
  const state = page.state.data!
  const job = analysis.data.job

  const header = (
    <>
      <Link to={`/projects/${p.id}`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        {p.name}
      </Link>
      <PageHeader title="Syllabus" description={[p.subject?.moduleCode, p.subject?.subjectName].filter(Boolean).join(', ') || p.name} />
      {job && <JobNotice projectId={id} job={job} canEdit={state.canEdit} />}
    </>
  )

  if (!state.resource || !page.detail || !page.content) {
    return (
      <>
        {header}
        {state.canEdit ? (
          isRunning(job) ? null : (
            <section className="max-w-3xl">
              <h2 className="text-lg font-semibold mb-1">Analyse the course documents</h2>
              <p className="text-muted mb-4">
                Your AI account reads the documents you choose and drafts the syllabus: its structure, learning outcomes, assessments, and
                where documents disagree. Every item links to the page it came from. Nothing is final until a reviewer approves it.
              </p>
              <AnalyseForm projectId={id} overview={analysis.data} />
              <p className="text-sm text-muted mt-6">
                Or <WriteByHand projectId={id} />.
              </p>
            </section>
          )
        ) : (
          <EmptyState title="No syllabus yet" body="The project's owners and editors haven't drafted the syllabus yet." />
        )}
      </>
    )
  }

  const d = page.detail
  const s = page.content
  const editable = page.editable
  const set = (patch: Partial<Syllabus>) => page.setContent({ ...s, ...patch })

  return (
    <>
      {header}
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-10">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-10 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={page.discard}>
                Discard
              </Button>
              <Button
                variant="primary"
                loading={page.saving}
                onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}
              >
                Save draft
              </Button>
            </div>
          )}
          {page.reviewerEditing && (
            <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm">
              You're editing as the reviewer. Approving with your edits saves them as a new, approved version; the version under review is kept.
            </p>
          )}

          <section aria-labelledby="sum">
            <h2 id="sum" className="text-lg font-semibold mb-2">Summary</h2>
            {editable ? (
              <Textarea aria-labelledby="sum" rows={3} maxLength={4000} value={s.summary ?? ''} onChange={(e) => set({ summary: e.target.value })} />
            ) : (
              <p className={s.summary ? '' : 'text-muted'}>{s.summary || 'No summary.'}</p>
            )}
          </section>

          <section aria-labelledby="structure">
            <h2 id="structure" className="text-lg font-semibold mb-2">Structure</h2>
            <TreeEditor
              items={s.structure}
              onChange={(structure) => set({ structure })}
              editable={editable}
              docs={s.documents}
              emptyText="No chapters or topics yet."
            />
          </section>

          <Outcomes s={s} set={set} editable={editable} />
          <Assessments s={s} set={set} editable={editable} />
          <Contradictions s={s} set={set} editable={editable} />

          {s.documents.length > 0 && (
            <section aria-labelledby="docs">
              <h2 id="docs" className="text-lg font-semibold mb-2">Documents read</h2>
              <ul className="space-y-1">
                {s.documents.map((doc) => (
                  <li key={doc.versionId}>
                    <span className="text-muted">{doc.label}</span>{' '}
                    <Link className="hover:underline" to={`/documents/${doc.documentId}?version=${doc.versionId}`}>
                      {doc.title}
                    </Link>
                    {doc.versionNo && doc.versionNo > 1 && <span className="text-muted">, version {doc.versionNo}</span>}
                  </li>
                ))}
              </ul>
              {s.analysis?.citations != null && (
                <p className="text-sm text-muted mt-3">
                  Analysed with {s.analysis.model}. {s.analysis.citationsFound} of {s.analysis.citations} citations were found word for word on their page
                  {s.analysis.citationsRemoved ? `; ${s.analysis.citationsRemoved} pointing at pages that don't exist were removed` : ''}.
                </p>
              )}
            </section>
          )}
        </div>

        <aside className="space-y-6 lg:sticky lg:top-6">
          <ReviewPanel
            detail={d}
            kindLabel="Syllabus"
            dirty={page.dirty}
            edited={s}
            saveFirst={page.saveDraft}
            editing={page.reviewerEditing}
            onReviewerEdit={(on) => {
              page.setReviewerEditing(on)
              if (!on) page.discard()
            }}
            onEdit={page.startDraft}
          />
          {state.canEdit && !isRunning(job) && (
            <Button variant="ghost" onClick={() => setAnalyseOpen(true)}>
              Analyse documents again
            </Button>
          )}
          <section aria-labelledby="versions-h">
            <h2 id="versions-h" className="font-semibold mb-2">Versions</h2>
            <VersionList versions={state.versions} current={d.version.id} approvedId={state.approvedVersionId} onPick={page.pick} />
          </section>
          <section aria-labelledby="history-h">
            <h2 id="history-h" className="font-semibold mb-2">History</h2>
            <History resourceId={state.resource.id} />
          </section>
        </aside>
      </div>

      {analyseOpen && (
        <Dialog open title="Analyse the documents again" onClose={() => setAnalyseOpen(false)}>
          <p className="text-muted mb-4">
            The result becomes a new draft version. {state.approvedVersionNo ? `Version ${state.approvedVersionNo} stays approved until a new one is, but the topic list will be marked outdated.` : ''}
          </p>
          <AnalyseForm projectId={id} overview={analysis.data} onStarted={() => setAnalyseOpen(false)} />
        </Dialog>
      )}
    </>
  )
}

// ---- the analysis -------------------------------------------------------------------------------

function AnalyseForm({ projectId, overview, onStarted }: { projectId: string; overview: AnalysisOverview; onStarted?: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [chosen, setChosen] = useState<string[]>([])
  const [guidance, setGuidance] = useState('')
  const start = useStartAnalysis(projectId)
  useDefaultAccount(usable, accountId, setAccountId)
  const docs = overview.documents
  const chars = docs.filter((d) => chosen.includes(d.documentId)).reduce((n, d) => n + d.chars, 0)
  const tooMuch = chars > overview.maxChars

  if (docs.length === 0) {
    return (
      <EmptyState
        title="No documents to analyse yet"
        body="Add the module descriptor, handbook or lecture notes in Course documents first. They're read page by page, then you can analyse them here."
      />
    )
  }
  return (
    <div className="space-y-5">
      <fieldset>
        <legend className="font-medium mb-2">Documents to read</legend>
        <ul className="space-y-1">
          {docs.map((doc) => (
            <li key={doc.documentId}>
              <label className="flex items-center gap-3 py-1">
                <input
                  type="checkbox"
                  className="size-4 accent-marigold-600"
                  checked={chosen.includes(doc.documentId)}
                  onChange={(e) => setChosen((c) => (e.target.checked ? [...c, doc.documentId] : c.filter((x) => x !== doc.documentId)))}
                />
                <span className="flex-1 min-w-0 break-words">{doc.title}</span>
                <span className="text-sm text-muted shrink-0 tabular">
                  {doc.pages} {doc.pages === 1 ? 'page' : 'pages'}
                </span>
              </label>
            </li>
          ))}
        </ul>
        <p className={tooMuch ? 'text-sm text-rose-signal mt-2' : 'text-sm text-muted mt-2'}>
          {chosen.length === 0
            ? 'Choose the documents that describe the module: usually the module descriptor and the handbook.'
            : `${chars.toLocaleString()} of ${overview.maxChars.toLocaleString()} characters${tooMuch ? '. Too much text for one analysis; choose fewer documents.' : ''}`}
        </p>
      </fieldset>
      {isPending ? (
        <Spinner label="Loading AI accounts" />
      ) : (
        <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account; the cost depends on how much text you choose." />
      )}
      <Field label="Guidance (optional)" hint="For example: “Use the 2026 handbook where documents differ.”">
        {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
      </Field>
      <Button
        variant="primary"
        loading={start.isPending}
        disabled={chosen.length === 0 || tooMuch || !accountId || !model}
        onClick={() =>
          start.mutate(
            { documentIds: chosen, accountId, model, guidance: guidance.trim() || undefined },
            { onSuccess: () => { toast.success('Analysis started'); onStarted?.() }, onError: (e) => toast.error(e.message) },
          )
        }
      >
        Analyse documents
      </Button>
    </div>
  )
}

function JobNotice({ projectId, job, canEdit }: { projectId: string; job: NonNullable<AnalysisOverview['job']>; canEdit: boolean }) {
  const cancel = useCancelAnalysis(projectId)
  if (job.status === 'SUCCEEDED' || job.status === 'CANCELLED') return null
  const running = isRunning(job)
  return (
    <div className={'mb-6 rounded-xl border px-4 py-3 text-sm flex flex-wrap items-center gap-3 ' + (running ? 'border-line bg-surface-2' : 'border-rose-signal/40')} role="status">
      <p className="flex-1 min-w-0">
        {running ? (
          <>
            {job.status === 'QUEUED' ? 'Waiting to analyse' : 'Analysing'} {job.documents.length} {job.documents.length === 1 ? 'document' : 'documents'}
            {job.model ? ` with ${job.model}` : ''}
            {job.startedBy ? `, started by ${job.startedBy}` : ''} {formatRelative(job.createdAt)}. This takes a minute or two
            {job.error ? `. ${job.error}` : '.'}
          </>
        ) : (
          <>The last analysis failed: {job.error ?? 'unknown error'}.</>
        )}
      </p>
      {running && canEdit && job.status === 'QUEUED' && (
        <Button size="sm" variant="ghost" loading={cancel.isPending} onClick={() => cancel.mutate(job.id, { onError: (e) => toast.error(e.message) })}>
          Cancel
        </Button>
      )}
    </div>
  )
}

function WriteByHand({ projectId }: { projectId: string }) {
  const write = useWriteByHand(projectId)
  return (
    <button type="button" className="underline underline-offset-2 hover:text-ink" disabled={write.isPending} onClick={() => write.mutate(undefined, { onError: (e) => toast.error(e.message) })}>
      write the syllabus by hand
    </button>
  )
}

// ---- sections -----------------------------------------------------------------------------------

type Part = { s: Syllabus; set: (p: Partial<Syllabus>) => void; editable: boolean }

function Outcomes({ s, set, editable }: Part) {
  if (!editable && s.outcomes.length === 0) return <Section title="Learning outcomes"><p className="text-muted">None listed.</p></Section>
  const change = (i: number, patch: Partial<Syllabus['outcomes'][number]>) => set({ outcomes: s.outcomes.map((o, j) => (j === i ? { ...o, ...patch } : o)) })
  return (
    <Section title="Learning outcomes">
      <ol className="space-y-3">
        {s.outcomes.map((o, i) => (
          <li key={o.id} className="flex gap-3 items-start">
            {editable ? (
              <>
                <Input aria-label="Outcome code" className="w-20" maxLength={20} value={o.code ?? ''} onChange={(e) => change(i, { code: e.target.value })} />
                <Textarea aria-label="Learning outcome" rows={2} className="flex-1" maxLength={2000} value={o.text} onChange={(e) => change(i, { text: e.target.value })} />
                <RemoveButton label="Remove this outcome" onClick={() => set({ outcomes: s.outcomes.filter((_, j) => j !== i) })} />
              </>
            ) : (
              <div>
                <p>
                  {o.code && <span className="font-medium">{o.code} </span>}
                  {o.text}
                </p>
                <Sources item={o} s={s} />
              </div>
            )}
          </li>
        ))}
      </ol>
      {editable && <AddButton onClick={() => set({ outcomes: [...s.outcomes, { id: newId(), text: '' }] })}>Add an outcome</AddButton>}
    </Section>
  )
}

function Assessments({ s, set, editable }: Part) {
  if (!editable && s.assessments.length === 0) return <Section title="Assessments"><p className="text-muted">None listed.</p></Section>
  const change = (i: number, patch: Partial<Syllabus['assessments'][number]>) => set({ assessments: s.assessments.map((a, j) => (j === i ? { ...a, ...patch } : a)) })
  return (
    <Section title="Assessments">
      <ul className="space-y-3">
        {s.assessments.map((a, i) => (
          <li key={a.id}>
            {editable ? (
              <div className="grid gap-2 sm:grid-cols-[1fr_10rem_7rem_auto] items-start">
                <Input aria-label="Assessment name" maxLength={300} value={a.title} onChange={(e) => change(i, { title: e.target.value })} />
                <Select aria-label="Kind" value={a.kind} onChange={(e) => change(i, { kind: e.target.value as AssessmentKind })}>
                  {ASSESSMENT_KINDS.map((k) => (
                    <option key={k} value={k}>
                      {ASSESSMENT_LABEL[k]}
                    </option>
                  ))}
                </Select>
                <Input
                  aria-label="Weight in percent"
                  type="number"
                  min={0}
                  max={100}
                  placeholder="Weight %"
                  value={a.weight ?? ''}
                  onChange={(e) => change(i, { weight: e.target.value === '' ? null : Number(e.target.value) })}
                />
                <RemoveButton label="Remove this assessment" onClick={() => set({ assessments: s.assessments.filter((_, j) => j !== i) })} />
              </div>
            ) : (
              <div>
                <p>
                  <span className="font-medium">{a.title}</span>
                  <span className="text-muted">
                    , {ASSESSMENT_LABEL[a.kind] ?? a.kind}
                    {a.weight != null && `, ${a.weight}%`}
                  </span>
                </p>
                {a.details && <p className="text-muted">{a.details}</p>}
                <Sources item={a} s={s} />
              </div>
            )}
          </li>
        ))}
      </ul>
      {editable && <AddButton onClick={() => set({ assessments: [...s.assessments, { id: newId(), title: '', kind: 'EXAM' }] })}>Add an assessment</AddButton>}
    </Section>
  )
}

function Contradictions({ s, set, editable }: Part) {
  if (s.contradictions.length === 0) {
    return (
      <Section title="Where documents disagree">
        <p className="text-muted">Nothing found.</p>
      </Section>
    )
  }
  return (
    <Section title="Where documents disagree">
      <ul className="space-y-4">
        {s.contradictions.map((c, i) => (
          <li key={c.id} className="rounded-xl border border-line px-4 py-3">
            <p>{c.description}</p>
            <Sources item={c} s={s} />
            {editable ? (
              <Field label="Resolution" hint="Needed before approval: which document is right, or how to treat it.">
                {(fid, hid) => (
                  <Textarea
                    id={fid}
                    aria-describedby={hid}
                    rows={2}
                    maxLength={2000}
                    value={c.resolution ?? ''}
                    onChange={(e) => set({ contradictions: s.contradictions.map((x, j) => (j === i ? { ...x, resolution: e.target.value } : x)) })}
                  />
                )}
              </Field>
            ) : (
              <p className={c.resolution ? 'mt-2' : 'mt-2 text-rose-signal'}>
                {c.resolution ? <><span className="font-medium">Resolution:</span> {c.resolution}</> : 'Not resolved yet.'}
              </p>
            )}
          </li>
        ))}
      </ul>
    </Section>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  const hid = title.toLowerCase().replace(/\W+/g, '-')
  return (
    <section aria-labelledby={hid}>
      <h2 id={hid} className="text-lg font-semibold mb-3">
        {title}
      </h2>
      {children}
    </section>
  )
}

function Sources({ item, s }: { item: { sources?: Syllabus['outcomes'][number]['sources'] }; s: Syllabus }) {
  if (!item.sources?.length) return null
  return (
    <div className="flex flex-wrap gap-x-3 mt-1">
      {item.sources.map((src, i) => (
        <SourceChip key={i} source={src} docs={s.documents} />
      ))}
    </div>
  )
}

function AddButton({ onClick, children }: { onClick: () => void; children: string }) {
  return (
    <Button className="mt-3" size="sm" variant="ghost" icon={<Plus className="size-4" />} onClick={onClick}>
      {children}
    </Button>
  )
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" aria-label={label} onClick={onClick} className="mt-2 rounded-md p-1.5 text-faint hover:text-rose-signal hover:bg-surface-2">
      <Trash2 className="size-4" />
    </button>
  )
}
