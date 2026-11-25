import { AlertTriangle, CheckCircle2, ChevronLeft, Circle, Loader2, Plus, Trash2, XCircle, Zap } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import { DIFFICULTIES, LEVEL_LABEL, LEVELS, STYLE_LABEL, STYLES, type Level } from '../lib/materials'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import {
  BASIS_LABEL,
  BLOOM,
  BLOOM_LABEL,
  clock,
  EXAMPLE_LABEL,
  jobLive,
  nextId,
  planTotals,
  SHOWS,
  SHOWS_LABEL,
  stepLink,
  useNextSteps,
  useRunAnalysis,
  useSaveAutomation,
  useTopicAnalysis,
  waitingFor,
  type AnalysisTopic,
  type AutomationSettings,
  type Item,
  type Source,
  type Step,
  type TopicAnalysis,
} from '../lib/topicAnalysis'
import { toast } from '../stores/ui'

const JOB_LABEL: Record<string, string> = { QUEUED: 'Waiting to start', RUNNING: 'Running', FAILED: 'Failed' }

function Status({ status }: { status: string }) {
  if (status === 'NONE') return <span className="text-sm text-muted">Not started</span>
  return <StatusPill tone={statusTone(status as ReviewStatus)}>{STATUS_LABEL[status as ReviewStatus] ?? status}</StatusPill>
}

// =====================================================================================================
// /projects/:id/analysis — automation settings and every topic's analysis
// =====================================================================================================

export function TopicAnalysisProjectPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useTopicAnalysis(id)
  const run = useRunAnalysis(id)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [runAll, setRunAll] = useState(false)

  if (project.isPending || data.isPending) return <Spinner label="Loading topic analysis" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const p = project.data
  const d = data.data
  const s = d.settings
  const missing = d.topics.filter((t) => !t.resourceId && !jobLive(t.job)).length

  return (
    <>
      <Link to={`/projects/${p.id}`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        {p.name}
      </Link>
      <PageHeader
        title="Topic analysis"
        description="Before notes, questions and the lesson video are written, each topic is analysed: objectives, key concepts, misconceptions, a question plan and a video plan. You review and approve each analysis; the questions and the video follow its plan."
        actions={d.canEdit && d.topicsApproved ? (
          <>
            <Button variant="ghost" onClick={() => setSettingsOpen(true)}>Automation settings</Button>
            {missing > 0 && <Button variant="primary" onClick={() => setRunAll(true)}>Analyse {missing} topic{missing === 1 ? '' : 's'}</Button>}
          </>
        ) : undefined}
      />

      <AutomationSummary s={s} canEdit={d.canEdit} onEdit={() => setSettingsOpen(true)} />

      {!d.topicsApproved ? (
        <EmptyState
          title="The topic list isn't approved yet"
          body="Topics are analysed from the approved topic list. When it's approved, analysis starts automatically if automation is on, or you can start it here."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/topics`}>Go to the topic list</Link>}
        />
      ) : d.topics.length === 0 ? (
        <EmptyState title="No topics selected" body="Select topics in the topic list to analyse them." />
      ) : (
        <>
          <p className="text-sm text-muted mb-3">
            From topic list version {d.topicsVersionNo}. {d.topics.length} topic{d.topics.length === 1 ? '' : 's'}
            {Object.entries(d.counts).length > 0 && `: ${Object.entries(d.counts).map(([k, n]) => `${n} ${k === 'NONE' ? 'not analysed' : (STATUS_LABEL[k as ReviewStatus] ?? k).toLowerCase()}`).join(', ')}`}.
          </p>
          <ul className="divide-y divide-line border-y border-line">
            {d.topics.map((t) => <TopicLine key={t.topicId} projectId={id} t={t} s={s} canEdit={d.canEdit} />)}
          </ul>
        </>
      )}

      {settingsOpen && <SettingsDialog projectId={id} s={s} topicCount={d.topics.length} onClose={() => setSettingsOpen(false)} />}
      {runAll && (
        <RunDialog
          s={s}
          title={`Analyse ${missing} topic${missing === 1 ? '' : 's'}`}
          count={missing}
          pending={run.isPending}
          onRun={(req) => run.mutate(req, {
            onSuccess: (r) => { toast.success(`${r.queued} analysis job${r.queued === 1 ? '' : 's'} started${r.skipped ? `, ${r.skipped} skipped` : ''}`); setRunAll(false) },
            onError: (e) => toast.error(e.message),
          })}
          onClose={() => setRunAll(false)}
        />
      )}
    </>
  )
}

function AutomationSummary({ s, canEdit, onEdit }: { s: AutomationSettings; canEdit: boolean; onEdit: () => void }) {
  return (
    <section aria-label="Automation" className={cn('rounded-2xl border px-5 py-4 mb-6 flex flex-wrap gap-4 items-start', s.enabled ? 'border-marigold-500/60' : 'border-line')}>
      <Zap className={cn('size-6 shrink-0 mt-0.5', s.enabled ? 'text-marigold-600' : 'text-faint')} aria-hidden />
      <div className="flex-1 min-w-[16rem] text-sm">
        {s.enabled ? (
          <>
            <p className="font-semibold text-base">Automation is on</p>
            <p className="text-muted mt-0.5">
              Uses {s.accountName ?? 'the chosen account'}, {s.model}. When the topic list is approved, each topic is analysed. When an analysis is approved:
              {' '}{[s.autoNotes && `${LEVEL_LABEL[s.notesLevel]} notes are written`, s.autoMcq && `${s.mcqCount} questions follow once the notes are approved`,
                s.autoSlides && `the presentation (the ${s.videoMinutes}-minute video's script) follows once the notes, QA and SEO reports are approved`].filter(Boolean).join('; ') || 'nothing else starts'}.
            </p>
            <p className="text-muted mt-1">Every step is a draft a person reviews and approves. Nothing replaces work that already exists. AI use is charged to that account.</p>
          </>
        ) : (
          <>
            <p className="font-semibold text-base">Automation is off</p>
            <p className="text-muted mt-0.5">Start analyses here; notes, questions and presentations are started on their own pages. Turn automation on to start each step when the one before is approved.</p>
          </>
        )}
        {s.updatedByName && <p className="text-xs text-faint mt-1">Changed by {s.updatedByName} {s.updatedAt ? formatRelative(s.updatedAt) : ''}</p>}
      </div>
      {canEdit && <Button size="sm" variant="ghost" onClick={onEdit}>Change</Button>}
    </section>
  )
}

function TopicLine({ projectId, t, s, canEdit }: { projectId: string; t: AnalysisTopic; s: AutomationSettings; canEdit: boolean }) {
  const run = useRunAnalysis(projectId)
  const [again, setAgain] = useState(false)
  const live = jobLive(t.job)
  return (
    <li className="py-4 grid gap-3 lg:grid-cols-[minmax(0,18rem)_minmax(0,1fr)] lg:items-start">
      <div className="min-w-0">
        <p className="font-medium break-words">{t.title}</p>
        {t.path && <p className="text-sm text-muted">{t.path}</p>}
        <div className="mt-1 flex flex-wrap items-center gap-2 text-sm">
          {t.resourceId ? (
            <Link to={`/topic-analysis/${t.resourceId}`} className="inline-flex items-center gap-2 hover:underline">
              Analysis, version {t.versionNo} <Status status={t.status} />
            </Link>
          ) : !live && <span className="text-muted">Not analysed</span>}
          {t.approvedNo != null && t.approvedNo !== t.versionNo && <span className="text-muted">(version {t.approvedNo} approved)</span>}
        </div>
        {live && <p className="text-sm mt-1 inline-flex items-center gap-1.5"><Loader2 className="size-3.5 animate-spin" aria-hidden />{JOB_LABEL[t.job!]}{t.jobAuto ? ' (started automatically)' : ''}</p>}
        {t.job === 'FAILED' && <p className="text-sm text-rose-signal mt-1">Analysis failed: {t.jobError}</p>}
        {canEdit && !live && t.status !== 'IN_REVIEW' && (
          <Button size="sm" variant="ghost" className="mt-1 -ml-3" onClick={() => setAgain(true)}>{t.resourceId ? 'Analyse again' : 'Analyse'}</Button>
        )}
      </div>
      {t.approvedNo != null ? <Steps projectId={projectId} steps={t.next} s={s} /> : <p className="text-sm text-muted">Notes, questions and the video plan follow once the analysis is approved.</p>}
      {again && (
        <RunDialog
          s={s}
          title={`Analyse “${t.title}”`}
          count={1}
          note={t.status === 'DRAFT' ? 'This replaces the current draft.' : t.resourceId ? 'This makes a new version; the approved one stays until the new one is approved.' : undefined}
          pending={run.isPending}
          onRun={(req) => run.mutate({ ...req, topicIds: [t.topicId] }, {
            onSuccess: (r) => { if (r.queued) toast.success('Analysis started'); else toast.error(r.notes[0] ?? 'Nothing started'); setAgain(false) },
            onError: (e) => toast.error(e.message),
          })}
          onClose={() => setAgain(false)}
        />
      )}
    </li>
  )
}

function Steps({ projectId, steps, s }: { projectId: string; steps: Step[]; s: AutomationSettings }) {
  return (
    <ol className="space-y-1.5" aria-label="What follows the analysis">
      {steps.map((st) => {
        const live = jobLive(st.job)
        const Icon = st.status === 'APPROVED' ? CheckCircle2 : live ? Loader2 : st.job === 'FAILED' ? XCircle : Circle
        return (
          <li key={st.kind} className="flex gap-2 text-sm">
            <Icon className={cn('size-4 shrink-0 mt-0.5', st.status === 'APPROVED' ? 'text-emerald-600' : st.job === 'FAILED' ? 'text-rose-signal' : 'text-faint', live && 'animate-spin')} aria-hidden />
            <span className="min-w-0">
              <Link to={stepLink(projectId, st)} className="font-medium hover:underline">{st.label}</Link>{' '}
              {live ? <span>{JOB_LABEL[st.job!]}{st.auto ? ' (started automatically)' : ''}</span>
                : st.job === 'FAILED' ? <span className="text-rose-signal">failed: {st.jobError}</span>
                : st.status !== 'NONE' ? <Status status={st.status} />
                : <span className="text-muted">{waitingFor(st, steps, s)}</span>}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

function SettingsDialog({ projectId, s, topicCount, onClose }: { projectId: string; s: AutomationSettings; topicCount: number; onClose: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const save = useSaveAutomation(projectId)
  const [f, setF] = useState(s)
  const [chosen, setAccountId] = useState(s.accountId ?? '')
  // a saved account that is no longer usable isn't shown as chosen
  const accountId = isPending || usable.some((x) => x.id === chosen) ? chosen : ''
  const [model, setModel] = useState(s.model ?? '')
  useDefaultAccount(usable, accountId, setAccountId)
  const set = (patch: Partial<AutomationSettings>) => setF({ ...f, ...patch })
  const calls = topicCount * (1 + (f.autoNotes ? 1 : 0) + (f.autoMcq ? 1 : 0) + (f.autoSlides ? 1 : 0))
  return (
    <Dialog
      open
      size="lg"
      title="Automation settings"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={save.isPending}
            disabled={f.enabled && (!accountId || !model)}
            onClick={() => save.mutate({ ...f, accountId: accountId || null, model: model || null }, {
              onSuccess: () => { toast.success(f.enabled ? 'Automation is on' : 'Automation is off'); onClose() },
              onError: (e) => toast.error(e.message),
            })}
          >
            Save settings
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        <label className="flex gap-3 items-start">
          <input type="checkbox" className="mt-1 accent-marigold-500" checked={f.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
          <span>
            <span className="font-medium">Start each step automatically when the one before it is approved</span>
            <span className="block text-sm text-muted">Approving the topic list starts topic analysis; approving an analysis starts the steps ticked below. Each one is a draft for review.</span>
          </span>
        </label>
        {isPending ? <Spinner label="Loading AI accounts" /> : (
          <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="The automatic work runs with this account and is charged to it." />
        )}
        <div className="grid sm:grid-cols-3 gap-4">
          <Field label="Notes level">
            {(fid) => (
              <Select id={fid} value={f.notesLevel} onChange={(e) => set({ notesLevel: e.target.value as Level })}>
                {LEVELS.map((l) => <option key={l} value={l}>{LEVEL_LABEL[l]}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Questions per topic" hint="4 to 30">
            {(fid) => <Input id={fid} type="number" min={4} max={30} value={f.mcqCount} onChange={(e) => set({ mcqCount: Number(e.target.value) })} />}
          </Field>
          <Field label="Video length (minutes)" hint="3 to 20">
            {(fid) => <Input id={fid} type="number" min={3} max={20} value={f.videoMinutes} onChange={(e) => set({ videoMinutes: Number(e.target.value) })} />}
          </Field>
        </div>
        <fieldset className="space-y-2">
          <legend className="font-medium mb-1">After an analysis is approved</legend>
          {([['autoNotes', 'Write the notes at the level above'], ['autoMcq', 'Write the questions, following the question plan, once the notes are approved'],
            ['autoSlides', 'Make the presentation, following the video plan, once the notes, QA report and SEO report are approved']] as const).map(([k, label]) => (
            <label key={k} className="flex gap-3 items-start text-sm">
              <input type="checkbox" className="mt-0.5 accent-marigold-500" checked={f[k]} disabled={!f.enabled} onChange={(e) => set({ [k]: e.target.checked } as Partial<AutomationSettings>)} />
              {label}
            </label>
          ))}
          <p className="text-sm text-muted">QA checks and SEO research always need a person to start them, so the presentation waits for them. The lesson video is made from the approved presentation on the videos page.</p>
        </fieldset>
        {f.enabled && topicCount > 0 && (
          <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm flex gap-2">
            <AlertTriangle className="size-4 shrink-0 mt-0.5 text-marigold-600" aria-hidden />
            <span>With {topicCount} topic{topicCount === 1 ? '' : 's'}, this can make up to {calls} AI request{calls === 1 ? '' : 's'} on this account as work is approved. The cost depends on the model and the provider; check AI usage under Credits.</span>
          </p>
        )}
      </div>
    </Dialog>
  )
}

function RunDialog({ s, title, count, note, pending, onRun, onClose }: {
  s: AutomationSettings; title: string; count: number; note?: string; pending: boolean
  onRun: (req: { accountId: string; model: string; guidance?: string }) => void; onClose: () => void
}) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [chosen, setAccountId] = useState(s.accountId ?? '')
  // a saved account that is no longer usable isn't shown as chosen
  const accountId = isPending || usable.some((x) => x.id === chosen) ? chosen : ''
  const [model, setModel] = useState(s.model ?? '')
  const [guidance, setGuidance] = useState('')
  useDefaultAccount(usable, accountId, setAccountId)
  return (
    <Dialog
      open
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={pending} disabled={!accountId || !model} onClick={() => onRun({ accountId, model, guidance: guidance.trim() || undefined })}>
            Start the analysis
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-sm text-muted">
          The AI reads the topic list and the course document pages about {count === 1 ? 'the topic' : 'each topic'}, and plans {s.mcqCount} questions and a {s.videoMinutes}-minute video.
          {count > 1 && ` That's ${count} AI requests on this account.`} {note}
        </p>
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account." />}
        <Field label="Guidance (optional)" hint="For example: focus on exam questions, or use examples from banking">
          {(fid, d) => <Textarea id={fid} aria-describedby={d} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
      </div>
    </Dialog>
  )
}

// =====================================================================================================
// /topic-analysis/:resourceId — one analysis: read, edit, review, and what follows
// =====================================================================================================

export function TopicAnalysisPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<TopicAnalysis>({ resourceId })
  const approved = page.state.data?.approvedVersionId != null
  const next = useNextSteps(approved ? resourceId : undefined)

  if (page.loading) return <Spinner label="Opening the topic analysis" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  const a = page.content
  if (!state.resource || !d || !a) return <ErrorNotice error={new Error('This analysis has no versions')} />
  const r = state.resource
  const edit = page.editable
  const set = (patch: Partial<TopicAnalysis>) => page.setContent({ ...a, ...patch })
  const totals = planTotals(a)
  const made = a.analysis

  return (
    <>
      <Link to={`/projects/${r.projectId}/analysis`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Topic analysis
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{a.topicTitle}: topic analysis</h1>
        <p className="text-muted mt-1">
          {a.topicPath ? `${a.topicPath}. ` : ''}
          {made?.model ? `Analysed by ${made.model}${made.madeAt ? ` ${formatRelative(made.madeAt)}` : ''}. ` : ''}
          {made && made.quotesKept != null && `${made.quotesFound} of ${made.quotesKept} cited quotes found word for word on their page${made.quotesRemoved ? `; ${made.quotesRemoved} citation${made.quotesRemoved === 1 ? '' : 's'} of pages it wasn't given removed` : ''}.`}
        </p>
      </header>

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-10">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={page.discard}>Discard</Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>Save draft</Button>
            </div>
          )}
          {page.reviewerEditing && <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm">You're editing as the reviewer. Approving with your edits saves them as a new, approved version.</p>}

          <section aria-labelledby="sum-h" className="space-y-3">
            <h2 id="sum-h" className="text-lg font-semibold">Summary and difficulty</h2>
            <TextBlock label="Summary" value={a.summary ?? ''} rows={4} edit={edit} onChange={(v) => set({ summary: v })} />
            <div className="grid sm:grid-cols-[12rem_1fr] gap-4">
              {edit ? (
                <Field label="Difficulty">
                  {(fid) => (
                    <Select id={fid} value={a.difficulty?.level ?? ''} onChange={(e) => set({ difficulty: { ...a.difficulty, level: e.target.value || null } })}>
                      <option value="">Not set</option>
                      {DIFFICULTIES.map((x) => <option key={x} value={x}>{x[0] + x.slice(1).toLowerCase()}</option>)}
                    </Select>
                  )}
                </Field>
              ) : (
                <p><span className="text-sm text-muted block">Difficulty</span>{a.difficulty?.level ? a.difficulty.level[0] + a.difficulty.level.slice(1).toLowerCase() : 'Not set'}</p>
              )}
              <TextBlock label="Why" value={a.difficulty?.why ?? ''} rows={2} edit={edit} onChange={(v) => set({ difficulty: { ...a.difficulty, why: v } })} />
            </div>
          </section>

          <ItemList title="Prerequisites" items={a.prerequisites} edit={edit} onChange={(v) => set({ prerequisites: v })} />

          <section aria-labelledby="obj-h">
            <h2 id="obj-h" className="text-lg font-semibold mb-2">Learning objectives ({a.objectives.length})</h2>
            <ol className="space-y-3">
              {a.objectives.map((o, i) => (
                <li key={i} className="rounded-xl border border-line px-4 py-3">
                  {edit ? (
                    <div className="grid sm:grid-cols-[5rem_1fr_10rem_auto] gap-2 items-start">
                      <Input aria-label="Objective id" value={o.id} maxLength={16} onChange={(e) => set({ objectives: a.objectives.map((x, k) => (k === i ? { ...x, id: e.target.value } : x)) })} />
                      <Textarea aria-label={`Objective ${o.id}`} className="min-h-16" value={o.text} maxLength={2000} onChange={(e) => set({ objectives: a.objectives.map((x, k) => (k === i ? { ...x, text: e.target.value } : x)) })} />
                      <Select aria-label="Bloom level" value={o.bloom ?? ''} onChange={(e) => set({ objectives: a.objectives.map((x, k) => (k === i ? { ...x, bloom: e.target.value || null } : x)) })}>
                        <option value="">No level</option>
                        {BLOOM.map((b) => <option key={b} value={b}>{BLOOM_LABEL[b]}</option>)}
                      </Select>
                      <RemoveButton label={`Remove ${o.id}`} onClick={() => set({ objectives: a.objectives.filter((_, k) => k !== i), mcqPlan: a.mcqPlan.filter((p) => p.objective !== o.id) })} />
                    </div>
                  ) : (
                    <p><span className="font-semibold mr-2">{o.id}</span>{o.text}{o.bloom && <span className="text-muted"> · {BLOOM_LABEL[o.bloom] ?? o.bloom}</span>}</p>
                  )}
                  {(o.outcomes?.length ?? 0) > 0 && <p className="text-sm text-muted mt-1">Outcomes: {o.outcomes!.join(', ')}</p>}
                  <Sources sources={o.sources} />
                </li>
              ))}
            </ol>
            {edit && <AddButton label="Add an objective" onClick={() => set({ objectives: [...a.objectives, { id: nextId('O', a.objectives.map((o) => o.id)), text: '' }] })} />}
          </section>

          <section aria-labelledby="con-h">
            <h2 id="con-h" className="text-lg font-semibold mb-2">Key concepts ({a.concepts.length})</h2>
            <dl className="space-y-3">
              {a.concepts.map((c, i) => (
                <div key={i} className="rounded-xl border border-line px-4 py-3">
                  {edit ? (
                    <div className="grid sm:grid-cols-[5rem_14rem_1fr_auto] gap-2 items-start">
                      <Input aria-label="Concept id" value={c.id} maxLength={16} onChange={(e) => set({ concepts: a.concepts.map((x, k) => (k === i ? { ...x, id: e.target.value } : x)) })} />
                      <Input aria-label="Term" value={c.term} maxLength={300} onChange={(e) => set({ concepts: a.concepts.map((x, k) => (k === i ? { ...x, term: e.target.value } : x)) })} />
                      <Textarea aria-label={`Definition of ${c.term}`} className="min-h-16" value={c.definition ?? ''} maxLength={2000} onChange={(e) => set({ concepts: a.concepts.map((x, k) => (k === i ? { ...x, definition: e.target.value } : x)) })} />
                      <RemoveButton label={`Remove ${c.term}`} onClick={() => set({ concepts: a.concepts.filter((_, k) => k !== i) })} />
                    </div>
                  ) : (
                    <>
                      <dt className="font-semibold">{c.term} <span className="text-muted font-normal text-sm">{c.id} · {BASIS_LABEL[c.basis ?? 'GENERAL']}</span></dt>
                      {c.definition && <dd className="mt-0.5">{c.definition}</dd>}
                    </>
                  )}
                  <Sources sources={c.sources} />
                </div>
              ))}
            </dl>
            {edit && <AddButton label="Add a concept" onClick={() => set({ concepts: [...a.concepts, { id: nextId('C', a.concepts.map((c) => c.id)), term: '', basis: 'GENERAL' }] })} />}
          </section>

          <section aria-labelledby="mis-h">
            <h2 id="mis-h" className="text-lg font-semibold mb-2">Common misconceptions ({a.misconceptions.length})</h2>
            <p className="text-sm text-muted mb-2">Questions use these as wrong options, and the video addresses them.</p>
            <ol className="space-y-2">
              {a.misconceptions.map((m, i) => (
                <li key={i} className="rounded-xl border border-line px-4 py-3 text-sm">
                  {edit ? (
                    <div className="grid sm:grid-cols-[1fr_1fr_auto] gap-2 items-start">
                      <Textarea aria-label="The misconception" className="min-h-16" value={m.wrong} maxLength={2000} onChange={(e) => set({ misconceptions: a.misconceptions.map((x, k) => (k === i ? { ...x, wrong: e.target.value } : x)) })} />
                      <Textarea aria-label="What is correct" className="min-h-16" value={m.right ?? ''} maxLength={2000} onChange={(e) => set({ misconceptions: a.misconceptions.map((x, k) => (k === i ? { ...x, right: e.target.value } : x)) })} />
                      <RemoveButton label="Remove this misconception" onClick={() => set({ misconceptions: a.misconceptions.filter((_, k) => k !== i) })} />
                    </div>
                  ) : (
                    <p><span className="font-medium">Students think:</span> {m.wrong}{m.right && <><br /><span className="font-medium">In fact:</span> {m.right}</>}</p>
                  )}
                </li>
              ))}
            </ol>
            {edit && <AddButton label="Add a misconception" onClick={() => set({ misconceptions: [...a.misconceptions, { wrong: '', basis: 'GENERAL' }] })} />}
          </section>

          {a.examples.length > 0 && (
            <section aria-labelledby="ex-h">
              <h2 id="ex-h" className="text-lg font-semibold mb-2">Examples to use</h2>
              <ul className="list-disc pl-5 space-y-1">
                {a.examples.map((e, i) => (
                  <li key={i}>{e.title}{e.kind && <span className="text-muted"> · {EXAMPLE_LABEL[e.kind] ?? e.kind}</span>}<Sources sources={e.sources} /></li>
                ))}
              </ul>
            </section>
          )}
          <ItemList title="Exam relevance" items={a.examRelevance} edit={edit} onChange={(v) => set({ examRelevance: v })} />

          <section aria-labelledby="plan-h">
            <h2 id="plan-h" className="text-lg font-semibold mb-1">Question plan</h2>
            <p className={cn('text-sm mb-2', totals.questionsOk ? 'text-muted' : 'text-rose-signal font-medium')}>
              {totals.questions} planned{a.mcqCount != null && ` of ${a.mcqCount}`}{!totals.questionsOk && ': the plan must add up to the number of questions before approval'}
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[36rem]">
                <thead><tr className="text-left text-muted"><th className="font-normal py-1 pr-2">Objective</th><th className="font-normal pr-2 w-24">Questions</th><th className="font-normal pr-2">Difficulty</th><th className="font-normal">Styles</th></tr></thead>
                <tbody>
                  {a.mcqPlan.map((p, i) => (
                    <tr key={p.objective} className="border-t border-line align-top">
                      <td className="py-1.5 pr-2"><span className="font-medium">{p.objective}</span> <span className="text-muted">{a.objectives.find((o) => o.id === p.objective)?.text}</span></td>
                      <td className="pr-2">
                        {edit ? <Input aria-label={`Questions for ${p.objective}`} type="number" min={0} max={30} value={p.count} onChange={(e) => set({ mcqPlan: a.mcqPlan.map((x, k) => (k === i ? { ...x, count: Number(e.target.value) } : x)) })} />
                          : <span className="tabular-nums">{p.count}</span>}
                      </td>
                      <td className="pr-2 text-muted">{Object.entries(p.difficulties ?? {}).map(([k, n]) => `${n} ${k.toLowerCase()}`).join(', ') || '—'}</td>
                      <td className="text-muted">{(p.styles ?? []).map((x) => STYLE_LABEL[x as (typeof STYLES)[number]] ?? x).join(', ') || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {edit && a.objectives.some((o) => !a.mcqPlan.some((p) => p.objective === o.id)) && (
              <Select aria-label="Plan questions for an objective" className="mt-2 max-w-xs" value="" onChange={(e) => e.target.value && set({ mcqPlan: [...a.mcqPlan, { objective: e.target.value, count: 1, difficulties: {}, styles: [] }] })}>
                <option value="">Plan questions for…</option>
                {a.objectives.filter((o) => !a.mcqPlan.some((p) => p.objective === o.id)).map((o) => <option key={o.id} value={o.id}>{o.id}</option>)}
              </Select>
            )}
          </section>

          <section aria-labelledby="video-h">
            <h2 id="video-h" className="text-lg font-semibold mb-1">Lesson video plan</h2>
            <p className={cn('text-sm mb-2', totals.timeOk ? 'text-muted' : 'text-marigold-700')}>
              {a.videoPlan?.scenes.length ?? 0} scenes, {clock(totals.seconds)} in all; about {clock(totals.target)} asked{!totals.timeOk && '. That is more than a quarter off.'}
            </p>
            <TextBlock label="Opening hook" value={a.videoPlan?.hook ?? ''} rows={2} edit={edit} onChange={(v) => set({ videoPlan: { scenes: a.videoPlan?.scenes ?? [], hook: v } })} />
            <ol className="mt-3 space-y-2">
              {(a.videoPlan?.scenes ?? []).map((sc, i) => {
                const scenes = a.videoPlan!.scenes
                const put = (patch: Partial<typeof sc>) => set({ videoPlan: { ...a.videoPlan!, scenes: scenes.map((x, k) => (k === i ? { ...x, ...patch } : x)) } })
                return (
                  <li key={i} className="rounded-xl border border-line px-4 py-3 text-sm">
                    {edit ? (
                      <div className="grid sm:grid-cols-[1fr_8rem_6rem_8rem_auto] gap-2 items-start">
                        <Input aria-label={`Scene ${i + 1} title`} value={sc.title} maxLength={300} onChange={(e) => put({ title: e.target.value })} />
                        <Select aria-label="Shows" value={sc.shows ?? 'CONCEPT'} onChange={(e) => put({ shows: e.target.value })}>
                          {SHOWS.map((x) => <option key={x} value={x}>{SHOWS_LABEL[x]}</option>)}
                        </Select>
                        <Input aria-label="Seconds" type="number" min={5} max={900} value={sc.seconds} onChange={(e) => put({ seconds: Number(e.target.value) })} />
                        <Select aria-label="Goal" value={sc.goal ?? ''} onChange={(e) => put({ goal: e.target.value || null })}>
                          <option value="">No objective</option>
                          {a.objectives.map((o) => <option key={o.id} value={o.id}>{o.id}</option>)}
                        </Select>
                        <RemoveButton label={`Remove scene ${i + 1}`} onClick={() => set({ videoPlan: { ...a.videoPlan!, scenes: scenes.filter((_, k) => k !== i) } })} />
                      </div>
                    ) : (
                      <p>
                        <span className="font-medium">{i + 1}. {sc.title}</span>
                        <span className="text-muted"> · {SHOWS_LABEL[sc.shows ?? ''] ?? sc.shows} · {clock(sc.seconds)}{sc.goal ? ` · ${sc.goal}` : ''}</span>
                        {(sc.concepts?.length ?? 0) > 0 && <span className="block text-muted">Concepts: {sc.concepts!.map((cid) => a.concepts.find((c) => c.id === cid)?.term ?? cid).join(', ')}</span>}
                      </p>
                    )}
                  </li>
                )
              })}
            </ol>
            {edit && (a.videoPlan?.scenes.length ?? 0) < 12 && (
              <AddButton label="Add a scene" onClick={() => set({ videoPlan: { hook: a.videoPlan?.hook ?? null, scenes: [...(a.videoPlan?.scenes ?? []), { title: '', shows: 'CONCEPT', seconds: 60, concepts: [] }] } })} />
            )}
          </section>

          <NoteList title="Gaps in the course documents" empty="None found." items={a.gaps} edit={edit} onChange={(v) => set({ gaps: v })} />
          <NoteList title="Questions for the lecturer" empty="None." items={a.questions} edit={edit} onChange={(v) => set({ questions: v })} />
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel detail={d} kindLabel="Topic analysis" dirty={page.dirty} edited={a} saveFirst={page.saveDraft} editing={page.reviewerEditing}
            onReviewerEdit={(on) => { page.setReviewerEditing(on); if (!on) page.discard() }} onEdit={page.startDraft}
            originLabel={d.version.origin === 'AI' ? 'written by the AI analysis' : undefined} />
          <section aria-labelledby="next-h">
            <h2 id="next-h" className="font-semibold mb-2">What happens next</h2>
            {!approved ? (
              <p className="text-sm text-muted">When this analysis is approved, the notes{next.data?.settings.enabled ? ' start automatically' : ' can be written'}, and the questions and presentation follow its plans.</p>
            ) : next.isPending ? <Spinner label="Loading the next steps" /> : next.error ? <ErrorNotice error={next.error} /> : (
              <Steps projectId={r.projectId} steps={next.data.steps} s={next.data.settings} />
            )}
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
    </>
  )
}

// ---- small pieces ------------------------------------------------------------------------------------

function Sources({ sources }: { sources?: Source[] }) {
  if (!sources || sources.length === 0) return null
  return (
    <ul className="mt-1 space-y-0.5 text-sm text-muted">
      {sources.map((s, i) => (
        <li key={i}>
          {s.documentId ? <Link className="hover:underline" to={`/documents/${s.documentId}?${s.versionId ? `version=${s.versionId}&` : ''}page=${s.page}`}>{s.title}, page {s.page}</Link> : `${s.title}, page ${s.page}`}
          {s.quote && <>: “{s.quote}”</>}
          {s.found === false && <span className="text-marigold-700"> (not found word for word on that page)</span>}
        </li>
      ))}
    </ul>
  )
}

function TextBlock({ label, value, rows, edit, onChange }: { label: string; value: string; rows: number; edit: boolean; onChange: (v: string) => void }) {
  if (!edit) return value ? <p><span className="text-sm text-muted block">{label}</span>{value}</p> : null
  return <Field label={label}>{(fid) => <Textarea id={fid} rows={rows} maxLength={4000} value={value} onChange={(e) => onChange(e.target.value)} />}</Field>
}

function ItemList({ title, items, edit, onChange }: { title: string; items: Item[]; edit: boolean; onChange: (v: Item[]) => void }) {
  if (!edit && items.length === 0) return null
  return (
    <section aria-label={title}>
      <h2 className="text-lg font-semibold mb-2">{title}</h2>
      <ul className={cn(!edit && 'list-disc pl-5', 'space-y-1')}>
        {items.map((it, i) => (
          <li key={i}>
            {edit ? (
              <div className="flex gap-2 items-start">
                <Input aria-label={`${title} ${i + 1}`} value={it.text} maxLength={2000} onChange={(e) => onChange(items.map((x, k) => (k === i ? { ...x, text: e.target.value } : x)))} />
                <RemoveButton label={`Remove ${title.toLowerCase()} ${i + 1}`} onClick={() => onChange(items.filter((_, k) => k !== i))} />
              </div>
            ) : (
              <>{it.text}{it.basis === 'GENERAL' && <span className="text-muted text-sm"> (general knowledge)</span>}<Sources sources={it.sources} /></>
            )}
          </li>
        ))}
      </ul>
      {edit && <AddButton label={`Add to ${title.toLowerCase()}`} onClick={() => onChange([...items, { text: '', basis: 'GENERAL', sources: [] }])} />}
    </section>
  )
}

function NoteList({ title, empty, items, edit, onChange }: { title: string; empty: string; items: { text: string }[]; edit: boolean; onChange: (v: { text: string }[]) => void }) {
  return (
    <section aria-label={title}>
      <h2 className="text-lg font-semibold mb-2">{title}</h2>
      {items.length === 0 && !edit && <p className="text-sm text-muted">{empty}</p>}
      <ul className={cn(!edit && 'list-disc pl-5', 'space-y-1')}>
        {items.map((n, i) => (
          <li key={i}>
            {edit ? (
              <div className="flex gap-2 items-start">
                <Input aria-label={`${title} ${i + 1}`} value={n.text} maxLength={2000} onChange={(e) => onChange(items.map((x, k) => (k === i ? { text: e.target.value } : x)))} />
                <RemoveButton label={`Remove ${i + 1}`} onClick={() => onChange(items.filter((_, k) => k !== i))} />
              </div>
            ) : n.text}
          </li>
        ))}
      </ul>
      {edit && <AddButton label="Add one" onClick={() => onChange([...items, { text: '' }])} />}
    </section>
  )
}

function AddButton({ label, onClick }: { label: string; onClick: () => void }) {
  return <Button size="sm" variant="ghost" className="mt-2 -ml-3" icon={<Plus className="size-4" aria-hidden />} onClick={onClick}>{label}</Button>
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" aria-label={label} onClick={onClick} className="size-10 inline-flex items-center justify-center rounded-lg text-muted hover:text-rose-signal hover:bg-surface-2">
      <Trash2 className="size-4" aria-hidden />
    </button>
  )
}

