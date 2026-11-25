import { ExternalLink, Library } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useAccounts, useModels, useTextModels } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatDuration } from '../lib/format'
import type { AiAccount, AiModelInfo, Capability, MediaAsset, MediaKind } from '../lib/types'
import { MediaPicker } from './MediaPicker'
import { MediaThumb } from './media'
import { Button, EmptyState, Field, Select, Spinner } from './ui'

/** Accounts that can do something, ready to use (not switched off or rejected). */
export function useUsableAccounts(capability: Capability) {
  const accounts = useAccounts()
  const usable = useMemo(
    () => (accounts.data ?? []).filter((a) => a.capabilities.includes(capability) && a.status !== 'DISABLED' && a.status !== 'INVALID'),
    [accounts.data, capability],
  )
  return { ...accounts, usable }
}

/** Keeps a selected account id valid: picks the default (or first) one when none is chosen. */
export function useDefaultAccount(usable: AiAccount[], value: string, set: (id: string) => void) {
  useEffect(() => {
    if (usable.length === 0) return
    if (value && usable.some((a) => a.id === value)) return
    set((usable.find((a) => a.isDefault) ?? usable[0]).id)
  }, [usable, value, set])
}

export function AccountSelect({
  id,
  accounts,
  value,
  onChange,
}: {
  id?: string
  accounts: AiAccount[]
  value: string
  onChange: (id: string) => void
}) {
  return (
    <Select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      {accounts.map((a) => (
        <option key={a.id} value={a.id}>
          {a.name} · {a.providerName}
        </option>
      ))}
    </Select>
  )
}

export function ConnectAccount({ title, body }: { title: string; body: string }) {
  return (
    <EmptyState
      title={title}
      body={body}
      action={
        <Link to="/manage/ai-accounts" className="h-10 inline-flex items-center px-4 rounded-lg bg-marigold-500 hover:bg-marigold-400 text-[#1e2240] font-semibold">
          Add an account
        </Link>
      }
    />
  )
}

/** Chooses a file from the media library (or uploads one) and shows what was picked. */
export function MediaField({
  label,
  kind,
  value,
  onChange,
  pickerTitle,
}: {
  label: string
  kind: MediaKind | MediaKind[]
  value?: MediaAsset
  onChange: (m?: MediaAsset) => void
  pickerTitle?: string
}) {
  const [open, setOpen] = useState(false)
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-sm font-medium text-ink">{label}</span>
      {value ? (
        <div className="flex items-center gap-3 rounded-lg border border-line bg-surface-2/50 p-2">
          <MediaThumb asset={value} className="w-20 aspect-video rounded-md shrink-0" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm">{value.filename}</p>
            {value.durationMs != null && <p className="text-xs text-faint tabular">{formatDuration(value.durationMs)}</p>}
          </div>
          <Button type="button" size="sm" variant="ghost" onClick={() => setOpen(true)}>
            Change
          </Button>
        </div>
      ) : (
        <Button type="button" onClick={() => setOpen(true)} icon={<Library className="size-4" />}>
          Choose from library
        </Button>
      )}
      <MediaPicker
        open={open}
        kind={kind}
        title={pickerTitle ?? label}
        onClose={() => setOpen(false)}
        onPick={(m) => {
          onChange(m)
          setOpen(false)
        }}
      />
    </div>
  )
}

/** Plays a finished file and links to it in the library. */
export function ResultCard({ media, note, actions }: { media: MediaAsset; note?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="rounded-xl border border-peacock-500/40 bg-peacock-500/6 p-4 flex flex-col gap-3" aria-live="polite">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium truncate">{media.filename}</p>
          <p className="text-sm text-muted">
            Saved to your media library{media.durationMs != null ? ` · ${formatDuration(media.durationMs)}` : ''}
            {note ? <> · {note}</> : null}
          </p>
        </div>
        <Link to="/media" className="text-sm text-muted hover:text-ink inline-flex items-center gap-1 shrink-0">
          Library <ExternalLink className="size-3.5" aria-hidden />
        </Link>
      </div>
      {media.kind === 'AUDIO' && <audio controls preload="metadata" src={media.url} className="w-full" />}
      {media.kind === 'VIDEO' && <video controls preload="metadata" src={media.url} poster={media.posterUrl} className="w-full rounded-lg" />}
      {media.kind === 'IMAGE' && <img src={media.url} alt="" className="max-h-96 w-auto rounded-lg self-start" />}
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

export interface TabDef<T extends string> {
  id: T
  label: string
}

/** Accessible tab strip; panels are rendered by the caller. */
export function Tabs<T extends string>({ tabs, value, onChange, label }: { tabs: TabDef<T>[]; value: T; onChange: (t: T) => void; label: string }) {
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line mb-6 -mx-1 px-1">
      {tabs.map((t) => (
        <button
          key={t.id}
          role="tab"
          type="button"
          id={`tab-${t.id}`}
          aria-selected={value === t.id}
          aria-controls={`panel-${t.id}`}
          onClick={() => onChange(t.id)}
          onKeyDown={(e) => {
            const i = tabs.findIndex((x) => x.id === value)
            if (e.key === 'ArrowRight') onChange(tabs[(i + 1) % tabs.length].id)
            if (e.key === 'ArrowLeft') onChange(tabs[(i - 1 + tabs.length) % tabs.length].id)
          }}
          className={cn(
            'px-3.5 py-2.5 -mb-px border-b-2 whitespace-nowrap text-sm font-medium transition-colors',
            value === t.id ? 'border-marigold-500 text-ink' : 'border-transparent text-muted hover:text-ink',
          )}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

export function Panel({ id, children, className }: { id: string; children: ReactNode; className?: string }) {
  return (
    <section role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`} className={className}>
      {children}
    </section>
  )
}

export function Card({ children, className, label }: { children: ReactNode; className?: string; label?: string }) {
  return (
    <div className={cn('rounded-2xl border border-line bg-surface p-5 flex flex-col gap-5', className)} aria-label={label}>
      {children}
    </div>
  )
}

/** A range input with its value shown next to the label. */
export function Slider({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format = (v) => String(v),
  hint,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  onChange: (v: number) => void
  format?: (v: number) => string
  hint?: string
}) {
  const id = `slider-${label.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex justify-between text-sm">
        <label htmlFor={id} className="font-medium">
          {label}
        </label>
        <span className="tabular text-muted">{format(value)}</span>
      </div>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="accent-marigold-500"
      />
      {hint && <p className="text-xs text-faint">{hint}</p>}
    </div>
  )
}

/** An AI text account and one of its models (OpenAI, Anthropic or Gemini). */
export function TextModelPicker({
  accounts,
  accountId,
  setAccountId,
  model,
  setModel,
  required,
  hint,
  prefix,
}: {
  accounts: ReturnType<typeof useUsableAccounts>['usable']
  accountId: string
  setAccountId: (v: string) => void
  model: string
  setModel: (v: string) => void
  required: boolean
  hint?: string
  /** Distinguishes a second picker on the same form, e.g. "Second". */
  prefix?: string
}) {
  const models = useTextModels(accountId || undefined)
  useEffect(() => {
    if (models.data && !models.data.includes(model)) setModel(models.data[0] ?? '')
  }, [models.data, model, setModel])
  if (accounts.length === 0) {
    return <p className="text-sm text-muted">{required ? 'Needs' : 'Works better with'} an OpenAI, Anthropic or Google Gemini account (AI accounts & keys).</p>
  }
  return (
    <div className="grid sm:grid-cols-2 gap-4">
      <Field label={`${prefix ? prefix + ' AI' : 'AI'} text account${required ? '' : ' (optional)'}`} hint={hint}>
        {(id, d) => (
          <Select id={id} aria-describedby={d} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {!required && <option value="">None</option>}
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} · {a.providerName}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {accountId && (
        <Field label={prefix ? `${prefix} model` : 'Model'}>
          {(id) =>
            models.isPending ? (
              <Spinner label="Loading models" />
            ) : (
              <Select id={id} value={model} onChange={(e) => setModel(e.target.value)}>
                {(models.data ?? []).map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </Select>
            )
          }
        </Field>
      )}
    </div>
  )
}

/** An image account and one of its enabled image models. */
export function ImageModelPicker({
  accounts,
  accountId,
  setAccountId,
  model,
  setModel,
  label = 'Image account',
  stacked,
}: {
  accounts: AiAccount[]
  accountId: string
  setAccountId: (v: string) => void
  model: string
  setModel: (v: string) => void
  label?: string
  stacked?: boolean
}) {
  const models = useModels()
  const provider = accounts.find((a) => a.id === accountId)?.provider
  const list = useMemo(() => (models.data ?? []).filter((m) => m.capability === 'IMAGE' && m.enabled && m.provider === provider), [models.data, provider])
  useEffect(() => {
    if (list.length && !list.some((m) => m.modelCode === model)) setModel(list[0].modelCode)
  }, [list, model, setModel])
  if (accounts.length === 0) return <p className="text-sm text-muted">Add an OpenAI or Google Gemini account under AI accounts & keys to make images.</p>
  return (
    <div className={cn('grid gap-3', !stacked && 'sm:grid-cols-2')}>
      <Field label={label}>{(id) => <AccountSelect id={id} accounts={accounts} value={accountId} onChange={setAccountId} />}</Field>
      <Field label="Model">
        {(id) =>
          list.length === 0 ? (
            <p id={id} className="text-sm text-muted">No image models are switched on for this account. Turn one on under AI accounts & keys.</p>
          ) : (
          <Select id={id} value={model} onChange={(e) => setModel(e.target.value)}>
            {list.map((m) => (
              <option key={m.id} value={m.modelCode}>
                {m.displayName}
              </option>
            ))}
          </Select>
          )
        }
      </Field>
    </div>
  )
}

/** A video account and one of its enabled models; reports the chosen model so callers can read its options. */
export function VideoModelPicker({
  accounts,
  accountId,
  setAccountId,
  modelId,
  setModelId,
  onModel,
  stacked,
}: {
  accounts: AiAccount[]
  accountId: string
  setAccountId: (v: string) => void
  modelId: string
  setModelId: (v: string) => void
  onModel?: (m?: AiModelInfo) => void
  stacked?: boolean
}) {
  const models = useModels()
  const provider = accounts.find((a) => a.id === accountId)?.provider
  const list = useMemo(() => (models.data ?? []).filter((m) => m.capability === 'VIDEO' && m.enabled && m.provider === provider), [models.data, provider])
  useEffect(() => {
    if (list.length && !list.some((m) => m.id === modelId)) setModelId(list[0].id)
  }, [list, modelId, setModelId])
  useEffect(() => {
    onModel?.(list.find((m) => m.id === modelId))
  }, [list, modelId, onModel])
  if (accounts.length === 0) return <p className="text-sm text-muted">Add a Veo, Runway, Kling or Luma account under AI accounts & keys to make video.</p>
  return (
    <div className={cn('grid gap-3', !stacked && 'sm:grid-cols-2')}>
      <Field label="Video account">{(id) => <AccountSelect id={id} accounts={accounts} value={accountId} onChange={setAccountId} />}</Field>
      <Field label="Model">
        {(id) =>
          list.length === 0 ? (
            <p id={id} className="text-sm text-muted">No video models are switched on for this account. Turn one on under AI accounts & keys.</p>
          ) : (
          <Select id={id} value={modelId} onChange={(e) => setModelId(e.target.value)}>
            {list.map((m) => (
              <option key={m.id} value={m.id}>
                {m.displayName}
              </option>
            ))}
          </Select>
          )
        }
      </Field>
    </div>
  )
}
