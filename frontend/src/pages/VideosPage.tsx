import { Check, ChevronLeft, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill } from '../components/ui'
import { cn } from '../lib/cn'
import { LEVEL_LABEL, type Level } from '../lib/materials'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import { useCreateVideo, useVideosOverview, type TopicVideos, type VideoInfo, type VideoKind } from '../lib/videos'
import { toast } from '../stores/ui'

/**
 * /projects/:id/videos — lesson videos (from approved presentations) and quiz videos (from
 * approved MCQ sets, once the SEO report is approved), per topic.
 */
export function VideosPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useVideosOverview(id)
  const [making, setMaking] = useState<{ topic: TopicVideos; kind: VideoKind } | null>(null)

  if (project.isPending || data.isPending) return <Spinner label="Loading videos" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const d = data.data
  return (
    <>
      <Link to={`/projects/${id}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <PageHeader
        title="Lesson and quiz videos"
        description="A lesson video follows an approved presentation, narrated from its speaker notes. A quiz video reads the approved questions, counts down and reveals each answer. Each is narrated, rendered, and checked before it can be approved."
        actions={<Link to={`/projects/${id}/youtube`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface text-ink px-4 h-10 hover:bg-surface-2 transition-colors">Publish to YouTube</Link>}
      />
      {!d.rendererAvailable && (
        <p role="status" className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm mb-4">
          The render service isn’t reachable, so videos can be planned and narrated but not rendered yet.
        </p>
      )}
      {!d.topicsApproved || d.topics.length === 0 ? (
        <EmptyState title="The topic list isn't approved yet" body="Videos are made per approved topic."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/topics`}>Go to topics</Link>} />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {d.topics.map((t) => {
            const lessonReady = t.approvedDecks.length > 0
            const quizReady = t.mcqApproved && t.seoApproved
            return (
              <li key={t.topicId} className="py-4 grid gap-3 lg:grid-cols-[minmax(0,18rem)_1fr_auto] lg:items-start">
                <div style={{ paddingLeft: `${(t.depth - 1) * 1.25}rem` }}>
                  <p className={cn('break-words', t.depth === 1 ? 'font-semibold' : 'font-medium')}>{t.title}</p>
                  <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted" aria-label="Approvals">
                    <Gate ok={lessonReady} label={lessonReady ? `Presentation: ${t.approvedDecks.map((l) => LEVEL_LABEL[l as Level] ?? l).join(', ')}` : 'Presentation'} />
                    <Gate ok={t.mcqApproved} label="MCQ set" />
                    <Gate ok={t.seoApproved} label="SEO report" />
                  </ul>
                </div>
                <div className="text-sm min-w-0 space-y-1">
                  {t.videos.length === 0 ? (
                    <p className="text-muted">{lessonReady || quizReady ? 'No videos yet' : 'Needs the approvals on the left'}</p>
                  ) : (
                    t.videos.map((v) => <VideoLine key={v.resourceId} v={v} />)
                  )}
                </div>
                {d.canEdit && (
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" variant="secondary" disabled={!lessonReady} title={lessonReady ? undefined : 'Approve a presentation first'}
                      onClick={() => setMaking({ topic: t, kind: 'LESSON' })}>
                      Lesson video
                    </Button>
                    <Button size="sm" variant="secondary" disabled={!quizReady} title={quizReady ? undefined : 'Approve the MCQ set and the SEO report first'}
                      onClick={() => setMaking({ topic: t, kind: 'QUIZ' })}>
                      Quiz video
                    </Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {making && <PlanDialog projectId={id} topic={making.topic} kind={making.kind} onClose={() => setMaking(null)} />}
    </>
  )
}

const RENDER_LABEL: Record<string, string> = {
  CHECKED: 'rendered and checked', QUEUED: 'waiting to render', RENDERING: 'rendering', COMPLETED: 'rendered', FAILED: 'render failed', CANCELLED: 'render cancelled',
}

function VideoLine({ v }: { v: VideoInfo }) {
  const name = v.kind === 'LESSON' ? `${LEVEL_LABEL[v.level as Level] ?? v.level} lesson video` : 'Quiz video'
  return (
    <div>
      <Link to={`/videos/${v.resourceId}`} className="inline-flex flex-wrap items-center gap-2 hover:underline">
        {name}, version {v.versionNo}
        {v.status && <StatusPill tone={statusTone(v.status as ReviewStatus)}>{STATUS_LABEL[v.status as ReviewStatus]}</StatusPill>}
        <span className="text-muted">{v.running ? 'narrating…' : v.renderStatus ? RENDER_LABEL[v.renderStatus] ?? v.renderStatus : 'not rendered'}</span>
      </Link>
      {v.error && !v.running && <p className="text-rose-signal">Narration failed. {v.error}</p>}
    </div>
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

function PlanDialog({ projectId, topic, kind, onClose }: { projectId: string; topic: TopicVideos; kind: VideoKind; onClose: () => void }) {
  const navigate = useNavigate()
  const create = useCreateVideo(projectId)
  const [level, setLevel] = useState(topic.approvedDecks[0] ?? '')
  const [language, setLanguage] = useState<'EN' | 'TA'>('EN')
  const [aspect, setAspect] = useState<'16:9' | '9:16'>(kind === 'QUIZ' ? '9:16' : '16:9')
  const [countdown, setCountdown] = useState(5)
  const [questions, setQuestions] = useState(10)
  const existing = topic.videos.find((v) => v.kind === kind && (kind === 'QUIZ' || v.level === level))
  const badCount = kind === 'QUIZ' && (!(countdown >= 3 && countdown <= 30) || !(questions >= 1 && questions <= 30))
  return (
    <Dialog
      open
      title={`${kind === 'LESSON' ? 'Lesson video' : 'Quiz video'}: ${topic.title}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={create.isPending} disabled={(kind === 'LESSON' && !level) || existing?.status === 'IN_REVIEW' || badCount}
            onClick={() => create.mutate(
              { kind, topicId: topic.topicId, level: kind === 'LESSON' ? level : undefined, language, aspect, countdown: kind === 'QUIZ' ? countdown : undefined, questions: kind === 'QUIZ' ? questions : undefined },
              { onSuccess: (r) => { toast.success('Video planned'); navigate(`/videos/${r.resourceId}`) }, onError: (e) => toast.error(e.message) },
            )}>
            Plan the video
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">
        {kind === 'LESSON'
          ? 'One scene per slide, narrated from its approved speaker notes, with an introduction and an ending. Nothing is written by AI.'
          : 'Each approved question is read aloud with its four options, followed by a countdown, the answer and its explanation. Nothing is written by AI.'}
        {' '}You choose the voice and can edit the narration before it’s spoken.
        {existing?.status === 'DRAFT' && ' The current draft will be replaced.'}
        {existing?.status === 'IN_REVIEW' && ' This video is in review; withdraw it first.'}
      </p>
      <div className="grid sm:grid-cols-2 gap-4">
        {kind === 'LESSON' && (
          <Field label="Presentation">
            {(fid) => (
              <Select id={fid} value={level} onChange={(e) => setLevel(e.target.value)}>
                {topic.approvedDecks.map((l) => <option key={l} value={l}>{LEVEL_LABEL[l as Level] ?? l} slides</option>)}
              </Select>
            )}
          </Field>
        )}
        <Field label="Narration language" hint="For the fixed linking sentences; the notes and questions are read as written">
          {(fid, hid) => (
            <Select id={fid} aria-describedby={hid} value={language} onChange={(e) => setLanguage(e.target.value as 'EN' | 'TA')}>
              <option value="EN">English</option>
              <option value="TA">Tamil</option>
            </Select>
          )}
        </Field>
        <Field label="Shape">
          {(fid) => (
            <Select id={fid} value={aspect} onChange={(e) => setAspect(e.target.value as '16:9' | '9:16')}>
              <option value="16:9">16:9 (YouTube)</option>
              <option value="9:16">9:16 (Shorts)</option>
            </Select>
          )}
        </Field>
        {kind === 'QUIZ' && (
          <>
            <Field label="Countdown (seconds)" error={!(countdown >= 3 && countdown <= 30) ? 'Use 3 to 30' : undefined}>
              {(fid) => <Input id={fid} type="number" min={3} max={30} value={countdown} onChange={(e) => setCountdown(Number(e.target.value))} />}
            </Field>
            <Field label="Questions, at most" error={!(questions >= 1 && questions <= 30) ? 'Use 1 to 30' : undefined}>
              {(fid) => <Input id={fid} type="number" min={1} max={30} value={questions} onChange={(e) => setQuestions(Number(e.target.value))} />}
            </Field>
          </>
        )}
      </div>
    </Dialog>
  )
}
