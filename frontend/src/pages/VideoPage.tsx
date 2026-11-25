import { Player } from '@remotion/player'
import { CheckCircle2, ChevronLeft, CircleAlert, Download, Mic, Clapperboard } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import { LEVEL_LABEL, type Level } from '../lib/materials'
import { STYLE_KEYS, STYLES } from '../lib/slides'
import { useVoices } from '../lib/studioQueries'
import {
  characters,
  mediaRefs,
  minutes,
  SCENE_LABEL,
  speechSeconds,
  useNarrate,
  useStartRender,
  useVideoPreview,
  useVideoRenders,
  useVideosOverview,
  type VideoPlan,
  type VideoRender,
  type VideoScene,
} from '../lib/videos'
import { timelineDuration, toFrames } from '../remotion/timeline'
import { TimelineVideo } from '../remotion/TimelineVideo'
import { toast } from '../stores/ui'

/** /videos/:resourceId — one lesson or quiz video: voice, narration, preview, render, file checks and review. */
export function VideoPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<VideoPlan>({ resourceId })
  const plan = page.content
  const d = page.detail
  const projectId = page.state.data?.resource?.projectId
  const overview = useVideosOverview(projectId)
  const narrating = overview.data?.topics.some((t) => t.videos.some((v) => v.resourceId === resourceId && v.running)) ?? false
  const narrationError = overview.data?.topics.flatMap((t) => t.videos).find((v) => v.resourceId === resourceId)?.error
  const narrate = useNarrate(resourceId, projectId)
  const renders = useVideoRenders(resourceId, d?.version.id)
  const startRender = useStartRender(resourceId)
  const hasNarration = !!plan?.narration && !page.dirty
  const preview = useVideoPreview(resourceId, d?.version.id, hasNarration)

  // when narration finishes, show the narrated draft
  const wasNarrating = useRef(false)
  const refetch = page.state.refetch
  useEffect(() => {
    if (narrating) wasNarrating.current = true
    else if (wasNarrating.current) {
      wasNarrating.current = false
      refetch()
    }
  }, [narrating, refetch])

  if (page.loading) return <Spinner label="Opening the video" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  if (!state.resource || !d || !plan) return <ErrorNotice error={new Error('This video has no versions')} />
  const r = state.resource
  const edit = page.editable
  const draft = d.version.status === 'DRAFT'
  const set = (patch: Partial<VideoPlan>) => page.setContent({ ...plan, ...patch })
  const setScene = (id: string, patch: Partial<VideoScene>) => set({ scenes: plan.scenes.map((s) => (s.id === id ? { ...s, ...patch } : s)) })
  const forThis = (renders.data ?? []).filter((x) => x.forThisContent)
  const checked = forThis.find((x) => x.status === 'COMPLETED' && x.validation?.ok)
  const rendering = forThis.some((x) => x.status === 'QUEUED' || x.status === 'RENDERING')
  const name = plan.kind === 'LESSON' ? `${LEVEL_LABEL[plan.level as Level] ?? plan.level} lesson video` : 'Quiz video'

  return (
    <>
      <Link to={`/projects/${r.projectId}/videos`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Lesson and quiz videos
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{plan.title || plan.topicTitle}</h1>
        <p className="text-muted mt-1">
          {name} for {plan.topicTitle}. {plan.scenes.length} scenes, {plan.aspect === '9:16' ? '9:16 for Shorts' : '16:9'}, about {minutes(speechSeconds(plan))} of speech
        </p>
      </header>

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-8">
          {page.dirty && draft && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes. Saving a change to the words or the voice means making the narration again.</span>
              <Button variant="ghost" onClick={page.discard}>Discard</Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>Save draft</Button>
            </div>
          )}

          <Steps plan={plan} narrating={narrating} checked={!!checked} rendering={rendering} />

          <section aria-labelledby="look-h" className="space-y-4">
            <h2 id="look-h" className="text-lg font-semibold">Voice and look</h2>
            <VoiceChooser plan={plan} edit={edit} onChange={(voice) => set({ voice })} />
            <div className="grid sm:grid-cols-3 gap-4">
              <Field label="Style">
                {(fid) => (
                  <Select id={fid} value={plan.style} disabled={!edit} onChange={(e) => set({ style: e.target.value })}>
                    {STYLE_KEYS.map((k) => <option key={k} value={k}>{STYLES[k].name}</option>)}
                  </Select>
                )}
              </Field>
              <Field label="Shape">
                {(fid) => (
                  <Select id={fid} value={plan.aspect} disabled={!edit} onChange={(e) => set({ aspect: e.target.value as '16:9' | '9:16' })}>
                    <option value="16:9">16:9 (YouTube)</option>
                    <option value="9:16">9:16 (Shorts)</option>
                  </Select>
                )}
              </Field>
              {plan.kind === 'QUIZ' && (
                <Field label="Countdown (seconds)">
                  {(fid) => <Input id={fid} type="number" min={3} max={30} disabled={!edit} value={plan.quiz?.countdown ?? 5}
                    onChange={(e) => set({ quiz: { countdown: Number(e.target.value) } })} />}
                </Field>
              )}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" disabled={!edit} checked={plan.captions !== false} onChange={(e) => set({ captions: e.target.checked })} className="size-4 accent-[var(--color-marigold-500)]" />
              Captions burned into the video
            </label>
          </section>

          <section aria-labelledby="narration-h" className="space-y-3">
            <h2 id="narration-h" className="text-lg font-semibold">Narration</h2>
            <p className="text-sm text-muted">
              {characters(plan).toLocaleString()} characters to speak.{' '}
              {plan.narration
                ? `Made with ${plan.narration.voiceLabel ?? 'the chosen voice'}.`
                : narrating ? 'Being made now…' : 'Not made yet, or the words or voice changed since.'}
            </p>
            {narrationError && !narrating && <p role="alert" className="text-sm text-rose-signal">The last attempt failed: {narrationError}</p>}
            {draft && d.you.canEdit && (
              <Button icon={<Mic className="size-4" />} loading={narrate.isPending || narrating} disabled={page.dirty || !plan.voice}
                title={page.dirty ? 'Save the draft first' : !plan.voice ? 'Choose a voice first' : undefined}
                onClick={() => narrate.mutate(d.version.id, { onSuccess: () => toast.success('Making the narration'), onError: (e) => toast.error(e.message) })}>
                {plan.narration ? 'Make the narration again' : 'Make the narration'}
              </Button>
            )}
          </section>

          <section aria-labelledby="preview-h" className="space-y-3">
            <h2 id="preview-h" className="text-lg font-semibold">Preview</h2>
            {!hasNarration ? (
              <p className="text-sm text-muted">The preview appears once the narration is made{page.dirty ? ' and the draft is saved' : ''}.</p>
            ) : preview.isPending ? (
              <Spinner label="Building the preview" />
            ) : preview.error ? (
              <p role="alert" className="text-sm text-rose-signal">{preview.error.message}</p>
            ) : (
              <VideoPreviewPlayer doc={preview.data!.document} media={preview.data!.media} />
            )}
          </section>

          <section aria-labelledby="scenes-h" className="space-y-3">
            <h2 id="scenes-h" className="text-lg font-semibold">Scenes</h2>
            <ol className="space-y-3">
              {plan.scenes.map((s, i) => (
                <li key={s.id} className="rounded-xl border border-line p-4">
                  <p className="text-sm text-muted mb-1">{i + 1}. {SCENE_LABEL[s.kind]}</p>
                  <p className="font-medium break-words">{s.title}</p>
                  {s.question && (
                    <ul className="text-sm mt-2 grid sm:grid-cols-2 gap-x-4">
                      {s.question.options.map((o, j) => (
                        <li key={j} className={cn(j === s.question!.answer && 'font-semibold text-emerald-700')}>{'ABCD'[j]}. {o}{j === s.question!.answer ? ' (answer)' : ''}</li>
                      ))}
                    </ul>
                  )}
                  {edit ? (
                    <div className="mt-3 space-y-3">
                      <Field label={s.kind === 'QUESTION' ? 'Read aloud' : 'Narration'}>
                        {(fid) => <Textarea id={fid} rows={3} maxLength={5000} value={s.narration ?? ''} onChange={(e) => setScene(s.id, { narration: e.target.value })} />}
                      </Field>
                      {s.kind === 'QUESTION' && (
                        <Field label="After the answer is shown">
                          {(fid) => <Textarea id={fid} rows={2} maxLength={5000} value={s.answerNarration ?? ''} onChange={(e) => setScene(s.id, { answerNarration: e.target.value })} />}
                        </Field>
                      )}
                    </div>
                  ) : (
                    <div className="mt-2 text-sm space-y-1">
                      <p className="whitespace-pre-line">{s.narration}</p>
                      {s.answerNarration && <p className="whitespace-pre-line text-muted">Then: {s.answerNarration}</p>}
                    </div>
                  )}
                </li>
              ))}
            </ol>
            <p className="text-sm text-muted">Slides and questions show exactly as approved. To change one, change the presentation or the MCQ set and plan the video again.</p>
          </section>
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel detail={d} kindLabel={plan.kind === 'LESSON' ? 'lesson video' : 'quiz video'} dirty={page.dirty} edited={plan} saveFirst={page.saveDraft}
            editing={false} allowReviewerEdits={false} onReviewerEdit={() => undefined} onEdit={page.startDraft}
            originLabel="planned from approved content" />
          <RenderPanel renders={renders.data ?? []} canRender={d.you.canEdit && ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED'].includes(d.version.status)}
            ready={hasNarration} available={overview.data?.rendererAvailable ?? true} vertical={plan.aspect === '9:16'} dirty={page.dirty}
            starting={startRender.isPending}
            onRender={(resolution) => startRender.mutate({ versionId: d.version.id, resolution }, {
              onSuccess: () => toast.success('Rendering. The file is checked when it’s done.'), onError: (e) => toast.error(e.message) })} />
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

function Steps({ plan, narrating, checked, rendering }: { plan: VideoPlan; narrating: boolean; checked: boolean; rendering: boolean }) {
  const steps = [
    { label: 'Voice chosen', done: !!plan.voice },
    { label: narrating ? 'Narrating…' : 'Narration made', done: !!plan.narration },
    { label: rendering ? 'Rendering…' : 'Rendered and checked', done: checked },
  ]
  return (
    <ol aria-label="Progress" className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
      {steps.map((s) => (
        <li key={s.label} className={cn('inline-flex items-center gap-1.5', s.done ? 'text-ink' : 'text-muted')}>
          {s.done ? <CheckCircle2 className="size-4 text-emerald-600" aria-hidden /> : <span className="size-4 rounded-full border border-line" aria-hidden />}
          {s.label}
          <span className="sr-only">{s.done ? ', done' : ', to do'}</span>
        </li>
      ))}
    </ol>
  )
}

/** Account and voice (or a saved profile), with the price where the provider has one per voice. */
function VoiceChooser({ plan, edit, onChange }: { plan: VideoPlan; edit: boolean; onChange: (v: VideoPlan['voice']) => void }) {
  const { usable, isPending } = useUsableAccounts('VOICE')
  const [accountId, setAccountId] = useState(plan.voice?.accountId ?? '')
  useDefaultAccount(usable, accountId, setAccountId)
  const voices = useVoices(accountId || undefined)
  const lang = plan.language === 'TA' ? 'ta' : 'en'
  // voices in the narration's language first; the others stay available (notes can be in either)
  const list = useMemo(() => {
    const all = voices.data ?? []
    const first = (v: { language?: string }) => (!v.language || v.language.toLowerCase().startsWith(lang) ? 0 : 1)
    return [...all].sort((a, b) => first(a) - first(b))
  }, [voices.data, lang])
  const account = usable.find((a) => a.id === accountId)
  const chosen = list.find((v) => v.id === plan.voice?.voiceId)
  if (isPending) return <Spinner label="Loading voice accounts" />
  if (usable.length === 0) return <p className="text-sm text-muted">Add a voice account (ElevenLabs, Google Gemini or Google Cloud Text-to-Speech) under AI accounts first.</p>
  return (
    <div className="grid sm:grid-cols-[1fr_1.4fr_7rem] gap-4">
      <Field label="Voice account">
        {(fid) => (
          <Select id={fid} value={accountId} disabled={!edit} onChange={(e) => setAccountId(e.target.value)}>
            {usable.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.providerName})</option>)}
          </Select>
        )}
      </Field>
      <Field label="Voice" hint={chosen?.description ?? (plan.voice?.label ? `Chosen: ${plan.voice.label}` : undefined)} error={voices.error?.message}>
        {(fid, hid) => (
          <Select id={fid} aria-describedby={hid} disabled={!edit || voices.isPending} value={plan.voice?.accountId === accountId ? (plan.voice?.voiceId ?? '') : ''}
            onChange={(e) => {
              const v = list.find((x) => x.id === e.target.value)
              onChange(v ? { accountId, voiceId: v.id, speed: plan.voice?.speed ?? null, label: `${v.name}${v.category ? `, ${v.category}` : ''} (${account?.providerName ?? ''})` } : null)
            }}>
            <option value="">{voices.isPending ? 'Loading voices…' : 'Choose a voice'}</option>
            {list.map((v) => <option key={v.id} value={v.id}>{v.name}{v.category ? ` (${v.category})` : ''}{v.gender ? `, ${v.gender}` : ''}</option>)}
          </Select>
        )}
      </Field>
      <Field label="Speed">
        {(fid) => (
          <Select id={fid} disabled={!edit || !plan.voice} value={String(plan.voice?.speed ?? 1)}
            onChange={(e) => plan.voice && onChange({ ...plan.voice, speed: Number(e.target.value) === 1 ? null : Number(e.target.value) })}>
            {[0.85, 0.9, 1, 1.1, 1.2].map((x) => <option key={x} value={x}>{x}×</option>)}
          </Select>
        )}
      </Field>
    </div>
  )
}

function VideoPreviewPlayer({ doc, media }: { doc: import('../remotion/types').TimelineDoc; media: Record<string, import('../lib/types').MediaAsset> }) {
  const refs = useMemo(() => mediaRefs(media), [media])
  const frames = Math.max(1, toFrames(timelineDuration(doc), doc.fps))
  const vertical = doc.height > doc.width
  return (
    <div className={cn('rounded-xl overflow-hidden bg-[#0b0d18]', vertical ? 'max-w-sm mx-auto' : '')}>
      <Player component={TimelineVideo} inputProps={{ timeline: doc, media: refs }} durationInFrames={frames} fps={doc.fps}
        compositionWidth={doc.width} compositionHeight={doc.height} style={{ width: '100%', aspectRatio: `${doc.width} / ${doc.height}` }}
        initialFrame={Math.min(frames - 1, doc.fps * 2)} controls acknowledgeRemotionLicense numberOfSharedAudioTags={8}
        errorFallback={({ error }: { error: Error }) => <p className="text-white text-sm p-4">Preview error: {error.message}</p>} />
    </div>
  )
}

const STATUS: Record<VideoRender['status'], string> = { QUEUED: 'Waiting', RENDERING: 'Rendering', COMPLETED: 'Rendered', FAILED: 'Failed', CANCELLED: 'Cancelled' }

function RenderPanel({ renders, canRender, ready, available, vertical, dirty, starting, onRender }: {
  renders: VideoRender[]; canRender: boolean; ready: boolean; available: boolean; vertical: boolean; dirty: boolean; starting: boolean; onRender: (res: number) => void
}) {
  const [res, setRes] = useState(1080)
  return (
    <section aria-labelledby="render-h" className="space-y-3">
      <h2 id="render-h" className="font-semibold">Render</h2>
      {canRender && (
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Size">
              {(fid) => (
                <Select id={fid} value={res} onChange={(e) => setRes(Number(e.target.value))}>
                  <option value={720}>{vertical ? '720 × 1280' : '1280 × 720'}</option>
                  <option value={1080}>{vertical ? '1080 × 1920' : '1920 × 1080'}</option>
                  <option value={2160}>{vertical ? '2160 × 3840 (4K, slow)' : '3840 × 2160 (4K, slow)'}</option>
                </Select>
              )}
            </Field>
          </div>
          <Button icon={<Clapperboard className="size-4" />} loading={starting} disabled={!ready || !available || dirty} onClick={() => onRender(res)}
            title={!available ? 'The render service isn’t reachable' : !ready ? 'Make the narration first' : dirty ? 'Save first' : undefined}>
            Render
          </Button>
        </div>
      )}
      <p className="text-xs text-muted">Every file is checked with ffprobe: its length, size, frame rate and frames, H.264 picture and AAC sound that isn’t silent. Only a render of this exact version that passes can be approved.</p>
      {renders.length > 0 && (
        <ul className="space-y-2">
          {renders.slice(0, 6).map((x) => (
            <li key={x.renderJobId} className={cn('rounded-lg border p-3 text-sm', x.forThisContent ? 'border-line' : 'border-line/60 text-muted')}>
              <p className="flex items-center gap-2">
                {x.status === 'COMPLETED' && x.validation?.ok ? <CheckCircle2 className="size-4 text-emerald-600" aria-hidden />
                  : x.status === 'FAILED' ? <CircleAlert className="size-4 text-rose-signal" aria-hidden /> : null}
                <span className="font-medium">{STATUS[x.status]}{x.status === 'RENDERING' ? ` ${Math.round(x.progress * 100)}%` : ''}</span>
                <span className="text-muted">version {x.versionNo}{x.forThisContent ? '' : ', earlier content'}</span>
              </p>
              {x.validation?.ok && (
                <p className="text-muted mt-1">
                  Checked: {x.validation.found.width}×{x.validation.found.height}, {minutes(x.validation.found.durationSeconds ?? 0)}, {Math.round(x.validation.found.fps ?? 0)} fps
                  {x.validation.found.audioCodec ? ', sound' : ''}
                </p>
              )}
              {x.error && <p className="text-rose-signal mt-1 break-words">{x.error}</p>}
              {x.output && (
                <a href={x.output.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 mt-1 underline underline-offset-2">
                  <Download className="size-3.5" aria-hidden /> Open the MP4
                </a>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
