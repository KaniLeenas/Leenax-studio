import { Check, ChevronLeft, Download, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import {
  DIFFICULTIES,
  LETTERS,
  quizExportUrl,
  STYLE_LABEL,
  STYLES,
  useCreateQuiz,
  useDeleteQuiz,
  useMaterials,
  useQuiz,
  useQuizzes,
  type Quiz,
} from '../lib/materials'
import { useProject } from '../lib/queries'
import { toast } from '../stores/ui'

/** /projects/:id/quizzes — quizzes drawn from approved question banks. A quiz is a fixed snapshot. */
export function QuizzesPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const open = params.get('quiz')
  const project = useProject(id)
  const materials = useMaterials(id)
  const quizzes = useQuizzes(id)
  const [building, setBuilding] = useState(false)

  if (project.isPending || materials.isPending || quizzes.isPending) return <Spinner label="Loading quizzes" />
  if (project.error || materials.error || quizzes.error) return <ErrorNotice error={project.error ?? materials.error ?? quizzes.error} onRetry={() => quizzes.refetch()} />
  const p = project.data
  const list = quizzes.data
  const banks = materials.data.banks.filter((b) => b.approvedNo)
  const canEdit = materials.data.canEdit

  return (
    <>
      <Link to={`/projects/${p.id}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <PageHeader
        title="Quizzes"
        description="Draw a quiz from approved question banks. The questions are fixed when the quiz is made, so later edits to a bank don't change it."
        actions={
          canEdit && banks.length > 0 ? (
            <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setBuilding(true)}>
              New quiz
            </Button>
          ) : undefined
        }
      />
      {list.length === 0 ? (
        <EmptyState
          title="No quizzes yet"
          body={banks.length ? 'Make one from the approved question banks.' : 'Quizzes use approved question banks, and none is approved yet.'}
        />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {list.map((q) => (
            <li key={q.id} className="py-3 flex flex-wrap items-center gap-3">
              <button type="button" className="text-left flex-1 min-w-0 hover:underline font-medium break-words" onClick={() => setParams({ quiz: q.id })}>
                {q.title}
              </button>
              <span className="text-sm text-muted">
                {q.questionCount} questions, {q.createdBy ? `by ${q.createdBy} ` : ''}
                {formatRelative(q.createdAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {building && <QuizBuilder projectId={id} banks={banks.map((b) => ({ id: b.resourceId, title: b.title, count: b.questionCount ?? 0, versionNo: b.approvedNo! }))} onClose={() => setBuilding(false)} onMade={(q) => { setBuilding(false); setParams({ quiz: q.id }) }} />}
      {open && <QuizView projectId={id} quizId={open} canEdit={canEdit} onClose={() => setParams({})} />}
    </>
  )
}

function QuizBuilder({ projectId, banks, onClose, onMade }: { projectId: string; banks: { id: string; title: string; count: number; versionNo: number }[]; onClose: () => void; onMade: (q: Quiz) => void }) {
  const [title, setTitle] = useState('')
  const [chosen, setChosen] = useState<string[]>(banks.map((b) => b.id))
  const [count, setCount] = useState(20)
  const [difficulties, setDifficulties] = useState<string[]>([])
  const [styles, setStyles] = useState<string[]>([])
  const [shuffle, setShuffle] = useState(true)
  const [seed, setSeed] = useState('')
  const create = useCreateQuiz(projectId)
  const toggle = (list: string[], set: (v: string[]) => void, v: string) => set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v])
  const available = banks.filter((b) => chosen.includes(b.id)).reduce((n, b) => n + b.count, 0)
  const seedOk = seed === '' || /^\d{1,15}$/.test(seed)
  return (
    <Dialog
      open
      size="xl"
      title="New quiz"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={create.isPending}
            disabled={!title.trim() || chosen.length === 0 || count < 1 || count > 200 || !seedOk}
            onClick={() =>
              create.mutate(
                { title: title.trim(), bankIds: chosen, count, difficulties, styles, shuffleOptions: shuffle, seed: seed ? Number(seed) : undefined },
                { onSuccess: (q) => { toast.success(`Made a quiz of ${q.questions.length} questions`); onMade(q) }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Make quiz
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Title">{(fid) => <Input id={fid} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
        <fieldset>
          <legend className="font-medium mb-1">From these approved banks</legend>
          <ul className="max-h-56 overflow-y-auto space-y-1 rounded-xl border border-line p-2">
            {banks.map((b) => (
              <li key={b.id}>
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" className="size-4 accent-marigold-600" checked={chosen.includes(b.id)} onChange={() => toggle(chosen, setChosen, b.id)} />
                  <span className="flex-1 min-w-0 break-words">{b.title}</span>
                  <span className="text-muted">{b.count} in version {b.versionNo}</span>
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="Questions" hint={`Up to ${available} available before filters`}>
            {(fid, hid) => <Input id={fid} aria-describedby={hid} type="number" min={1} max={200} value={count} onChange={(e) => setCount(Number(e.target.value))} />}
          </Field>
          <Field label="Seed (optional)" hint="The same seed and choices give the same quiz" error={seedOk ? undefined : 'Use digits only'}>
            {(fid, hid) => <Input id={fid} aria-describedby={hid} inputMode="numeric" value={seed} onChange={(e) => setSeed(e.target.value.trim())} />}
          </Field>
        </div>
        <fieldset>
          <legend className="font-medium mb-1">Difficulty</legend>
          <div className="flex flex-wrap gap-x-4 gap-y-1">
            {DIFFICULTIES.map((x) => (
              <label key={x} className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="size-4 accent-marigold-600" checked={difficulties.includes(x)} onChange={() => toggle(difficulties, setDifficulties, x)} />
                {STYLE_LABEL[x]}
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset>
          <legend className="font-medium mb-1">Kinds of question</legend>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1">
            {STYLES.map((x) => (
              <label key={x} className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="size-4 accent-marigold-600" checked={styles.includes(x)} onChange={() => toggle(styles, setStyles, x)} />
                {STYLE_LABEL[x]}
              </label>
            ))}
          </div>
          <p className="text-sm text-muted mt-1">Leave empty for any.</p>
        </fieldset>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" className="size-4 accent-marigold-600" checked={shuffle} onChange={(e) => setShuffle(e.target.checked)} />
          Shuffle the options of each question
        </label>
      </div>
    </Dialog>
  )
}

function QuizView({ projectId, quizId, canEdit, onClose }: { projectId: string; quizId: string; canEdit: boolean; onClose: () => void }) {
  const quiz = useQuiz(quizId)
  const remove = useDeleteQuiz(projectId)
  const [answers, setAnswers] = useState(false)
  const q = quiz.data
  return (
    <Dialog
      open
      size="xl"
      title={q?.title ?? 'Quiz'}
      onClose={onClose}
      footer={
        q && (
          <>
            {canEdit && (
              <Button
                variant="ghost"
                icon={<Trash2 className="size-4" />}
                loading={remove.isPending}
                onClick={() => {
                  if (!window.confirm(`Delete the quiz “${q.title}”?`)) return
                  remove.mutate(q.id, { onSuccess: () => { toast.success('Quiz deleted'); onClose() }, onError: (e) => toast.error(e.message) })
                }}
              >
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button variant="ghost" onClick={onClose}>Close</Button>
          </>
        )
      }
    >
      {quiz.isPending ? (
        <Spinner label="Opening the quiz" />
      ) : quiz.error ? (
        <ErrorNotice error={quiz.error} onRetry={() => quiz.refetch()} />
      ) : (
        q && (
          <div className="space-y-5">
            <p className="text-sm text-muted">
              {q.questions.length} questions, seed {q.seed}. Made {formatRelative(q.createdAt)}
              {q.createdBy ? ` by ${q.createdBy}` : ''}.
            </p>
            <div className="space-y-2">
              {(['none', 'end'] as const).map((mode) => (
                <div key={mode} className="flex flex-wrap items-center gap-2">
                  <span className="text-sm w-40">{mode === 'none' ? 'For students, no answers' : 'With an answer key'}</span>
                  {[['pdf', 'PDF'], ['docx', 'Word'], ['xlsx', 'Excel'], ['csv', 'CSV']].map(([f, label]) => (
                    <a key={f} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface px-3 h-8 text-sm hover:bg-surface-2" href={quizExportUrl(q.id, f, mode)}>
                      <Download className="size-3.5" aria-hidden /> {label}
                    </a>
                  ))}
                </div>
              ))}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" className="size-4 accent-marigold-600" checked={answers} onChange={(e) => setAnswers(e.target.checked)} />
              Show answers here
            </label>
            <ol className="space-y-4">
              {q.questions.map((x) => (
                <li key={x.number}>
                  <p className="whitespace-pre-wrap break-words">
                    <span className="font-semibold">{x.number}.</span> {x.stem}
                  </p>
                  {x.code && <pre className="mt-2 rounded-lg bg-surface-2 px-3 py-2 text-sm overflow-x-auto"><code>{x.code.text}</code></pre>}
                  <ol className="mt-2 space-y-0.5">
                    {x.options.map((o, i) => (
                      <li key={i} className={cn('flex gap-2', answers && x.answer === i && 'font-medium')}>
                        <span className="w-4">{LETTERS[i]}</span>
                        <span className="flex-1 break-words">{o}</span>
                        {answers && x.answer === i && <Check className="size-4 text-emerald-600 shrink-0" aria-label="Correct answer" />}
                      </li>
                    ))}
                  </ol>
                  {answers && x.explanation && <p className="text-sm text-muted mt-1">{x.explanation}</p>}
                  <p className="text-sm text-faint mt-1">{x.bankTitle}, version {x.bankVersionNo}</p>
                </li>
              ))}
            </ol>
          </div>
        )
      )}
    </Dialog>
  )
}
