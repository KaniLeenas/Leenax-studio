import { ArrowDown, ArrowUp, Check, ChevronLeft, Download, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Comments } from '../components/Comments'
import { QaPanel } from '../components/QaPanel'
import { History, ReviewPanel, VersionList } from '../components/review'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import {
  DIFFICULTIES,
  exportUrl,
  LETTERS,
  LEVEL_LABEL,
  LEVELS,
  newQuestion,
  questionProblems,
  regenerateQuestion,
  STYLE_LABEL,
  STYLES,
  useMaterials,
  useUpdateFromUpstream,
  type Bank,
  type Question,
  type Style,
} from '../lib/materials'
import { useAuthStatus } from '../lib/queries'
import { openByItem, SEVERITY_LABEL, useResourceQa, type Issue } from '../lib/qa'
import { toast } from '../stores/ui'
import { QuestionsDialog } from './MaterialsPage'

/** /mcq/:resourceId — a topic's question bank: every question, checked as you edit, and its review. */
export function BankPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<Bank>({ resourceId })
  const me = useAuthStatus()
  const update = useUpdateFromUpstream()
  const projectId = page.state.data?.resource?.projectId ?? ''
  const materials = useMaterials(projectId)
  const qa = useResourceQa(page.state.data?.resource?.id)
  const [onlyProblems, setOnlyProblems] = useState(false)
  const [more, setMore] = useState(false)
  const [regen, setRegen] = useState<Question | null>(null)

  if (page.loading) return <Spinner label="Opening the question bank" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  const b = page.content
  if (!state.resource || !d || !b) return <ErrorNotice error={new Error('This bank has no versions')} />
  const r = state.resource
  const editable = page.editable
  const qs = b.questions ?? []
  const allowAllNone = !!b.allowAllNone
  const problems = qs.map((q) => questionProblems(q, allowAllNone))
  const withProblems = problems.filter((p) => p.length > 0).length
  const notesChanged = d.checks.some((c) => c.code === 'notes' && !c.ok)
  const newest = state.versions[0]
  const canComment = state.canEdit || state.canReview
  const set = (questions: Question[]) => page.setContent({ ...b, questions })
  const change = (i: number, q: Question) => set(qs.map((x, j) => (j === i ? q : x)))
  const move = (i: number, by: -1 | 1) => {
    const next = [...qs]
    const [q] = next.splice(i, 1)
    next.splice(i + by, 0, q)
    set(next)
  }
  const qaOpen = openByItem(qa.data?.issues ?? [])
  const qaChecked = (qa.data?.runs ?? []).some((x) => x.mode === 'CHECK')
  const showQuestion = (i: Issue) => {
    const el = i.itemRef ? document.getElementById(`q-${i.itemRef}`) : null
    if (!el) return false
    if (onlyProblems) setOnlyProblems(false)
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.focus()
    return true
  }
  const topic = materials.data?.topics.find((t) => t.id === r.topicId)
  const approvedLevels = LEVELS.filter((l) => materials.data?.notes.some((n) => n.topicId === r.topicId && n.level === l && n.approvedNo))
  const busy = materials.data?.jobs.some((j) => j.kind === 'MCQ' && j.target === r.topicId && (j.status === 'QUEUED' || j.status === 'RUNNING'))

  return (
    <>
      <Link to={`/projects/${r.projectId}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{b.topicTitle}: questions</h1>
        <p className="text-muted mt-1">
          {qs.length} questions{b.notesLevel ? `, written from the ${LEVEL_LABEL[b.notesLevel].toLowerCase()} notes` : ''}
          {withProblems > 0 && <span className="text-rose-signal">, {withProblems} need fixing</span>}
        </p>
      </header>

      {d.latest && (d.version.status === 'OUTDATED' || notesChanged) && (
        <div className="mb-6 rounded-xl border border-marigold-500/50 px-4 py-3 text-sm flex flex-wrap items-center gap-3" role="status">
          <p className="flex-1 min-w-0">The notes this bank was written from have changed. Check the questions still match, then tie the bank to the approved notes.</p>
          {state.canEdit && newest?.status !== 'IN_REVIEW' && (
            <Button size="sm" variant="primary" loading={update.isPending} onClick={() => update.mutate(r.id, { onSuccess: () => toast.success('Tied to the approved notes'), onError: (e) => toast.error(e.message) })}>
              Tie to the approved notes
            </Button>
          )}
        </div>
      )}
      {busy && <p className="mb-6 rounded-xl border border-line bg-surface-2 px-4 py-3 text-sm" role="status">Writing more questions… they'll be added to this bank.</p>}

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 mb-3 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={page.discard}>
                Discard
              </Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>
                Save draft
              </Button>
            </div>
          )}
          {page.reviewerEditing && (
            <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm mb-4">You're editing as the reviewer. Approving with your edits saves them as a new, approved version.</p>
          )}
          <div className="flex flex-wrap items-center gap-3 mb-4">
            {withProblems > 0 && (
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="size-4 accent-marigold-600" checked={onlyProblems} onChange={(e) => setOnlyProblems(e.target.checked)} />
                Show only the {withProblems} that need fixing
              </label>
            )}
            <span className="flex-1" />
            {state.canEdit && d.latest && newest?.status !== 'IN_REVIEW' && !page.dirty && !busy && topic && approvedLevels.length > 0 && (
              <Button size="sm" icon={<Plus className="size-4" />} onClick={() => setMore(true)}>
                Write more with AI
              </Button>
            )}
          </div>
          {qs.length === 0 && <p className="text-muted">No questions yet.</p>}
          <ol className="space-y-4">
            {qs.map((q, i) =>
              onlyProblems && problems[i].length === 0 ? null : (
                <QuestionCard
                  key={q.id}
                  n={i + 1}
                  q={q}
                  problems={problems[i]}
                  editable={editable}
                  canRegenerate={editable && state.canEdit && d.version.status === 'DRAFT'}
                  onChange={(x) => change(i, x)}
                  onMove={(by) => move(i, by)}
                  first={i === 0}
                  last={i === qs.length - 1}
                  onRemove={() => set(qs.filter((_, j) => j !== i))}
                  onRegenerate={() => setRegen(q)}
                  qaOpen={qaOpen.get(q.id) ?? []}
                  qaChecked={qaChecked}
                />
              ),
            )}
          </ol>
          {editable && (
            <Button className="mt-4" variant="ghost" icon={<Plus className="size-4" />} onClick={() => set([...qs, newQuestion()])}>
              Add a question
            </Button>
          )}
          <div className="mt-8">
            <QaPanel
              resourceId={r.id}
              versionId={d.version.id}
              versionNo={d.version.versionNo}
              questionOptions={new Map(qs.map((q) => [q.id, q.options]))}
              onShowItem={showQuestion}
            />
          </div>
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel
            detail={d}
            kindLabel="Questions"
            dirty={page.dirty}
            edited={b}
            saveFirst={page.saveDraft}
            editing={page.reviewerEditing}
            onReviewerEdit={(on) => {
              page.setReviewerEditing(on)
              if (!on) page.discard()
            }}
            onEdit={notesChanged ? undefined : page.startDraft}
          />
          <section aria-labelledby="export-h">
            <h2 id="export-h" className="font-semibold mb-2">Download version {d.version.versionNo}</h2>
            <div className="flex flex-wrap gap-2">
              {[['pdf', 'PDF'], ['docx', 'Word'], ['xlsx', 'Excel'], ['csv', 'CSV']].map(([f, label]) => (
                <a key={f} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-3 h-9 text-sm hover:bg-surface-2" href={exportUrl(d.version.id, f)}>
                  <Download className="size-4" aria-hidden /> {label}
                </a>
              ))}
            </div>
            <p className="text-sm text-muted mt-1">With answers and explanations. For a quiz without answers on the page, make a quiz.</p>
          </section>
          <Comments resourceId={r.id} versionId={d.version.id} versionNo={d.version.versionNo} canComment={canComment} meId={me.data?.user?.id} clearQuote={() => {}} />
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

      {more && topic && <QuestionsDialog projectId={r.projectId} topic={topic} levels={approvedLevels} hasBank onClose={() => setMore(false)} />}
      {regen && (
        <RegenerateDialog
          resourceId={r.id}
          versionId={d.version.id}
          question={regen}
          onClose={() => setRegen(null)}
          onDone={(q) => {
            const i = qs.findIndex((x) => x.id === q.id)
            if (i >= 0) change(i, q)
            setRegen(null)
            toast.success('New question in place. Save the draft to keep it.')
          }}
        />
      )}
    </>
  )
}

function QuestionCard({
  n, q, problems, editable, canRegenerate, onChange, onMove, first, last, onRemove, onRegenerate, qaOpen, qaChecked,
}: {
  n: number
  q: Question
  problems: string[]
  editable: boolean
  canRegenerate: boolean
  onChange: (q: Question) => void
  onMove: (by: -1 | 1) => void
  first: boolean
  last: boolean
  onRemove: () => void
  onRegenerate: () => void
  qaOpen: Issue[]
  qaChecked: boolean
}) {
  const [why, setWhy] = useState(false)
  const set = (patch: Partial<Question>) => onChange({ ...q, ...patch })
  const setOption = (i: number, v: string) => set({ options: q.options.map((o, j) => (j === i ? v : o)) })
  const setWhyText = (i: number, v: string) => set({ optionExplanations: (q.optionExplanations ?? ['', '', '', '']).map((o, j) => (j === i ? v : o)) })

  return (
    <li id={`q-${q.id}`} tabIndex={-1} className={cn('rounded-2xl border px-5 py-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-marigold-500/40', problems.length ? 'border-rose-signal/50' : 'border-line')} aria-label={`Question ${n}`}>
      <div className="flex flex-wrap items-center gap-2 mb-3">
        <span className="font-semibold mr-1">{n}.</span>
        {editable ? (
          <>
            <div className="w-44"><Select aria-label="Kind of question" value={q.style} onChange={(e) => set({ style: e.target.value as Style })}>
              {STYLES.map((s) => <option key={s} value={s}>{STYLE_LABEL[s]}</option>)}
            </Select></div>
            <div className="w-40"><Select aria-label="Difficulty" value={q.difficulty ?? ''} onChange={(e) => set({ difficulty: (e.target.value || null) as Question['difficulty'] })}>
              <option value="">Difficulty</option>
              {DIFFICULTIES.map((s) => <option key={s} value={s}>{STYLE_LABEL[s]}</option>)}
            </Select></div>
          </>
        ) : (
          <span className="text-sm text-muted">
            {STYLE_LABEL[q.style]}
            {q.difficulty && `, ${STYLE_LABEL[q.difficulty].toLowerCase()}`}
          </span>
        )}
        <span className={cn('text-sm', qaOpen.some((i) => i.severity === 'CRITICAL') ? 'text-rose-signal font-medium' : 'text-muted')}>
          {qaOpen.length > 0
            ? `QA: ${qaOpen.length} open (${[...new Set(qaOpen.map((i) => SEVERITY_LABEL[i.severity].toLowerCase()))].join(', ')})`
            : qaChecked ? 'QA: nothing open' : 'Not checked by QA yet'}
        </span>
        <span className="flex-1" />
        {editable && (
          <span className="flex items-center gap-0.5">
            {canRegenerate && <IconButton label="Write a new version of this question with AI" onClick={onRegenerate}><RefreshCw /></IconButton>}
            <IconButton label="Move up" disabled={first} onClick={() => onMove(-1)}><ArrowUp /></IconButton>
            <IconButton label="Move down" disabled={last} onClick={() => onMove(1)}><ArrowDown /></IconButton>
            <IconButton label={`Delete question ${n}`} onClick={onRemove}><Trash2 /></IconButton>
          </span>
        )}
      </div>

      {editable ? (
        <div className="space-y-3">
          <Textarea aria-label="Question" rows={2} maxLength={3000} placeholder="The question" value={q.stem ?? ''} onChange={(e) => set({ stem: e.target.value })} />
          {q.code ? (
            <div className="grid gap-2 sm:grid-cols-[8rem_1fr]">
              <Input aria-label="Code language" placeholder="Language" value={q.code.language ?? ''} onChange={(e) => set({ code: { ...q.code!, language: e.target.value } })} />
              <Textarea aria-label="Code" rows={4} className="font-mono text-sm" value={q.code.text} onChange={(e) => set({ code: e.target.value ? { ...q.code!, text: e.target.value } : null })} />
            </div>
          ) : (
            <button type="button" className="text-sm text-muted underline underline-offset-2" onClick={() => set({ code: { language: '', text: ' ' } })}>Add code</button>
          )}
          <fieldset>
            <legend className="sr-only">Options; choose the correct one</legend>
            <div className="space-y-2">
              {q.options.map((o, i) => (
                <div key={i} className="grid grid-cols-[auto_auto_1fr] items-center gap-2">
                  <input type="radio" name={`answer-${q.id}`} className="size-4 accent-emerald-600" checked={q.answer === i} onChange={() => set({ answer: i })} aria-label={`Option ${LETTERS[i]} is correct`} />
                  <span className="font-medium w-4">{LETTERS[i]}</span>
                  <Input aria-label={`Option ${LETTERS[i]}`} maxLength={1000} value={o} onChange={(e) => setOption(i, e.target.value)} />
                  {why && i !== q.answer && (
                    <Input className="col-start-3" aria-label={`Why option ${LETTERS[i]} is wrong`} placeholder="Why it's wrong" maxLength={1000} value={q.optionExplanations?.[i] ?? ''} onChange={(e) => setWhyText(i, e.target.value)} />
                  )}
                </div>
              ))}
            </div>
          </fieldset>
          <button type="button" className="text-sm text-muted underline underline-offset-2" aria-expanded={why} onClick={() => setWhy((w) => !w)}>
            {why ? 'Hide' : 'Explain'} the wrong options
          </button>
          <Textarea aria-label="Explanation" rows={2} maxLength={4000} placeholder="Why the answer is right" value={q.explanation ?? ''} onChange={(e) => set({ explanation: e.target.value })} />
          <Input aria-label="Subtopic" placeholder="Subtopic (optional)" maxLength={300} value={q.subtopic ?? ''} onChange={(e) => set({ subtopic: e.target.value })} />
        </div>
      ) : (
        <div>
          <p className="whitespace-pre-wrap break-words">{q.stem}</p>
          {q.code && <pre className="mt-2 rounded-lg bg-surface-2 px-3 py-2 text-sm overflow-x-auto"><code>{q.code.text}</code></pre>}
          <ol className="mt-3 space-y-1">
            {q.options.map((o, i) => (
              <li key={i} className={cn('flex gap-2', q.answer === i && 'font-medium')}>
                <span className="w-4">{LETTERS[i]}</span>
                <span className="flex-1 break-words">{o}</span>
                {q.answer === i && <Check className="size-4 text-emerald-600 shrink-0" aria-label="Correct answer" />}
              </li>
            ))}
          </ol>
          {q.explanation && <p className="mt-3 text-muted">{q.explanation}</p>}
          {q.optionExplanations?.some((w, i) => w && i !== q.answer) && (
            <ul className="mt-1 text-sm text-muted">
              {q.optionExplanations.map((w, i) => (w && i !== q.answer ? <li key={i}>{LETTERS[i]}: {w}</li> : null))}
            </ul>
          )}
        </div>
      )}
      {(q.subtopic || q.source?.label) && !editable && <p className="text-sm text-faint mt-2">{[q.subtopic, q.source?.label].filter(Boolean).join(', ')}</p>}
      {problems.length > 0 && <p className="text-sm text-rose-signal mt-3">Needs fixing: {problems.join(', ')}.</p>}
    </li>
  )
}

function IconButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <button type="button" title={label} aria-label={label} disabled={disabled} onClick={onClick} className="grid place-items-center size-8 rounded-md text-muted hover:text-ink hover:bg-surface-2 disabled:opacity-30 [&_svg]:size-4">
      {children}
    </button>
  )
}

function RegenerateDialog({ resourceId, versionId, question, onClose, onDone }: { resourceId: string; versionId: string; question: Question; onClose: () => void; onDone: (q: Question) => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [guidance, setGuidance] = useState('')
  const [busy, setBusy] = useState(false)
  useDefaultAccount(usable, accountId, setAccountId)
  async function run() {
    setBusy(true)
    try {
      onDone(await regenerateQuestion(resourceId, { versionId, questionId: question.id, accountId, model, guidance: guidance.trim() || undefined }))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not write a new question')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog
      open
      title="Write a new version of this question"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={busy} disabled={!accountId || !model} onClick={run}>Write it</Button>
        </>
      }
    >
      <p className="text-muted mb-4">“{question.stem}”. The new question replaces it in your draft; nothing is saved until you save the draft.</p>
      <div className="space-y-4">
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account." />}
        <Field label="What to change (optional)" hint="For example: “Make the wrong options more plausible.”">
          {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
      </div>
    </Dialog>
  )
}
