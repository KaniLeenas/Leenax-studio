import { ExternalLink, KeyRound, Plus, RefreshCw, ShieldCheck, Star, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill } from '../components/ui'
import {
  useAccountAction,
  useAccounts,
  useCreateAccount,
  useDeleteAccount,
  useProviders,
  useReplaceKey,
  useUpdateAccount,
} from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatAmount, formatRelative, UNIT_LABEL } from '../lib/format'
import { ACCOUNT_STATUS_TEXT, accountStatusTone } from '../lib/generation'
import type { AiAccount, Capability, ProviderCode, ProviderInfo } from '../lib/types'
import { toast } from '../stores/ui'

const CAPABILITY_TEXT: Record<Capability, string> = {
  VIDEO: 'Video generation',
  TEXT: 'Prompt writing and translation',
  IMAGE: 'Images',
  VOICE: 'Voice-overs',
  TRANSCRIPTION: 'Subtitles',
  SOUND: 'Sound effects',
}

function joinList(items: string[]) {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export function AiAccountsPage() {
  const providers = useProviders()
  const accounts = useAccounts()
  const [adding, setAdding] = useState<ProviderCode | 'any'>()
  const [editing, setEditing] = useState<AiAccount>()

  const loading = providers.isPending || accounts.isPending
  const error = providers.error ?? accounts.error

  return (
    <>
      <PageHeader
        title="AI accounts & keys"
        description="Connect your own provider accounts. Keys are encrypted on your server and never sent back to the browser."
        actions={
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setAdding('any')}>
            Add account
          </Button>
        }
      />

      {loading && <Spinner label="Loading accounts" />}
      {error && <ErrorNotice error={error} onRetry={() => { providers.refetch(); accounts.refetch() }} />}

      {providers.data && accounts.data && (
        <>
          {accounts.data.length === 0 && (
            <div className="mb-10">
              <EmptyState
                title="No accounts connected yet"
                body="Add a Runway, Kling, Luma or Google Gemini key to start generating video. Add OpenAI, Anthropic or Gemini to get help writing prompts."
                action={
                  <Button variant="primary" onClick={() => setAdding('any')}>
                    Add your first account
                  </Button>
                }
              />
            </div>
          )}
          <div className="flex flex-col gap-10">
            {providers.data.map((p) => {
              const list = accounts.data.filter((a) => a.provider === p.code)
              if (list.length === 0) return null
              return <ProviderSection key={p.code} provider={p} accounts={list} onAdd={() => setAdding(p.code)} onEdit={setEditing} />
            })}
          </div>

          <NotConnected providers={providers.data.filter((p) => !accounts.data.some((a) => a.provider === p.code))} onAdd={setAdding} />
        </>
      )}

      {providers.data && (
        <AddAccountDialog
          open={!!adding}
          providers={providers.data}
          initial={adding && adding !== 'any' ? adding : undefined}
          onClose={() => setAdding(undefined)}
        />
      )}
      {editing && <EditAccountDialog account={editing} onClose={() => setEditing(undefined)} />}
    </>
  )
}

function ProviderSection({
  provider,
  accounts,
  onAdd,
  onEdit,
}: {
  provider: ProviderInfo
  accounts: AiAccount[]
  onAdd: () => void
  onEdit: (a: AiAccount) => void
}) {
  return (
    <section aria-labelledby={`p-${provider.code}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2 mb-3">
        <div>
          <h2 id={`p-${provider.code}`} className="text-xl font-semibold">
            {provider.name}
          </h2>
          <p className="text-sm text-faint">
            {joinList(provider.capabilities.map((c) => CAPABILITY_TEXT[c]))}
            {provider.balanceApi ? '. Balance syncs automatically.' : '. Enter your balance by hand under Credits & usage.'}
          </p>
        </div>
        <Button size="sm" variant="ghost" icon={<Plus className="size-4" />} onClick={onAdd}>
          Another {provider.name} account
        </Button>
      </div>
      <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
        {accounts.map((a) => (
          <AccountRow key={a.id} account={a} onEdit={() => onEdit(a)} />
        ))}
      </ul>
    </section>
  )
}

function AccountRow({ account: a, onEdit }: { account: AiAccount; onEdit: () => void }) {
  const action = useAccountAction()
  const running = action.isPending ? action.variables?.action : undefined

  function run(kind: 'verify' | 'sync-balance' | 'default') {
    action.mutate(
      { id: a.id, action: kind },
      {
        onSuccess: () =>
          toast.success(kind === 'verify' ? `${a.name}: key works` : kind === 'sync-balance' ? 'Balance updated' : `${a.name} is now the default`),
        onError: (e) => toast.error(e.message),
      },
    )
  }

  const primary = a.totals[0]
  return (
    <li className="px-5 py-4 flex flex-wrap items-center gap-x-6 gap-y-3">
      <div className="min-w-0 flex-1 basis-60">
        <div className="flex items-center gap-2 flex-wrap">
          <p className="font-medium truncate">{a.name}</p>
          {a.isDefault && (
            <span className="inline-flex items-center gap-1 text-xs text-marigold-700 dark:text-marigold-300">
              <Star className="size-3.5 fill-current" aria-hidden />
              Default
            </span>
          )}
          <StatusPill tone={accountStatusTone(a.status)}>{ACCOUNT_STATUS_TEXT[a.status]}</StatusPill>
        </div>
        <p className="text-sm text-faint mt-0.5 tabular">
          {a.maskedKey}
          {a.lastVerifiedAt ? `, checked ${formatRelative(a.lastVerifiedAt)}` : ''}
        </p>
        {a.lastError && a.status !== 'ACTIVE' && <p className="text-sm text-rose-signal mt-1">{a.lastError}</p>}
      </div>

      <div className="min-w-36">
        <p className={cn('font-display text-xl font-semibold tabular', a.lowCredit && 'text-rose-signal')}>
          {primary ? formatAmount(primary.amount, primary.unit) : '—'}
        </p>
        <p className="text-xs text-faint">
          {a.lowCredit ? 'Below your warning level' : a.expiringSoon ? 'Some credits expire this week' : primary ? 'Available' : 'No balance recorded'}
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        <Button size="sm" icon={<ShieldCheck className="size-4" />} loading={running === 'verify'} onClick={() => run('verify')}>
          Check key
        </Button>
        {a.balanceApi && (
          <Button size="sm" icon={<RefreshCw className="size-4" />} loading={running === 'sync-balance'} onClick={() => run('sync-balance')}>
            Sync balance
          </Button>
        )}
        {!a.isDefault && (
          <Button size="sm" variant="ghost" loading={running === 'default'} onClick={() => run('default')}>
            Make default
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={onEdit}>
          Edit
        </Button>
      </div>
    </li>
  )
}

function NotConnected({ providers, onAdd }: { providers: ProviderInfo[]; onAdd: (p: ProviderCode) => void }) {
  if (providers.length === 0) return null
  return (
    <section className="mt-12">
      <h2 className="text-lg font-semibold mb-3">Other providers you can connect</h2>
      <ul className="grid sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {providers.map((p) => (
          <li key={p.code} className="rounded-xl border border-dashed border-line p-4 flex items-start justify-between gap-3">
            <div>
              <p className="font-medium">{p.name}</p>
              <p className="text-sm text-faint">{p.capabilities.map((c) => CAPABILITY_TEXT[c]).join(' and ')}</p>
            </div>
            <Button size="sm" onClick={() => onAdd(p.code)}>
              Connect
            </Button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function AddAccountDialog({
  open,
  providers,
  initial,
  onClose,
}: {
  open: boolean
  providers: ProviderInfo[]
  initial?: ProviderCode
  onClose: () => void
}) {
  const create = useCreateAccount()
  const [provider, setProvider] = useState<ProviderCode>(initial ?? providers[0].code)
  const [name, setName] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [makeDefault, setMakeDefault] = useState(false)
  const [openedFor, setOpenedFor] = useState<string>()

  // Reset the form each time the dialog opens (render-time, no effect needed).
  const openKey = open ? `open-${initial ?? 'any'}` : undefined
  if (openKey !== openedFor) {
    setOpenedFor(openKey)
    if (open) {
      setProvider(initial ?? providers[0].code)
      setName('')
      setApiKey('')
      setMakeDefault(false)
      create.reset()
    }
  }

  const info = providers.find((p) => p.code === provider)!

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate(
      { provider, name: name.trim() || `${info.name} account`, apiKey: apiKey.trim(), makeDefault },
      {
        onSuccess: (a) => {
          setApiKey('')
          toast.success(a.status === 'ACTIVE' ? `${a.name} connected` : `${a.name} saved. ${info.name} couldn't be reached to check the key.`)
          onClose()
        },
      },
    )
  }

  return (
    <Dialog open={open} title="Add an AI account" onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4" autoComplete="off">
        <Field label="Provider">
          {(id) => (
            <Select id={id} value={provider} onChange={(e) => setProvider(e.target.value as ProviderCode)}>
              {providers.map((p) => (
                <option key={p.code} value={p.code}>
                  {p.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Account name" hint="Your own label, e.g. “Personal” or “Free trial”.">
          {(id, d) => <Input id={id} aria-describedby={d} value={name} maxLength={80} placeholder={`${info.name} account`} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label={provider === 'GOOGLE_CLOUD_TTS' ? 'Service account key or API key' : 'API key'} hint={info.keyHelp}>
          {(id, d) => (
            <Input
              id={id}
              aria-describedby={d}
              type="password"
              autoComplete="new-password"
              spellCheck={false}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
          )}
        </Field>
        {provider === 'GOOGLE_CLOUD_TTS' && <KeyFile onRead={setApiKey} />}
        <a href={info.keyUrl} target="_blank" rel="noreferrer noopener" className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink underline underline-offset-4 self-start">
          Get a key from {info.name}
          <ExternalLink className="size-3.5" aria-hidden />
        </a>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={makeDefault} onChange={(e) => setMakeDefault(e.target.checked)} className="size-4 accent-[var(--color-marigold-500)]" />
          Use this as my default {info.name} account
        </label>
        {create.error && (
          <p role="alert" className="text-sm text-rose-signal">
            {create.error.message}
          </p>
        )}
        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={create.isPending} disabled={apiKey.trim().length < 8}>
            {create.isPending ? `Checking with ${info.name}` : 'Check and save'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

function EditAccountDialog({ account, onClose }: { account: AiAccount; onClose: () => void }) {
  const update = useUpdateAccount()
  const replace = useReplaceKey()
  const remove = useDeleteAccount()
  const [name, setName] = useState(account.name)
  const [threshold, setThreshold] = useState(account.lowCreditThreshold?.toString() ?? '')
  const [newKey, setNewKey] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const unit = account.totals[0]?.unit

  function save(e: FormEvent) {
    e.preventDefault()
    const t = threshold.trim()
    update.mutate(
      {
        id: account.id,
        name: name.trim() !== account.name ? name.trim() : undefined,
        ...(t === '' ? { clearLowCreditThreshold: true } : { lowCreditThreshold: Number(t) }),
      },
      {
        onSuccess: () => {
          toast.success('Account saved')
          onClose()
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  function toggle() {
    const enabled = account.status === 'DISABLED'
    update.mutate(
      { id: account.id, enabled },
      { onSuccess: () => { toast.success(enabled ? 'Account switched on' : 'Account switched off'); onClose() }, onError: (e) => toast.error(e.message) },
    )
  }

  return (
    <Dialog open title={`Edit ${account.name}`} onClose={onClose}>
      <form onSubmit={save} className="flex flex-col gap-4">
        <Field label="Account name">{(id) => <Input id={id} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field
          label="Warn me when the balance drops below"
          hint={unit ? `In ${UNIT_LABEL[unit]}, the same unit as this account's balance. Leave empty for no warning.` : 'Leave empty for no warning.'}
        >
          {(id, d) => <Input id={id} aria-describedby={d} type="number" min={0} step="any" inputMode="decimal" value={threshold} onChange={(e) => setThreshold(e.target.value)} />}
        </Field>
        <div className="flex justify-end">
          <Button type="submit" variant="primary" loading={update.isPending} disabled={!name.trim()}>
            Save changes
          </Button>
        </div>
      </form>

      <div className="mt-6 pt-5 border-t border-line">
        <p className="font-medium flex items-center gap-2">
          <KeyRound className="size-4" aria-hidden />
          Replace API key
        </p>
        <p className="text-sm text-faint mb-3">The new key is checked with {account.providerName} before it replaces the old one.</p>
        <div className="flex gap-2">
          <Input type="password" aria-label="New API key" autoComplete="new-password" spellCheck={false} value={newKey} onChange={(e) => setNewKey(e.target.value)} />
          {account.provider === 'GOOGLE_CLOUD_TTS' && <KeyFile onRead={setNewKey} compact />}
          <Button
            loading={replace.isPending}
            disabled={newKey.trim().length < 8}
            onClick={() =>
              replace.mutate(
                { id: account.id, apiKey: newKey.trim() },
                { onSuccess: () => { setNewKey(''); toast.success('Key replaced'); onClose() }, onError: (e) => toast.error(e.message) },
              )
            }
          >
            Replace
          </Button>
        </div>
      </div>

      <div className="mt-6 pt-5 border-t border-line flex flex-wrap items-center gap-2">
        {confirmDelete ? (
          <>
            <span className="text-sm text-rose-signal mr-auto">Delete this account and its saved key? Usage history is kept.</span>
            <Button size="sm" onClick={() => setConfirmDelete(false)}>
              Keep
            </Button>
            <Button
              size="sm"
              variant="danger"
              loading={remove.isPending}
              onClick={() => remove.mutate(account.id, { onSuccess: () => { toast.success(`Deleted ${account.name}`); onClose() }, onError: (e) => toast.error(e.message) })}
            >
              Delete account
            </Button>
          </>
        ) : (
          <>
            <Button size="sm" variant="ghost" icon={<Trash2 className="size-4" />} className="mr-auto" onClick={() => setConfirmDelete(true)}>
              Delete
            </Button>
            <Button size="sm" onClick={toggle} loading={update.isPending}>
              {account.status === 'DISABLED' ? 'Switch on' : 'Switch off'}
            </Button>
          </>
        )}
      </div>
    </Dialog>
  )
}

/**
 * Reads a Google service account key file (JSON) into the key field. The file stays in this
 * page's memory until it's sent to the server, which checks it with Google and stores it encrypted.
 */
function KeyFile({ onRead, compact }: { onRead: (text: string) => void; compact?: boolean }) {
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  return (
    <div className={compact ? 'shrink-0' : 'text-sm'}>
      <label className="inline-flex items-center gap-2 cursor-pointer rounded-lg border border-line bg-surface px-3 h-10 hover:bg-surface-2 has-[:focus-visible]:ring-2 ring-marigold-500">
        <input type="file" accept="application/json,.json" className="sr-only" onChange={(e) => {
          const f = e.target.files?.[0]
          e.target.value = ''
          if (!f) return
          setError('')
          if (f.size > 8000) return setError('That file is too big for a key file')
          f.text().then((t) => {
            try {
              const j = JSON.parse(t)
              if (j.type !== 'service_account') return setError('That isn’t a service account key file')
              onRead(t)
              setName(f.name)
            } catch {
              setError('That file isn’t JSON')
            }
          })
        }} />
        {compact ? 'Key file' : 'Choose key file (.json)'}
      </label>
      {!compact && name && <span className="ml-2 text-muted">{name} read</span>}
      {error && <p role="alert" className="text-rose-signal mt-1">{error}</p>}
    </div>
  )
}
