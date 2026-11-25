import { ImagePlus, Sparkles, Wand2, X } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { MediaThumb } from '../components/media'
import { MediaPicker } from '../components/MediaPicker'
import { CompareDialog, TakeGroup } from '../components/Takes'
import { durationChoices, groupJobs, isActive, ratiosFor } from '../lib/generation'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useAccounts, useEnhancePrompt, useEstimate, useGenerateVideo, useJobs, useModels, useTextModels } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatAmount } from '../lib/format'
import { useDebounced } from '../lib/hooks'
import { useProjects } from '../lib/queries'
import type { AiAccount, AiModelInfo, Estimate, GenerationJob, GenerationMode, MediaAsset, VideoGenerationInput } from '../lib/types'
import { useGenerationEvents } from '../lib/useGenerationEvents'
import { toast } from '../stores/ui'

// ---- small persisted preferences (display choices only, nothing sensitive) ----

function usePref<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(`leenax-pref:${key}`)
      return raw ? (JSON.parse(raw) as T) : initial
    } catch {
      return initial
    }
  })
  const set = (v: T) => {
    setValue(v)
    try {
      localStorage.setItem(`leenax-pref:${key}`, JSON.stringify(v))
    } catch {
      /* ignore */
    }
  }
  return [value, set] as const
}

interface FormState {
  mode: GenerationMode
  accountId: string
  modelId: string
  prompt: string
  negativePrompt: string
  durationSeconds?: number
  aspectRatio?: string
  resolution?: string
  audio: boolean
  image?: MediaAsset
  projectId: string
  variations: number
}

const EMPTY: FormState = { mode: 'TEXT_TO_VIDEO', accountId: '', modelId: '', prompt: '', negativePrompt: '', audio: false, projectId: '', variations: 1 }

export function VideoGeneratorPage() {
  const accounts = useAccounts()
  const models = useModels()
  const projects = useProjects({ page: 0 })
  const live = useGenerationEvents()
  const [page, setPage] = useState(0)
  const jobs = useJobs(page)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [picking, setPicking] = useState(false)
  const [showMore, setShowMore] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [confirmPref, setConfirmPref] = usePref('confirm-generation', true)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [comparing, setComparing] = useState(false)
  const generate = useGenerateVideo()

  const videoAccounts = useMemo(
    () => (accounts.data ?? []).filter((a) => a.capabilities.includes('VIDEO') && a.status !== 'DISABLED' && a.status !== 'INVALID'),
    [accounts.data],
  )
  const account = videoAccounts.find((a) => a.id === form.accountId)
  const modelsForAccount = useMemo(
    () => (models.data ?? []).filter((m) => m.capability === 'VIDEO' && m.enabled && m.provider === account?.provider),
    [models.data, account?.provider],
  )
  const modelsForMode = modelsForAccount.filter((m) => m.options.modes?.includes(form.mode))
  const model = modelsForMode.find((m) => m.id === form.modelId)

  // Pick sensible defaults once data arrives (default account, first model, model defaults).
  useEffect(() => {
    if (form.accountId || videoAccounts.length === 0) return
    const preferred = videoAccounts.find((a) => a.isDefault) ?? videoAccounts[0]
    setForm((f) => ({ ...f, accountId: preferred.id }))
  }, [videoAccounts, form.accountId])

  useEffect(() => {
    if (!account) return
    if (model) return
    const next = modelsForMode[0]
    setForm((f) => applyModelDefaults({ ...f, modelId: next?.id ?? '' }, next))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account?.id, form.mode, modelsForMode.length])

  // Fall back to polling when the live stream isn't connected.
  const anyActive = jobs.data?.items.some(isActive) ?? false
  useEffect(() => {
    if (live || !anyActive) return
    const t = setInterval(() => jobs.refetch(), 5000)
    return () => clearInterval(t)
  }, [live, anyActive, jobs])

  const input: VideoGenerationInput | undefined =
    account && model && form.prompt.trim() && (form.mode === 'TEXT_TO_VIDEO' || form.image)
      ? {
          accountId: account.id,
          modelId: model.id,
          mode: form.mode,
          prompt: form.prompt.trim(),
          negativePrompt: form.negativePrompt.trim() || undefined,
          durationSeconds: form.durationSeconds,
          aspectRatio: form.aspectRatio,
          resolution: form.resolution,
          audio: model.options.audio ? form.audio : undefined,
          imageMediaId: form.mode === 'IMAGE_TO_VIDEO' ? form.image?.id : undefined,
          projectId: form.projectId || undefined,
          variations: form.variations,
        }
      : undefined
  const debouncedInput = useDebounced(input, 500)
  const estimate = useEstimate(debouncedInput)

  function submit(e?: FormEvent) {
    e?.preventDefault()
    if (!input) return
    if (confirmPref && !confirming) {
      setConfirming(true)
      return
    }
    setConfirming(false)
    generate.mutate(
      { input, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (res) => {
          toast.success(res.jobs.length > 1 ? `${res.jobs.length} takes queued` : 'Generation queued')
          setPage(0)
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  function reuse(j: GenerationJob) {
    const m = models.data?.find((x) => x.id === j.modelId)
    setForm((f) => ({
      ...f,
      mode: j.mode,
      accountId: j.accountId && videoAccounts.some((a) => a.id === j.accountId) ? j.accountId : f.accountId,
      modelId: m?.id ?? f.modelId,
      prompt: j.prompt,
      negativePrompt: j.negativePrompt ?? '',
      durationSeconds: j.durationSeconds,
      aspectRatio: j.aspectRatio,
      resolution: j.resolution,
      audio: !!j.audio,
      projectId: j.projectId ?? '',
      image: j.mode === 'IMAGE_TO_VIDEO' ? f.image : undefined,
    }))
    window.scrollTo({ top: 0, behavior: 'smooth' })
    toast.info(j.mode === 'IMAGE_TO_VIDEO' ? 'Settings loaded. Pick the start image again.' : 'Settings loaded')
  }

  const loading = accounts.isPending || models.isPending

  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">AI video generator</h1>
        <p className="text-muted mt-1 max-w-2xl">Describe a shot or animate an image. Finished videos go straight into your media library.</p>
      </header>

      {loading && <Spinner label="Loading your accounts" />}
      {(accounts.error || models.error) && <ErrorNotice error={accounts.error ?? models.error} onRetry={() => { accounts.refetch(); models.refetch() }} />}

      {!loading && videoAccounts.length === 0 && (
        <EmptyState
          title="Connect a video account first"
          body="Add your Runway, Kling, Luma or Google Gemini API key. Generations are billed by that provider to your own account."
          action={
            <Link to="/manage/ai-accounts" className="h-10 inline-flex items-center px-4 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] font-semibold">
              Add an account
            </Link>
          }
        />
      )}

      {!loading && videoAccounts.length > 0 && (
        <div className="grid xl:grid-cols-[minmax(0,26rem)_minmax(0,1fr)] gap-8 items-start">
          {/* ---- Setup panel ---- */}
          <form onSubmit={submit} className="rounded-2xl border border-line bg-surface p-5 flex flex-col gap-5 xl:sticky xl:top-6" aria-label="Video settings">
            <div role="radiogroup" aria-label="Start from" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1">
              {(['TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO'] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  role="radio"
                  aria-checked={form.mode === m}
                  onClick={() => setForm((f) => ({ ...f, mode: m }))}
                  className={cn('h-9 rounded-lg text-sm', form.mode === m ? 'bg-surface text-ink font-medium shadow-sm' : 'text-muted hover:text-ink')}
                >
                  {m === 'TEXT_TO_VIDEO' ? 'From text' : 'From an image'}
                </button>
              ))}
            </div>

            <div className="grid sm:grid-cols-2 xl:grid-cols-1 gap-4">
              <Field label="Account">
                {(id) => (
                  <Select id={id} value={form.accountId} onChange={(e) => setForm((f) => ({ ...f, accountId: e.target.value, modelId: '' }))}>
                    {videoAccounts.map((a) => (
                      <option key={a.id} value={a.id}>
                        {accountLabel(a)}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label="Model" hint={model?.notes ?? undefined}>
                {(id, d) => (
                  <Select
                    id={id}
                    aria-describedby={d}
                    value={form.modelId}
                    disabled={modelsForMode.length === 0}
                    onChange={(e) => {
                      const next = modelsForMode.find((m) => m.id === e.target.value)
                      setForm((f) => applyModelDefaults({ ...f, modelId: e.target.value }, next))
                    }}
                  >
                    {modelsForMode.length === 0 && <option value="">No {form.mode === 'TEXT_TO_VIDEO' ? 'text' : 'image'}-to-video models for this account</option>}
                    {modelsForMode.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.displayName}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            </div>

            {form.mode === 'IMAGE_TO_VIDEO' && (
              <div>
                <p className="text-sm font-medium mb-1.5">Start frame</p>
                {form.image ? (
                  <div className="flex items-center gap-3">
                    <MediaThumb asset={form.image} className="w-28 aspect-video rounded-lg" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm truncate">{form.image.filename}</p>
                      <button type="button" onClick={() => setPicking(true)} className="text-sm text-muted underline underline-offset-4 hover:text-ink">
                        Change
                      </button>
                    </div>
                    <button type="button" onClick={() => setForm((f) => ({ ...f, image: undefined }))} className="p-1.5 text-faint hover:text-ink" aria-label="Remove image">
                      <X className="size-4" />
                    </button>
                  </div>
                ) : (
                  <Button type="button" icon={<ImagePlus className="size-4" />} onClick={() => setPicking(true)}>
                    Choose an image
                  </Button>
                )}
              </div>
            )}

            <PromptField
              value={form.prompt}
              onChange={(prompt) => setForm((f) => ({ ...f, prompt }))}
              targetModel={model?.displayName}
              language={projects.data?.items.find((p) => p.id === form.projectId)?.language}
            />

            {model && <ModelSettings model={model} form={form} setForm={setForm} />}

            <div>
              <p className="text-sm font-medium mb-1.5">Takes</p>
              <div role="radiogroup" aria-label="Number of takes" className="flex gap-1">
                {[1, 2, 3, 4].map((n) => (
                  <button
                    key={n}
                    type="button"
                    role="radio"
                    aria-checked={form.variations === n}
                    onClick={() => setForm((f) => ({ ...f, variations: n }))}
                    className={cn('h-9 w-11 rounded-lg border text-sm tabular', form.variations === n ? 'border-marigold-500 bg-marigold-500/10 font-medium' : 'border-line text-muted hover:text-ink')}
                  >
                    {n}
                  </button>
                ))}
              </div>
            </div>

            <button type="button" onClick={() => setShowMore((s) => !s)} aria-expanded={showMore} className="text-sm text-muted hover:text-ink self-start underline underline-offset-4">
              {showMore ? 'Fewer options' : 'More options'}
            </button>
            {showMore && (
              <div className="flex flex-col gap-4">
                <Field label="Avoid" hint="Things you don't want in the shot, e.g. “text, logos, blurry faces”.">
                  {(id, d) => <Input id={id} aria-describedby={d} value={form.negativePrompt} maxLength={1000} onChange={(e) => setForm((f) => ({ ...f, negativePrompt: e.target.value }))} />}
                </Field>
                <Field label="Save to project" hint="Optional. Videos always go to the media library.">
                  {(id, d) => (
                    <Select id={id} aria-describedby={d} value={form.projectId} onChange={(e) => setForm((f) => ({ ...f, projectId: e.target.value }))}>
                      <option value="">No project</option>
                      {projects.data?.items.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                    </Select>
                  )}
                </Field>
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={confirmPref} onChange={(e) => setConfirmPref(e.target.checked)} className="size-4 accent-[var(--color-marigold-500)]" />
                  Ask me to confirm the cost before generating
                </label>
              </div>
            )}

            <CostPanel estimate={estimate.data} loading={estimate.isFetching} error={estimate.error} ready={!!input} />

            <Button type="submit" variant="primary" className="h-12 text-base" loading={generate.isPending} disabled={!input} icon={<Sparkles className="size-5" />}>
              {form.variations > 1 ? `Generate ${form.variations} takes` : 'Generate video'}
            </Button>
          </form>

          {/* ---- Takes ---- */}
          <section aria-labelledby="takes" className="min-w-0">
            <div className="flex items-center justify-between gap-3 mb-4">
              <h2 id="takes" className="text-xl font-semibold">
                Your takes
              </h2>
              <div className="flex items-center gap-3">
                <span className="text-xs text-faint inline-flex items-center gap-1.5">
                  <span className={cn('size-2 rounded-full', live ? 'bg-peacock-500' : 'bg-[var(--text-faint)]')} aria-hidden />
                  {live ? 'Live' : 'Refreshing every few seconds'}
                </span>
                {selected.size > 0 && (
                  <>
                    <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
                      Clear
                    </Button>
                    <Button size="sm" variant="primary" disabled={selected.size < 2} onClick={() => setComparing(true)}>
                      Compare {selected.size}
                    </Button>
                  </>
                )}
              </div>
            </div>

            {jobs.isPending && <Spinner label="Loading takes" />}
            {jobs.error && <ErrorNotice error={jobs.error} onRetry={() => jobs.refetch()} />}
            {jobs.data &&
              (jobs.data.items.length === 0 ? (
                <EmptyState title="No takes yet" body="Your generations appear here with live progress. Pick 2 to 4 finished takes to compare them side by side." />
              ) : (
                <div className="flex flex-col gap-4">
                  {groupJobs(jobs.data.items).map((group) => (
                    <TakeGroup
                      key={group[0].groupId}
                      jobs={group}
                      selected={selected}
                      onReuse={reuse}
                      onToggleSelect={(id) =>
                        setSelected((s) => {
                          const next = new Set(s)
                          if (next.has(id)) next.delete(id)
                          else if (next.size < 4) next.add(id)
                          else toast.info('Compare up to 4 takes at a time')
                          return next
                        })
                      }
                    />
                  ))}
                  {jobs.data.totalPages > 1 && (
                    <div className="flex items-center justify-center gap-3 mt-2">
                      <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                        Newer
                      </Button>
                      <span className="text-sm text-muted tabular">
                        Page {page + 1} of {jobs.data.totalPages}
                      </span>
                      <Button size="sm" disabled={page + 1 >= jobs.data.totalPages} onClick={() => setPage((p) => p + 1)}>
                        Older
                      </Button>
                    </div>
                  )}
                </div>
              ))}
          </section>
        </div>
      )}

      <MediaPicker
        open={picking}
        kind="IMAGE"
        title="Choose a start frame"
        onClose={() => setPicking(false)}
        onPick={(m) => {
          setForm((f) => ({ ...f, image: m }))
          setPicking(false)
        }}
      />

      {comparing && (
        <CompareDialog jobs={(jobs.data?.items ?? []).filter((j) => selected.has(j.id))} onClose={() => setComparing(false)} />
      )}

      <Dialog
        open={confirming}
        title="Start this generation?"
        onClose={() => setConfirming(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>
              Not now
            </Button>
            <Button variant="primary" loading={generate.isPending} onClick={() => submit()}>
              {form.variations > 1 ? `Generate ${form.variations} takes` : 'Generate video'}
            </Button>
          </>
        }
      >
        <p className="text-muted">
          {model?.displayName} on {account?.name}.{' '}
          {estimate.data?.total != null
            ? `Estimated cost: ${formatAmount(estimate.data.total, estimate.data.unit)}, billed by ${account?.providerName} to your account.`
            : `${account?.providerName} bills your account directly; no price is set for this model, so there's no estimate.`}
        </p>
        {estimate.data?.warnings.map((w) => (
          <p key={w} className="text-sm text-rose-signal mt-2">
            {w}
          </p>
        ))}
      </Dialog>
    </>
  )
}

function accountLabel(a: AiAccount) {
  const t = a.totals[0]
  return `${a.providerName}: ${a.name}${t ? ` (${formatAmount(t.amount, t.unit)})` : ''}`
}

function applyModelDefaults(f: FormState, m?: AiModelInfo): FormState {
  if (!m) return { ...f, durationSeconds: undefined, aspectRatio: undefined, resolution: undefined }
  const durations = durationChoices(m)
  const ratios = ratiosFor(m, f.mode)
  const resolutions = m.options.resolutions ?? []
  return {
    ...f,
    durationSeconds: f.durationSeconds && durations.includes(f.durationSeconds) ? f.durationSeconds : m.options.defaultDuration ?? durations[0],
    aspectRatio: f.aspectRatio && ratios.some((r) => r.value === f.aspectRatio) ? f.aspectRatio : ratios[0]?.value,
    resolution: f.resolution && resolutions.some((r) => r.value === f.resolution) ? f.resolution : resolutions[0]?.value,
    audio: m.options.audio ? f.audio : false,
  }
}

function ModelSettings({ model, form, setForm }: { model: AiModelInfo; form: FormState; setForm: React.Dispatch<React.SetStateAction<FormState>> }) {
  const durations = durationChoices(model)
  const ratios = ratiosFor(model, form.mode)
  const resolutions = model.options.resolutions ?? []

  return (
    <div className="flex flex-col gap-4">
      {durations.length > 0 && (
        <div>
          <div className="flex items-baseline justify-between mb-1.5">
            <label htmlFor="duration" className="text-sm font-medium">
              Length
            </label>
            <span className="text-sm tabular text-muted">{form.durationSeconds} s</span>
          </div>
          {durations.length <= 6 ? (
            <div role="radiogroup" aria-label="Length" className="flex gap-1 flex-wrap">
              {durations.map((s) => (
                <button
                  key={s}
                  type="button"
                  role="radio"
                  aria-checked={form.durationSeconds === s}
                  onClick={() => setForm((f) => ({ ...f, durationSeconds: s }))}
                  className={cn('h-9 px-3 rounded-lg border text-sm tabular', form.durationSeconds === s ? 'border-marigold-500 bg-marigold-500/10 font-medium' : 'border-line text-muted hover:text-ink')}
                >
                  {s} s
                </button>
              ))}
            </div>
          ) : (
            <input
              id="duration"
              type="range"
              min={durations[0]}
              max={durations[durations.length - 1]}
              step={1}
              value={form.durationSeconds ?? durations[0]}
              onChange={(e) => setForm((f) => ({ ...f, durationSeconds: Number(e.target.value) }))}
              className="w-full accent-[var(--color-marigold-500)]"
            />
          )}
          {model.options.durationInPrompt && <p className="text-xs text-faint mt-1">This model takes length as guidance in the prompt, so the result may differ a little.</p>}
        </div>
      )}

      {ratios.length > 0 && (
        <div>
          <p className="text-sm font-medium mb-1.5">Format</p>
          <div role="radiogroup" aria-label="Format" className="flex gap-1.5 flex-wrap">
            {ratios.map((r) => (
              <button
                key={r.value}
                type="button"
                role="radio"
                aria-checked={form.aspectRatio === r.value}
                onClick={() => setForm((f) => ({ ...f, aspectRatio: r.value }))}
                className={cn('h-9 px-3 rounded-lg border text-sm', form.aspectRatio === r.value ? 'border-marigold-500 bg-marigold-500/10 font-medium' : 'border-line text-muted hover:text-ink')}
              >
                {r.label}
              </button>
            ))}
          </div>
        </div>
      )}
      {ratios.length === 0 && form.mode === 'IMAGE_TO_VIDEO' && <p className="text-xs text-faint">The video keeps your image's shape.</p>}

      <div className="flex flex-wrap gap-4 items-end">
        {resolutions.length > 0 && (
          <Field label="Resolution">
            {(id) => (
              <Select id={id} className="min-w-40" value={form.resolution} onChange={(e) => setForm((f) => ({ ...f, resolution: e.target.value }))}>
                {resolutions.map((r) => (
                  <option key={r.value} value={r.value}>
                    {r.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        {model.options.audio && (
          <label className="flex items-center gap-2 text-sm h-10">
            <input type="checkbox" checked={form.audio} onChange={(e) => setForm((f) => ({ ...f, audio: e.target.checked }))} className="size-4 accent-[var(--color-marigold-500)]" />
            Generate sound
          </label>
        )}
      </div>
    </div>
  )
}

function CostPanel({ estimate, loading, error, ready }: { estimate?: Estimate; loading: boolean; error: Error | null; ready: boolean }) {
  if (!ready) return <p className="text-sm text-faint">Write a prompt to see the cost.</p>
  if (error) return <p className="text-sm text-rose-signal">{error.message}</p>
  if (!estimate) return <p className="text-sm text-faint">{loading ? 'Working out the cost…' : ''}</p>
  return (
    <div className="rounded-xl bg-surface-2 px-4 py-3 text-sm" aria-live="polite">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-muted">Estimated cost</span>
        <span className={cn('font-display text-lg font-semibold tabular', estimate.insufficient && 'text-rose-signal')}>
          {estimate.total != null ? formatAmount(estimate.total, estimate.unit) : 'Unknown'}
        </span>
      </div>
      {estimate.variations > 1 && estimate.perVideo != null && (
        <p className="text-xs text-faint text-right">
          {estimate.variations} × {formatAmount(estimate.perVideo, estimate.unit)}
        </p>
      )}
      {estimate.balance != null && (
        <p className="text-xs text-faint mt-1">
          Balance {formatAmount(estimate.balance, estimate.balanceUnit)}
          {estimate.balanceAfter != null && `, about ${formatAmount(estimate.balanceAfter, estimate.balanceUnit)} after`}
        </p>
      )}
      {estimate.warnings.map((w) => (
        <p key={w} className={cn('text-xs mt-1.5', estimate.insufficient || estimate.lowCredit ? 'text-rose-signal' : 'text-muted')}>
          {w}
        </p>
      ))}
    </div>
  )
}

// ---- prompt with "improve" helper ---------------------------------------------

function PromptField({ value, onChange, targetModel, language }: { value: string; onChange: (v: string) => void; targetModel?: string; language?: string }) {
  const accounts = useAccounts()
  const textAccounts = (accounts.data ?? []).filter((a) => a.capabilities.includes('TEXT') && a.status !== 'DISABLED' && a.status !== 'INVALID')
  const [open, setOpen] = useState(false)
  const [accountId, setAccountId] = usePref<string>('enhance-account', '')
  const [model, setModel] = usePref<string>('enhance-model', '')
  const chosenAccount = textAccounts.find((a) => a.id === accountId) ?? textAccounts[0]
  const textModels = useTextModels(open ? chosenAccount?.id : undefined)
  const enhance = useEnhancePrompt()
  const [suggestion, setSuggestion] = useState<string>()

  function improve() {
    if (!chosenAccount || !model) return
    enhance.mutate(
      { accountId: chosenAccount.id, model, prompt: value, language, targetModel },
      { onSuccess: (r) => setSuggestion(r.prompt), onError: (e) => toast.error(e.message) },
    )
  }

  return (
    <div>
      <div className="flex items-baseline justify-between mb-1.5">
        <label htmlFor="prompt" className="text-sm font-medium">
          Describe the shot
        </label>
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          disabled={!value.trim()}
          className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink disabled:opacity-40"
        >
          <Wand2 className="size-4" aria-hidden />
          Improve prompt
        </button>
      </div>
      <Textarea
        id="prompt"
        value={value}
        maxLength={4000}
        rows={5}
        placeholder="A slow dolly shot through a Madurai flower market at dawn, jasmine garlands swaying, warm golden light, shallow depth of field"
        onChange={(e) => onChange(e.target.value)}
      />
      <p className="text-xs text-faint mt-1 text-right tabular">{value.length} / 4000</p>

      {open && (
        <div className="mt-2 rounded-xl border border-line p-4 flex flex-col gap-3">
          {textAccounts.length === 0 ? (
            <p className="text-sm text-muted">
              Add an OpenAI, Anthropic or Google Gemini account under{' '}
              <Link to="/manage/ai-accounts" className="underline underline-offset-4 text-ink">
                AI accounts & keys
              </Link>{' '}
              to get help writing prompts.
            </p>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-2">
                <Select aria-label="Writing account" value={chosenAccount?.id} onChange={(e) => { setAccountId(e.target.value); setModel('') }}>
                  {textAccounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.providerName}: {a.name}
                    </option>
                  ))}
                </Select>
                <Select aria-label="Writing model" value={model} onChange={(e) => setModel(e.target.value)} disabled={textModels.isPending}>
                  <option value="">{textModels.isPending ? 'Loading models…' : 'Choose a model'}</option>
                  {textModels.data?.map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </Select>
              </div>
              {textModels.error && <p className="text-sm text-rose-signal">{textModels.error.message}</p>}
              {suggestion ? (
                <>
                  <p className="text-sm bg-surface-2 rounded-lg p-3 whitespace-pre-line">{suggestion}</p>
                  <div className="flex gap-2 justify-end">
                    <Button type="button" size="sm" variant="ghost" onClick={() => setSuggestion(undefined)}>
                      Keep mine
                    </Button>
                    <Button type="button" size="sm" onClick={improve} loading={enhance.isPending}>
                      Try again
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="primary"
                      onClick={() => {
                        onChange(suggestion)
                        setSuggestion(undefined)
                        setOpen(false)
                      }}
                    >
                      Use this
                    </Button>
                  </div>
                </>
              ) : (
                <Button type="button" size="sm" className="self-end" onClick={improve} loading={enhance.isPending} disabled={!model}>
                  Write a better prompt
                </Button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
