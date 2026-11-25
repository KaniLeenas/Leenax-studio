import {
  ArrowDown,
  ArrowUp,
  ChevronLeft,
  Copy,
  Film,
  ImageIcon,
  ImagePlus,
  Library,
  Loader2,
  Mic,
  Plus,
  Settings2,
  Sparkles,
  Trash2,
  Video,
  X,
} from 'lucide-react'
import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { MediaPicker } from '../components/MediaPicker'
import { MediaThumb } from '../components/media'
import { Card, ImageModelPicker, MediaField, Slider, useDefaultAccount, useUsableAccounts, VideoModelPicker } from '../components/studio'
import { Button, Dialog, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { useProjects } from '../lib/queries'
import {
  ANGLES,
  boardProgress,
  dialogueText,
  FORMAT_CLASS,
  FORMAT_LABEL,
  FORMATS,
  formatSeconds,
  isVideoRunning,
  MOVES,
  nearestDuration,
  parseDialogue,
  type Character,
  type Scene,
  type SceneInput,
  type Storyboard,
} from '../lib/story'
import { useCharacters, useDeleteStoryboard, useExportStoryboard, useSceneAction, useStoryboard, useUpdateStoryboard } from '../lib/storyQueries'
import { useVoiceProfiles } from '../lib/studioQueries'
import type { AiModelInfo, GenerationMode, Language, MediaAsset, VideoFormat } from '../lib/types'
import { toast } from '../stores/ui'

/** The image and video generators chosen for this storyboard (kept while the page is open). */
interface Generators {
  imageAccountId: string
  imageModel: string
  videoAccountId: string
  videoModelId: string
  videoModel?: AiModelInfo
  resolution?: string
  audio: boolean
}

export function StoryboardEditorPage() {
  const { id = '' } = useParams()
  const board = useStoryboard(id)
  if (board.isPending) return <Spinner label="Opening storyboard" />
  if (board.error) {
    return (
      <>
        <BackLink />
        <ErrorNotice error={board.error} onRetry={() => board.refetch()} />
      </>
    )
  }
  return <Editor board={board.data} />
}

function BackLink() {
  return (
    <Link to="/create/storyboard" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
      <ChevronLeft className="size-4" aria-hidden />
      Storyboards
    </Link>
  )
}

function Editor({ board }: { board: Storyboard }) {
  const imageAccounts = useUsableAccounts('IMAGE')
  const videoAccounts = useUsableAccounts('VIDEO')
  const [gen, setGen] = useState<Generators>({ imageAccountId: '', imageModel: '', videoAccountId: '', videoModelId: '', audio: false })
  const set = useCallback(<K extends keyof Generators>(k: K) => (v: Generators[K]) => setGen((g) => ({ ...g, [k]: v })), [])
  const setImageAccount = useMemo(() => set('imageAccountId'), [set])
  const setImageModel = useMemo(() => set('imageModel'), [set])
  const setVideoAccount = useMemo(() => set('videoAccountId'), [set])
  const setVideoModelId = useMemo(() => set('videoModelId'), [set])
  const onVideoModel = useCallback(
    (m?: AiModelInfo) =>
      setGen((g) =>
        g.videoModel?.id === m?.id
          ? g
          : { ...g, videoModel: m, resolution: m?.options.resolutions?.[0]?.value, audio: !!m?.options.audio && g.audio },
      ),
    [],
  )
  useDefaultAccount(imageAccounts.usable, gen.imageAccountId, setImageAccount)
  useDefaultAccount(videoAccounts.usable, gen.videoAccountId, setVideoAccount)

  const characters = useCharacters(board.projectId)
  const action = useSceneAction()
  const [dialog, setDialog] = useState<'settings' | 'narrate' | 'export' | 'delete' | 'batch-images' | 'batch-videos'>()
  const [batch, setBatch] = useState<{ done: number; total: number }>()
  const progress = boardProgress(board)
  const ta = board.language === 'TA' ? 'ta' : undefined

  const missingImages = board.scenes.filter((s) => !s.image)
  const missingVideos = board.scenes.filter((s) => !s.video && !isVideoRunning(s.videoJob) && videoMode(s, gen.videoModel))

  async function runBatch(scenes: Scene[], kind: 'image' | 'video') {
    setDialog(undefined)
    setBatch({ done: 0, total: scenes.length })
    let failed = 0
    for (let i = 0; i < scenes.length; i++) {
      try {
        if (kind === 'image') await action.mutateAsync({ kind: 'image', sceneId: scenes[i].id, accountId: gen.imageAccountId, model: gen.imageModel })
        else await action.mutateAsync(videoAction(scenes[i], gen)!)
      } catch (e) {
        failed++
        if (failed === 1) toast.error(`Scene ${scenes[i].position + 1}: ${(e as Error).message}`)
      }
      setBatch({ done: i + 1, total: scenes.length })
    }
    setBatch(undefined)
    if (failed === 0) toast.success(kind === 'image' ? `${scenes.length} images made` : `${scenes.length} videos started. They appear as each one finishes.`)
    else toast.error(`${failed} of ${scenes.length} didn't work. The others are done.`)
  }

  return (
    <>
      <BackLink />
      <header className="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div className="min-w-0">
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words" lang={ta}>
            {board.title}
          </h1>
          <p className="text-muted mt-1">
            {FORMAT_LABEL[board.aspectRatio]}, {board.language === 'TA' ? 'Tamil' : 'English'}, {board.scenes.length} {board.scenes.length === 1 ? 'scene' : 'scenes'},{' '}
            {formatSeconds(board.totalSeconds)} planned
            {board.scriptId && (
              <>
                {', '}
                <Link to={`/create/scripts?id=${board.scriptId}`} className="underline hover:text-ink">
                  from a script
                </Link>
              </>
            )}
          </p>
          {board.style && <p className="text-sm text-faint mt-1">Look: {board.style}</p>}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button icon={<Settings2 className="size-4" />} onClick={() => setDialog('settings')}>
            Settings
          </Button>
          <Button icon={<Mic className="size-4" />} onClick={() => setDialog('narrate')} disabled={!board.scenes.some((s) => s.narration.trim())}>
            {board.narration ? 'Re-record narration' : 'Narrate'}
          </Button>
          <Button variant="primary" icon={<Film className="size-4" />} onClick={() => setDialog('export')} disabled={board.scenes.length === 0}>
            Send to editor
          </Button>
        </div>
      </header>

      <div className="grid xl:grid-cols-[minmax(0,1fr)_22rem] gap-6 items-start">
        <div className="flex flex-col gap-4 min-w-0">
          {board.scenes.length === 0 && (
            <div className="rounded-2xl border border-dashed border-line p-8 text-center">
              <p className="font-medium">No scenes yet</p>
              <p className="text-sm text-muted mt-1">Add the first scene, or start a storyboard from a script to have them planned.</p>
            </div>
          )}
          {board.scenes.map((s, i) => (
            <SceneCard
              key={`${s.id}:${editableKey(s)}`}
              scene={s}
              board={board}
              index={i}
              count={board.scenes.length}
              characters={characters.data ?? []}
              gen={gen}
              busy={!!batch}
            />
          ))}
          <div>
            <Button
              icon={<Plus className="size-4" />}
              loading={action.isPending && action.variables?.kind === 'add'}
              disabled={board.scenes.length >= 60}
              onClick={() => action.mutate({ kind: 'add', boardId: board.id }, { onError: (e) => toast.error(e.message) })}
            >
              Add scene
            </Button>
          </div>
        </div>

        <aside className="flex flex-col gap-4 order-first xl:order-none xl:sticky xl:top-6" aria-label="Generators and progress">
          <Card label="Progress">
            <h2 className="font-semibold">Progress</h2>
            <dl className="grid grid-cols-2 gap-y-1.5 text-sm">
              <dt className="text-muted">Images</dt>
              <dd className="tabular text-right">
                {progress.images} of {progress.scenes}
              </dd>
              <dt className="text-muted">Videos</dt>
              <dd className="tabular text-right">
                {progress.videos} of {progress.scenes}
                {progress.running > 0 && <span className="block text-xs text-faint">{progress.running} being made</span>}
              </dd>
              <dt className="text-muted">Narration</dt>
              <dd className="text-right">{progress.voiced ? 'Recorded' : 'Not yet'}</dd>
            </dl>
            {board.narration && <audio controls preload="none" src={board.narration.url} className="w-full" aria-label="Narration" />}
            {board.timelineId && (
              <Link to={`/edit/timeline/${board.timelineId}`} className="text-sm underline text-muted hover:text-ink">
                Open the last timeline sent to the editor
              </Link>
            )}
            {batch && (
              <p className="text-sm flex items-center gap-2" role="status">
                <Loader2 className="size-4 animate-spin" aria-hidden />
                Working on {batch.done + 1 > batch.total ? batch.total : batch.done + 1} of {batch.total}
              </p>
            )}
          </Card>

          <Card label="Image generator">
            <h2 className="font-semibold flex items-center gap-2">
              <ImageIcon className="size-4" aria-hidden /> Scene images
            </h2>
            <ImageModelPicker
              accounts={imageAccounts.usable}
              accountId={gen.imageAccountId}
              setAccountId={setImageAccount}
              model={gen.imageModel}
              setModel={setImageModel}
              stacked
            />
            {imageAccounts.usable.length > 0 && (
              <Button size="sm" disabled={!gen.imageModel || missingImages.length === 0 || !!batch} onClick={() => setDialog('batch-images')}>
                Make {missingImages.length || 'no'} missing {missingImages.length === 1 ? 'image' : 'images'}
              </Button>
            )}
          </Card>

          <Card label="Video generator">
            <h2 className="font-semibold flex items-center gap-2">
              <Video className="size-4" aria-hidden /> Scene videos
            </h2>
            <VideoModelPicker
              accounts={videoAccounts.usable}
              accountId={gen.videoAccountId}
              setAccountId={setVideoAccount}
              modelId={gen.videoModelId}
              setModelId={setVideoModelId}
              onModel={onVideoModel}
              stacked
            />
            {gen.videoModel && (
              <>
                {(gen.videoModel.options.resolutions?.length ?? 0) > 1 && (
                  <Field label="Resolution">
                    {(fid) => (
                      <Select id={fid} value={gen.resolution} onChange={(e) => setGen((g) => ({ ...g, resolution: e.target.value }))}>
                        {gen.videoModel!.options.resolutions!.map((r) => (
                          <option key={r.value} value={r.value}>
                            {r.label}
                          </option>
                        ))}
                      </Select>
                    )}
                  </Field>
                )}
                {gen.videoModel.options.audio && (
                  <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" className="accent-marigold-500" checked={gen.audio} onChange={(e) => setGen((g) => ({ ...g, audio: e.target.checked }))} />
                    Generate sound with the video
                  </label>
                )}
                <p className="text-xs text-faint">
                  Scenes with an image are animated from it{gen.videoModel.options.modes?.includes('IMAGE_TO_VIDEO') ? '' : ' when the model allows'}; others are made from the description. Lengths are rounded to what the model accepts.
                </p>
                <Button size="sm" disabled={missingVideos.length === 0 || !!batch} onClick={() => setDialog('batch-videos')}>
                  Start {missingVideos.length || 'no'} missing {missingVideos.length === 1 ? 'video' : 'videos'}
                </Button>
              </>
            )}
          </Card>
        </aside>
      </div>

      <Dialog
        open={dialog === 'batch-images' || dialog === 'batch-videos'}
        title={dialog === 'batch-images' ? `Make ${missingImages.length} images?` : `Start ${missingVideos.length} videos?`}
        onClose={() => setDialog(undefined)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDialog(undefined)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={() => (dialog === 'batch-images' ? runBatch(missingImages, 'image') : runBatch(missingVideos, 'video'))}
            >
              {dialog === 'batch-images' ? 'Make images' : 'Start videos'}
            </Button>
          </>
        }
      >
        <p className="text-muted">
          {dialog === 'batch-images'
            ? `One image per scene without one, with ${gen.imageModel}. Each is billed by the provider.`
            : `One video per scene without one, with ${gen.videoModel?.displayName ?? 'the chosen model'}. Video is billed per second by the provider; check Credits & usage for the estimate.`}
        </p>
      </Dialog>
      {dialog === 'settings' && <SettingsDialog board={board} onClose={() => setDialog(undefined)} />}
      {dialog === 'narrate' && <NarrateDialog board={board} onClose={() => setDialog(undefined)} />}
      {dialog === 'export' && <ExportDialog board={board} onClose={() => setDialog(undefined)} />}
    </>
  )
}

/** Changes only when a field the scene form edits changes, so polling for video progress doesn't reset a draft. */
function editableKey(s: Scene): string {
  const parts = [s.title, s.description, s.narration, dialogueText(s.dialogue), s.durationSeconds, s.cameraAngle, s.cameraMovement, s.lighting, s.composition, s.soundNotes, s.characterIds.join(','), s.imagePrompt, s.videoPrompt, s.references.map((r) => r.id).join(',')]
  let h = 0
  for (const ch of parts.join('\u0001')) h = (Math.imul(h, 31) + ch.charCodeAt(0)) | 0
  return String(h >>> 0)
}

/** Animate the still when there is one and the model can; otherwise from text, if the model can. */
function videoMode(s: Scene, m?: AiModelInfo): GenerationMode | undefined {
  const modes = m?.options.modes ?? []
  if (s.image && modes.includes('IMAGE_TO_VIDEO')) return 'IMAGE_TO_VIDEO'
  if (modes.includes('TEXT_TO_VIDEO')) return 'TEXT_TO_VIDEO'
  return undefined
}

function videoAction(s: Scene, gen: Generators) {
  const mode = videoMode(s, gen.videoModel)
  if (!mode || !gen.videoModel) return undefined
  return {
    kind: 'video' as const,
    sceneId: s.id,
    accountId: gen.videoAccountId,
    modelId: gen.videoModel.id,
    mode,
    durationSeconds: nearestDuration(gen.videoModel, s.durationSeconds),
    resolution: gen.resolution,
    audio: gen.videoModel.options.audio ? gen.audio : undefined,
  }
}

// ---- one scene --------------------------------------------------------------------------------------

function SceneCard({
  scene,
  board,
  index,
  count,
  characters,
  gen,
  busy,
}: {
  scene: Scene
  board: Storyboard
  index: number
  count: number
  characters: Character[]
  gen: Generators
  busy: boolean
}) {
  const [title, setTitle] = useState(scene.title)
  const [description, setDescription] = useState(scene.description)
  const [narration, setNarration] = useState(scene.narration)
  const [dialogue, setDialogue] = useState(dialogueText(scene.dialogue))
  const [duration, setDuration] = useState(String(scene.durationSeconds))
  const [angle, setAngle] = useState(scene.cameraAngle ?? '')
  const [move, setMove] = useState(scene.cameraMovement ?? '')
  const [lighting, setLighting] = useState(scene.lighting ?? '')
  const [composition, setComposition] = useState(scene.composition ?? '')
  const [sound, setSound] = useState(scene.soundNotes ?? '')
  const [cast, setCast] = useState(scene.characterIds)
  const [imagePrompt, setImagePrompt] = useState(scene.imagePrompt ?? '')
  const [videoPrompt, setVideoPrompt] = useState(scene.videoPrompt ?? '')
  const [refs, setRefs] = useState<MediaAsset[]>(scene.references)
  const [picker, setPicker] = useState<'image' | 'video' | 'reference'>()
  const action = useSceneAction()
  const pending = action.isPending ? action.variables?.kind : undefined
  const ta = board.language === 'TA' ? 'ta' : undefined
  const n = index + 1

  const seconds = Number(duration)
  const durationOk = Number.isFinite(seconds) && seconds >= 1 && seconds <= 120
  const draft: SceneInput = {
    title,
    description,
    narration,
    dialogue: parseDialogue(dialogue),
    durationSeconds: durationOk ? seconds : undefined,
    cameraAngle: angle,
    cameraMovement: move,
    lighting,
    composition,
    soundNotes: sound,
    characterIds: cast,
    imagePrompt,
    videoPrompt,
    referenceMediaIds: refs.map((r) => r.id),
  }
  const dirty =
    title !== scene.title ||
    description !== scene.description ||
    narration !== scene.narration ||
    dialogue.trim() !== dialogueText(scene.dialogue) ||
    seconds !== scene.durationSeconds ||
    angle !== (scene.cameraAngle ?? '') ||
    move !== (scene.cameraMovement ?? '') ||
    lighting !== (scene.lighting ?? '') ||
    composition !== (scene.composition ?? '') ||
    sound !== (scene.soundNotes ?? '') ||
    cast.join() !== scene.characterIds.join() ||
    imagePrompt !== (scene.imagePrompt ?? '') ||
    videoPrompt !== (scene.videoPrompt ?? '') ||
    refs.map((r) => r.id).join() !== scene.references.map((r) => r.id).join()

  const run = (a: Parameters<typeof action.mutate>[0], ok?: string) =>
    action.mutate(a, { onSuccess: () => ok && toast.success(ok), onError: (e) => toast.error(e.message) })

  function save() {
    if (!durationOk) return toast.error('Scene length must be 1 to 120 seconds')
    run({ kind: 'update', sceneId: scene.id, scene: draft }, `Scene ${n} saved`)
  }

  function guardDirty(): boolean {
    if (dirty) toast.info(`Save scene ${n} first, so the prompt uses your changes.`)
    return dirty
  }

  const vAction = videoAction(scene, gen)
  const job = scene.videoJob
  const running = isVideoRunning(job)
  const speakers = new Map(parseDialogue(dialogue).map((d) => [d.character.toLowerCase(), d.character]))
  const unvoiced = [...speakers].filter(([key]) => !characters.some((c) => c.name.toLowerCase() === key && c.voiceProfileId)).map(([, name]) => name)
  const portrait = board.aspectRatio === 'PORTRAIT'

  return (
    <article className="rounded-2xl border border-line bg-surface p-4 sm:p-5" aria-labelledby={`scene-${scene.id}`}>
      <div className={cn('grid gap-5', portrait ? 'md:grid-cols-[11rem_minmax(0,1fr)]' : 'md:grid-cols-[16rem_minmax(0,1fr)]')}>
        {/* visual */}
        <div className="flex flex-col gap-2">
          <div className={cn('relative rounded-xl overflow-hidden bg-surface-2 border border-line', FORMAT_CLASS[board.aspectRatio])}>
            {scene.video ? (
              <video src={scene.video.url} poster={scene.video.posterUrl ?? scene.image?.url} controls preload="none" className="absolute inset-0 size-full object-cover" aria-label={`Scene ${n} video`} />
            ) : scene.image ? (
              <img src={scene.image.url} alt={`Scene ${n}`} className="absolute inset-0 size-full object-cover" />
            ) : (
              <div className="absolute inset-0 grid place-items-center text-faint">
                <span className="font-display text-4xl tabular" aria-hidden>
                  {n}
                </span>
              </div>
            )}
            {running && (
              <div className="absolute inset-x-0 bottom-0 bg-[#1e2240]/80 text-white text-xs px-2 py-1.5 flex items-center gap-1.5" role="status">
                <Loader2 className="size-3.5 animate-spin" aria-hidden />
                Making video{job?.progress ? ` ${Math.round(job.progress * 100)}%` : ''}
              </div>
            )}
          </div>
          {job?.status === 'FAILED' && !scene.video && <p className="text-xs text-rose-signal">Video didn't work: {job.errorMessage ?? 'the provider gave no reason'}</p>}
          <div className="flex flex-wrap gap-1.5">
            <Button
              size="sm"
              icon={<Sparkles className="size-4" />}
              loading={pending === 'image'}
              disabled={!gen.imageModel || busy || action.isPending}
              title={!gen.imageModel ? 'Choose an image account on the right' : undefined}
              onClick={() => !guardDirty() && run({ kind: 'image', sceneId: scene.id, accountId: gen.imageAccountId, model: gen.imageModel }, `Scene ${n} image made`)}
            >
              {scene.image ? 'New image' : 'Make image'}
            </Button>
            <Button
              size="sm"
              icon={<Video className="size-4" />}
              loading={pending === 'video'}
              disabled={!vAction || running || busy || action.isPending}
              title={!gen.videoModel ? 'Choose a video account on the right' : undefined}
              onClick={() => !guardDirty() && vAction && run(vAction, `Scene ${n} video started`)}
            >
              {scene.video ? 'New video' : 'Make video'}
            </Button>
            <Button size="sm" variant="ghost" icon={<Library className="size-4" />} onClick={() => setPicker('image')} aria-label={`Choose scene ${n} image from library`}>
              Image
            </Button>
            <Button size="sm" variant="ghost" icon={<Library className="size-4" />} onClick={() => setPicker('video')} aria-label={`Choose scene ${n} video from library`}>
              Video
            </Button>
          </div>
          {vAction && (
            <p className="text-xs text-faint">
              {vAction.mode === 'IMAGE_TO_VIDEO' ? 'Animates the image' : 'From the description'}
              {vAction.durationSeconds ? `, ${vAction.durationSeconds} s` : ''}
            </p>
          )}
        </div>

        {/* details */}
        <div className="flex flex-col gap-3 min-w-0">
          <div className="flex flex-wrap items-end gap-3">
            <span className="text-sm font-semibold text-muted tabular pb-2.5" aria-hidden>
              {n}
            </span>
            <div className="flex-1 min-w-48">
              <label htmlFor={`scene-${scene.id}`} className="sr-only">
                Scene {n} title
              </label>
              <Input id={`scene-${scene.id}`} value={title} maxLength={160} onChange={(e) => setTitle(e.target.value)} placeholder={`Scene ${n}`} lang={ta} className="font-medium" />
            </div>
            <div className="w-24">
              <Field label="Seconds">{(fid) => <Input id={fid} type="number" min={1} max={120} step={0.5} value={duration} onChange={(e) => setDuration(e.target.value)} aria-invalid={!durationOk} />}</Field>
            </div>
            <div className="w-40">
              <Field label="Shot">
                {(fid) => (
                  <Select id={fid} value={angle} onChange={(e) => setAngle(e.target.value)}>
                    <option value="">Any</option>
                    {ANGLES.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            </div>
            <div className="w-36">
              <Field label="Camera">
                {(fid) => (
                  <Select id={fid} value={move} onChange={(e) => setMove(e.target.value)}>
                    <option value="">Any</option>
                    {MOVES.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            </div>
          </div>
          <Field label="What we see">
            {(fid) => <Textarea id={fid} rows={2} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} />}
          </Field>
          <div className="grid lg:grid-cols-2 gap-3">
            <Field label="Narration">
              {(fid) => <Textarea id={fid} rows={3} maxLength={6000} value={narration} onChange={(e) => setNarration(e.target.value)} lang={ta} />}
            </Field>
            <Field label="Dialogue" hint="One line each, as Name: what they say">
              {(fid, d) => <Textarea id={fid} aria-describedby={d} rows={3} value={dialogue} onChange={(e) => setDialogue(e.target.value)} lang={ta} />}
            </Field>
          </div>
          {(scene.dialogue.length > 0 || scene.dialogueAudio) && (
            <div className="flex flex-wrap items-center gap-3">
              {scene.dialogueAudio && <audio controls preload="none" src={scene.dialogueAudio.url} className="h-9 max-w-full" aria-label={`Scene ${n} dialogue`} />}
              {scene.dialogue.length > 0 && (
                <Button
                  size="sm"
                  icon={<Mic className="size-4" />}
                  loading={pending === 'dialogue'}
                  disabled={busy || action.isPending || unvoiced.length > 0}
                  onClick={() => !guardDirty() && run({ kind: 'dialogue', sceneId: scene.id }, `Scene ${n} dialogue voiced`)}
                >
                  {scene.dialogueAudio ? 'Re-voice lines' : 'Voice the lines'}
                </Button>
              )}
              {unvoiced.length > 0 && (
                <span className="text-xs text-muted">
                  Give {unvoiced.join(', ')} a voice in <Link to="/create/characters" className="underline">Character studio</Link> first.
                </span>
              )}
            </div>
          )}

          {characters.length > 0 && (
            <fieldset>
              <legend className="text-sm font-medium mb-1.5">In this scene</legend>
              <div className="flex flex-wrap gap-1.5">
                {characters.map((c) => {
                  const on = cast.includes(c.id)
                  return (
                    <button
                      key={c.id}
                      type="button"
                      aria-pressed={on}
                      disabled={!on && cast.length >= 8}
                      onClick={() => setCast((xs) => (on ? xs.filter((x) => x !== c.id) : [...xs, c.id]))}
                      className={cn(
                        'inline-flex items-center gap-1.5 rounded-full border pl-1 pr-3 py-0.5 text-sm',
                        on ? 'border-marigold-500 bg-marigold-500/10' : 'border-line text-muted hover:text-ink',
                      )}
                    >
                      {c.references[0] ? <MediaThumb asset={c.references[0]} className="size-6 rounded-full" /> : <span className="size-6 rounded-full bg-surface-2" aria-hidden />}
                      {c.name}
                    </button>
                  )
                })}
              </div>
            </fieldset>
          )}

          <details className="group rounded-lg border border-line px-3 py-2">
            <summary className="cursor-pointer text-sm font-medium">Light, framing, sound and references</summary>
            <div className="flex flex-col gap-3 pt-3">
              <div className="grid sm:grid-cols-2 gap-3">
                <Field label="Lighting">{(fid) => <Input id={fid} maxLength={300} value={lighting} onChange={(e) => setLighting(e.target.value)} placeholder="Neon signs, warm practical lights" />}</Field>
                <Field label="Framing">{(fid) => <Input id={fid} maxLength={300} value={composition} onChange={(e) => setComposition(e.target.value)} placeholder="Subject on the left third" />}</Field>
              </div>
              <Field label="Sound notes" hint="For you and the editor; not sent to image models.">
                {(fid, d) => <Input id={fid} aria-describedby={d} maxLength={2000} value={sound} onChange={(e) => setSound(e.target.value)} placeholder="Street noise, sizzling pan" />}
              </Field>
              <div>
                <p className="text-sm font-medium">Scene references</p>
                <p className="text-xs text-faint">Up to 4 images sent with the image prompt (location, props, a look), before the characters' own.</p>
                <ul className="flex flex-wrap gap-2 mt-2">
                  {refs.map((r) => (
                    <li key={r.id} className="relative">
                      <MediaThumb asset={r} className="size-16 rounded-lg" />
                      <button type="button" onClick={() => setRefs((rs) => rs.filter((x) => x.id !== r.id))} className="absolute -right-1.5 -top-1.5 rounded-full bg-surface border border-line p-0.5" aria-label={`Remove ${r.filename}`}>
                        <X className="size-3" />
                      </button>
                    </li>
                  ))}
                  {refs.length < 4 && (
                    <li>
                      <button type="button" onClick={() => setPicker('reference')} className="size-16 rounded-lg border border-dashed border-line grid place-items-center text-muted hover:text-ink" aria-label="Add a reference image">
                        <ImagePlus className="size-5" aria-hidden />
                      </button>
                    </li>
                  )}
                </ul>
              </div>
            </div>
          </details>

          <details className="rounded-lg border border-line px-3 py-2">
            <summary className="cursor-pointer text-sm font-medium">Prompts{scene.imagePrompt || scene.videoPrompt ? ' (your own)' : ''}</summary>
            <div className="flex flex-col gap-3 pt-3">
              <Field label="Image prompt" hint="Leave empty to use the one built from the scene, shown here.">
                {(fid, d) => <Textarea id={fid} aria-describedby={d} rows={4} maxLength={4000} value={imagePrompt} onChange={(e) => setImagePrompt(e.target.value)} placeholder={scene.suggestedImagePrompt} />}
              </Field>
              <Field label="Video prompt">
                {(fid) => <Textarea id={fid} rows={4} maxLength={4000} value={videoPrompt} onChange={(e) => setVideoPrompt(e.target.value)} placeholder={scene.suggestedVideoPrompt} />}
              </Field>
            </div>
          </details>

          <div className="flex flex-wrap items-center gap-2 pt-1">
            <Button size="sm" variant="primary" disabled={!dirty} loading={pending === 'update'} onClick={save}>
              Save scene
            </Button>
            {dirty && <span className="text-xs text-muted mr-auto">Unsaved</span>}
            <span className="flex flex-wrap gap-1 ml-auto">
              <IconButton label={`Move scene ${n} up`} disabled={index === 0 || action.isPending} onClick={() => run({ kind: 'reorder', boardId: board.id, sceneIds: swap(board.scenes, index, index - 1) })}>
                <ArrowUp className="size-4" />
              </IconButton>
              <IconButton label={`Move scene ${n} down`} disabled={index === count - 1 || action.isPending} onClick={() => run({ kind: 'reorder', boardId: board.id, sceneIds: swap(board.scenes, index, index + 1) })}>
                <ArrowDown className="size-4" />
              </IconButton>
              <IconButton label={`Add a scene after scene ${n}`} disabled={count >= 60 || action.isPending} onClick={() => run({ kind: 'add', boardId: board.id, at: index + 1 })}>
                <Plus className="size-4" />
              </IconButton>
              <IconButton label={`Duplicate scene ${n}`} disabled={count >= 60 || action.isPending} onClick={() => run({ kind: 'duplicate', sceneId: scene.id }, `Scene ${n} duplicated`)}>
                <Copy className="size-4" />
              </IconButton>
              <IconButton label={`Delete scene ${n}`} disabled={action.isPending} onClick={() => run({ kind: 'delete', sceneId: scene.id }, `Scene ${n} deleted`)}>
                <Trash2 className="size-4" />
              </IconButton>
            </span>
          </div>
        </div>
      </div>
      <MediaPicker
        open={!!picker}
        kind={picker === 'video' ? 'VIDEO' : 'IMAGE'}
        title={picker === 'video' ? `Video for scene ${n}` : picker === 'image' ? `Image for scene ${n}` : 'Reference image'}
        onClose={() => setPicker(undefined)}
        onPick={(m) => {
          if (picker === 'reference') setRefs((rs) => (rs.some((r) => r.id === m.id) ? rs : [...rs, m]))
          else run({ kind: 'update', sceneId: scene.id, scene: picker === 'video' ? { videoMediaId: m.id } : { imageMediaId: m.id } }, `Scene ${n} ${picker} set`)
          setPicker(undefined)
        }}
      />
    </article>
  )
}

function swap(scenes: Scene[], a: number, b: number): string[] {
  const ids = scenes.map((s) => s.id)
  ;[ids[a], ids[b]] = [ids[b], ids[a]]
  return ids
}

function IconButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} onClick={onClick} disabled={disabled} className="size-8 grid place-items-center rounded-lg text-muted hover:text-ink hover:bg-surface-2 disabled:opacity-40 disabled:pointer-events-none">
      {children}
    </button>
  )
}

// ---- dialogs ---------------------------------------------------------------------------------------------

function SettingsDialog({ board, onClose }: { board: Storyboard; onClose: () => void }) {
  const [title, setTitle] = useState(board.title)
  const [format, setFormat] = useState<VideoFormat>(board.aspectRatio)
  const [language, setLanguage] = useState<Language>(board.language)
  const [style, setStyle] = useState(board.style ?? '')
  const [projectId, setProjectId] = useState(board.projectId ?? '')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const update = useUpdateStoryboard()
  const remove = useDeleteStoryboard()
  const projects = useProjects({ page: 0 })
  const navigate = useNavigate()
  return (
    <Dialog
      open
      title="Storyboard settings"
      onClose={onClose}
      footer={
        confirmDelete ? (
          <>
            <span className="text-sm text-muted mr-auto self-center">Images and videos stay in the library.</span>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() => remove.mutate(board.id, { onSuccess: () => { toast.success('Storyboard deleted'); navigate('/create/storyboard') }, onError: (e) => toast.error(e.message) })}
            >
              Delete storyboard
            </Button>
          </>
        ) : (
          <>
            <Button variant="ghost" className="mr-auto" icon={<Trash2 className="size-4" />} onClick={() => setConfirmDelete(true)}>
              Delete
            </Button>
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={update.isPending}
              disabled={!title.trim()}
              onClick={() =>
                update.mutate(
                  { id: board.id, title: title.trim(), aspectRatio: format, language, style: style.trim() || undefined, projectId: projectId || undefined },
                  { onSuccess: () => { toast.success('Saved'); onClose() }, onError: (e) => toast.error(e.message) },
                )
              }
            >
              Save
            </Button>
          </>
        )
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Title">{(id) => <Input id={id} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="Format" hint={format !== board.aspectRatio ? 'Images already made keep their shape.' : undefined}>
            {(id, d) => (
              <Select id={id} aria-describedby={d} value={format} onChange={(e) => setFormat(e.target.value as VideoFormat)}>
                {FORMATS.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Language">
            {(id) => (
              <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
                <option value="TA">Tamil</option>
                <option value="EN">English</option>
              </Select>
            )}
          </Field>
        </div>
        <Field label="Look">{(id) => <Input id={id} maxLength={1000} value={style} onChange={(e) => setStyle(e.target.value)} />}</Field>
        <Field label="Project">
          {(id) => (
            <Select id={id} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">No project</option>
              {(projects.data?.items ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
    </Dialog>
  )
}

function NarrateDialog({ board, onClose }: { board: Storyboard; onClose: () => void }) {
  const profiles = useVoiceProfiles()
  const [profileId, setProfileId] = useState('')
  const action = useSceneAction()
  const chosen = profileId || profiles.data?.[0]?.id || ''
  const spoken = board.scenes.filter((s) => s.narration.trim()).length
  return (
    <Dialog
      open
      title="Narrate the storyboard"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={action.isPending}
            disabled={!chosen}
            onClick={() =>
              action.mutate(
                { kind: 'narrate', boardId: board.id, voice: { profileId: chosen } },
                { onSuccess: () => { toast.success('Narration recorded'); onClose() }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Record narration
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-muted text-sm">
          Reads the narration of {spoken} {spoken === 1 ? 'scene' : 'scenes'} in one voice-over, in {board.language === 'TA' ? 'Tamil' : 'English'}. Each scene keeps its own part, and scenes stretch to fit what is said. Save scenes you've changed first.
        </p>
        <Field label="Voice">
          {(id) =>
            profiles.isPending ? (
              <Spinner label="Loading voices" />
            ) : (profiles.data ?? []).length === 0 ? (
              <p id={id} className="text-sm text-muted">
                No saved voices. <Link to="/create/voice" className="underline">Add one in Voice & audio</Link>.
              </p>
            ) : (
              <Select id={id} value={chosen} onChange={(e) => setProfileId(e.target.value)}>
                {(profiles.data ?? []).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                    {p.kind === 'CLONED' ? ' (cloned)' : ''}
                  </option>
                ))}
              </Select>
            )
          }
        </Field>
      </div>
    </Dialog>
  )
}

function ExportDialog({ board, onClose }: { board: Storyboard; onClose: () => void }) {
  const [name, setName] = useState(board.title)
  const [captions, setCaptions] = useState(!!board.narration)
  const [music, setMusic] = useState<MediaAsset>()
  const [volume, setVolume] = useState(0.15)
  const exp = useExportStoryboard()
  const result = exp.data
  const p = boardProgress(board)
  return (
    <Dialog
      open
      title="Send to the video editor"
      onClose={onClose}
      footer={
        result ? (
          <>
            <Button variant="ghost" onClick={onClose}>
              Close
            </Button>
            <Link to={`/edit/timeline/${result.timelineId}`} className="h-10 inline-flex items-center px-4 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] font-semibold">
              Open in the editor
            </Link>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={exp.isPending}
              disabled={!name.trim()}
              onClick={() =>
                exp.mutate(
                  { id: board.id, name: name.trim(), captions, musicMediaId: music?.id, musicVolume: music ? volume : undefined },
                  { onError: (e) => toast.error(e.message) },
                )
              }
            >
              Make timeline
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="flex flex-col gap-3" aria-live="polite">
          <p>
            A {formatSeconds(result.totalSeconds)} timeline is ready. Trim, reorder, add transitions, then export it from the editor.
          </p>
          {result.notes.length > 0 && (
            <ul className="text-sm text-muted list-disc pl-5">
              {result.notes.map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-muted">
            {p.videos} of {p.scenes} scenes have video and {p.images} have an image; the rest get a title card. Each send makes a new timeline, so earlier edits are kept.
          </p>
          <Field label="Timeline name">{(id) => <Input id={id} maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" className="accent-marigold-500 mt-1" checked={captions} onChange={(e) => setCaptions(e.target.checked)} disabled={!board.narration} />
            <span>
              Captions from the narration
              {!board.narration && <span className="block text-xs text-faint">Record the narration first.</span>}
            </span>
          </label>
          <MediaField label="Music (optional)" kind="AUDIO" value={music} onChange={setMusic} pickerTitle="Choose music" />
          {music && <Slider label="Music volume" min={0.05} max={0.6} step={0.05} value={volume} onChange={setVolume} format={(v) => `${Math.round(v * 100)}%`} />}
        </div>
      )}
    </Dialog>
  )
}
