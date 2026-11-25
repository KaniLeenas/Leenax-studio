import { ChevronLeft, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import {
  DIFFICULTIES,
  jobRunning,
  LEVEL_LABEL,
  LEVELS,
  STYLE_LABEL,
  STYLES,
  useByHand,
  useGenerateNotes,
  useGenerateQuestions,
  useMaterials,
  type Level,
  type Material,
  type MaterialJob,
  type TopicRow,
} from '../lib/materials'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import { toast } from '../stores/ui'

/** /projects/:id/materials — every approved topic, with its notes at five levels and its question bank. */
export function MaterialsPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useMaterials(id)
  const [notesFor, setNotesFor] = useState<{ topic: TopicRow; level: Level } | null>(null)
  const [questionsFor, setQuestionsFor] = useState<TopicRow | null>(null)

  if (project.isPending || data.isPending) return <Spinner label="Loading notes and questions" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const p = project.data
  const d = data.data
  const notesOf = (topicId: string, level: Level) => d.notes.find((n) => n.topicId === topicId && n.level === level)
  const bankOf = (topicId: string) => d.banks.find((b) => b.topicId === topicId)
  const jobFor = (kind: 'NOTES' | 'MCQ', target: string) => d.jobs.find((j) => j.kind === kind && j.target === target)
  const topicTitle = new Map(d.topics.map((t) => [t.id, t.title]))
  const visibleJobs = d.jobs.filter((j) => jobRunning(j) || (j.status === 'FAILED' && Date.now() - Date.parse(j.createdAt) < 86_400_000))

  return (
    <>
      <Link to={`/projects/${p.id}`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        {p.name}
      </Link>
      <PageHeader
        title="Notes and questions"
        description="Study notes at five levels and a question bank for every approved topic. Each one is reviewed and approved on its own."
        actions={
          <>
            <Link to={`/projects/${p.id}/analysis`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              Topic analysis
            </Link>
            <Link to={`/projects/${p.id}/qa`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              Academic QA
            </Link>
            <Link to={`/projects/${p.id}/seo`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              YouTube SEO
            </Link>
            <Link to={`/projects/${p.id}/slides`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              Presentations
            </Link>
            <Link to={`/projects/${p.id}/videos`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              Videos
            </Link>
            <Link to={`/projects/${p.id}/quizzes`} className="inline-flex items-center rounded-lg border border-line bg-surface px-4 h-10 hover:bg-surface-2">
              Quizzes
            </Link>
          </>
        }
      />

      {!d.topicsApproved ? (
        <EmptyState
          title="The topic list isn't approved yet"
          body="Notes and questions are written only for approved topics, so nothing is built on an unchecked plan."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/topics`}>Go to topics</Link>}
        />
      ) : (
        <>
          {visibleJobs.length > 0 && (
            <ul className="mb-6 space-y-2" aria-label="Work in progress" aria-live="polite">
              {visibleJobs.map((j) => (
                <JobLine key={j.id} job={j} topicTitle={topicTitle} />
              ))}
            </ul>
          )}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted mb-3">
            <p className="mr-auto">From topic list version {d.topicsVersionNo}. Select a level to open its notes.</p>
            <ul className="flex flex-wrap gap-x-4 gap-y-1" aria-label="Key">
              {DOT_KEY.map(([cls, label]) => (
                <li key={label} className="flex items-center gap-1.5">
                  <span className={cn('size-2 rounded-full', cls)} aria-hidden />
                  {label}
                </li>
              ))}
            </ul>
          </div>
          <ul className="divide-y divide-line border-y border-line">
            {d.topics.map((t) => {
              const bank = bankOf(t.id)
              const approvedLevels = LEVELS.filter((l) => notesOf(t.id, l)?.approvedNo)
              return (
                <li key={t.id} className="py-4 grid gap-3 lg:grid-cols-[minmax(0,16rem)_1fr_15rem] lg:items-center">
                  <div className="min-w-0" style={{ paddingLeft: `${(t.depth - 1) * 1.25}rem` }}>
                    <p className={cn('break-words', t.depth === 1 ? 'font-semibold' : 'font-medium')}>{t.title}</p>
                    {t.path && <p className="text-sm text-faint truncate">{t.path}</p>}
                  </div>
                  <div className="flex flex-wrap gap-1.5" aria-label={`Notes for ${t.title}`}>
                    {LEVELS.map((level) => (
                      <NotesChip
                        key={level}
                        level={level}
                        material={notesOf(t.id, level)}
                        busy={jobRunning(jobFor('NOTES', `${t.id}:${level}`))}
                        canEdit={d.canEdit}
                        onStart={() => setNotesFor({ topic: t, level })}
                      />
                    ))}
                  </div>
                  <div>
                    {bank ? (
                      <Link to={`/mcq/${bank.resourceId}`} className="inline-flex items-center gap-2 text-sm hover:underline">
                        {bank.questionCount ?? 0} questions
                        {bank.latestStatus && <StatusPill tone={statusTone(bank.latestStatus as ReviewStatus)}>{STATUS_LABEL[bank.latestStatus as ReviewStatus]}</StatusPill>}
                      </Link>
                    ) : jobRunning(jobFor('MCQ', t.id)) ? (
                      <span className="text-sm text-muted">Writing questions…</span>
                    ) : d.canEdit && approvedLevels.length > 0 ? (
                      <Button size="sm" variant="ghost" icon={<Plus className="size-4" />} onClick={() => setQuestionsFor(t)}>
                        Write questions
                      </Button>
                    ) : (
                      <span className="text-sm text-faint">Questions need approved notes</span>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        </>
      )}

      {notesFor && <NotesDialog projectId={id} topic={notesFor.topic} level={notesFor.level} exists={!!notesOf(notesFor.topic.id, notesFor.level)} onClose={() => setNotesFor(null)} />}
      {questionsFor && (
        <QuestionsDialog projectId={id} topic={questionsFor} levels={LEVELS.filter((l) => notesOf(questionsFor.id, l)?.approvedNo)} hasBank={!!bankOf(questionsFor.id)} onClose={() => setQuestionsFor(null)} />
      )}
    </>
  )
}

const DOT_KEY: [string, string][] = [
  ['bg-emerald-500', 'Approved'],
  ['bg-marigold-500', 'In review'],
  ['bg-rose-signal', 'Outdated or sent back'],
  ['bg-[var(--text-faint)]', 'Draft'],
]

function NotesChip({ level, material, busy, canEdit, onStart }: { level: Level; material?: Material; busy: boolean; canEdit: boolean; onStart: () => void }) {
  const base = 'inline-flex items-center gap-1.5 rounded-full border px-3 h-8 text-sm'
  if (busy) return <span className={cn(base, 'border-line text-muted')}>{LEVEL_LABEL[level]}: writing…</span>
  if (material?.latestStatus) {
    const s = material.latestStatus as ReviewStatus
    return (
      <Link to={`/notes/${material.resourceId}`} className={cn(base, 'border-line hover:bg-surface-2')} title={`${LEVEL_LABEL[level]}: ${STATUS_LABEL[s]}`}>
        {LEVEL_LABEL[level]}
        <span className={cn('size-2 rounded-full', s === 'APPROVED' ? 'bg-emerald-500' : s === 'IN_REVIEW' ? 'bg-marigold-500' : s === 'OUTDATED' || s === 'REJECTED' ? 'bg-rose-signal' : 'bg-[var(--text-faint)]')} aria-hidden />
        <span className="sr-only">{STATUS_LABEL[s]}</span>
      </Link>
    )
  }
  if (!canEdit) return <span className={cn(base, 'border-dashed border-line text-faint')}>{LEVEL_LABEL[level]}</span>
  return (
    <button type="button" onClick={onStart} className={cn(base, 'border-dashed border-line text-muted hover:text-ink hover:bg-surface-2')}>
      <Plus className="size-3.5" aria-hidden />
      {LEVEL_LABEL[level]}
    </button>
  )
}

function JobLine({ job, topicTitle }: { job: MaterialJob; topicTitle: Map<string, string> }) {
  const [topicId, level] = job.target.split(':')
  const what = job.kind === 'NOTES' ? `${LEVEL_LABEL[level as Level] ?? ''} notes for ${topicTitle.get(topicId) ?? 'a topic'}` : `Questions for ${topicTitle.get(topicId) ?? 'a topic'}`
  return (
    <li className={cn('rounded-xl border px-4 py-2.5 text-sm', job.status === 'FAILED' ? 'border-rose-signal/40' : 'border-line bg-surface-2')}>
      {jobRunning(job) ? (
        <>
          {job.status === 'QUEUED' ? 'Waiting to write' : 'Writing'} {what.charAt(0).toLowerCase() + what.slice(1)}
          {job.startedBy ? `, started by ${job.startedBy}` : ''} {formatRelative(job.createdAt)}. This takes a minute or two{job.error ? `. ${job.error}` : '.'}
        </>
      ) : (
        <>
          {what} failed: {job.error ?? 'unknown error'}
        </>
      )}
    </li>
  )
}

function NotesDialog({ projectId, topic, level: initial, exists, onClose }: { projectId: string; topic: TopicRow; level: Level; exists: boolean; onClose: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [level, setLevel] = useState<Level>(initial)
  const [guidance, setGuidance] = useState('')
  const generate = useGenerateNotes(projectId)
  const byHand = useByHand(projectId, 'notes')
  const navigate = useNavigate()
  useDefaultAccount(usable, accountId, setAccountId)
  return (
    <Dialog
      open
      title={`Notes on ${topic.title}`}
      onClose={onClose}
      footer={
        <>
          {!exists && (
            <Button
              variant="ghost"
              loading={byHand.isPending}
              onClick={() => byHand.mutate({ topicId: topic.id, level }, { onSuccess: (s) => navigate(`/notes/${s.resource!.id}`), onError: (e) => toast.error(e.message) })}
            >
              Write by hand
            </Button>
          )}
          <Button
            variant="primary"
            loading={generate.isPending}
            disabled={!accountId || !model}
            onClick={() =>
              generate.mutate(
                { topicId: topic.id, level, accountId, model, guidance: guidance.trim() || undefined },
                { onSuccess: () => { toast.success('Writing the notes'); onClose() }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Write with AI
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Level">
          {(fid) => (
            <Select id={fid} value={level} onChange={(e) => setLevel(e.target.value as Level)}>
              {LEVELS.map((l) => (
                <option key={l} value={l}>
                  {LEVEL_LABEL[l]}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <p className="text-sm text-muted">
          The AI reads the pages the syllabus cites for this topic, and the best matches from your course documents, then writes the notes in its own words with sources.
          {exists && ' These notes exist already: a draft is rewritten, otherwise a new version is made.'}
        </p>
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account." />}
        <Field label="Guidance (optional)" hint="For example: “Use examples from Sri Lankan banking.”">
          {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
      </div>
    </Dialog>
  )
}

export function QuestionsDialog({ projectId, topic, levels, hasBank, onClose }: { projectId: string; topic: TopicRow; levels: Level[]; hasBank: boolean; onClose: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const [notesLevel, setNotesLevel] = useState<Level>(levels[0] ?? 'EXAM')
  const [count, setCount] = useState(10)
  const [difficulties, setDifficulties] = useState<string[]>([])
  const [styles, setStyles] = useState<string[]>([])
  const [allowAllNone, setAllowAllNone] = useState(false)
  const [guidance, setGuidance] = useState('')
  const generate = useGenerateQuestions(projectId)
  const byHand = useByHand(projectId, 'mcq')
  const navigate = useNavigate()
  useDefaultAccount(usable, accountId, setAccountId)
  const toggle = (list: string[], set: (v: string[]) => void, v: string) => set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v])
  return (
    <Dialog
      open
      title={`Questions on ${topic.title}`}
      onClose={onClose}
      footer={
        <>
          {!hasBank && (
            <Button variant="ghost" loading={byHand.isPending} onClick={() => byHand.mutate({ topicId: topic.id }, { onSuccess: (s) => navigate(`/mcq/${s.resource!.id}`), onError: (e) => toast.error(e.message) })}>
              Start an empty bank
            </Button>
          )}
          <Button
            variant="primary"
            loading={generate.isPending}
            disabled={!accountId || !model || count < 1 || count > 30}
            onClick={() =>
              generate.mutate(
                { topicId: topic.id, notesLevel, count, difficulties, styles, accountId, model, guidance: guidance.trim() || undefined, allowAllNone },
                { onSuccess: () => { toast.success('Writing the questions'); onClose() }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Write questions
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="From the approved notes">
            {(fid) => (
              <Select id={fid} value={notesLevel} onChange={(e) => setNotesLevel(e.target.value as Level)}>
                {levels.map((l) => (
                  <option key={l} value={l}>
                    {LEVEL_LABEL[l]}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="How many" hint="1 to 30 at a time">
            {(fid, hid) => <Input id={fid} aria-describedby={hid} type="number" min={1} max={30} value={count} onChange={(e) => setCount(Number(e.target.value))} />}
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
          <p className="text-sm text-muted mt-1">Leave both empty for a mix.</p>
        </fieldset>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-0.5 size-4 accent-marigold-600" checked={allowAllNone} onChange={(e) => setAllowAllNone(e.target.checked)} />
          <span>Allow “all of the above” and “none of the above” options in this bank</span>
        </label>
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account." />}
        <Field label="Guidance (optional)">
          {(fid) => <Textarea id={fid} rows={2} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
        <p className="text-sm text-muted">Every question is checked: four different options, one key, an explanation, and no repeats. Ones that fail are left out and counted.</p>
      </div>
    </Dialog>
  )
}
