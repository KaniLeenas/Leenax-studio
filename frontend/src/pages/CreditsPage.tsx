import { AlertTriangle, Pencil, Plus, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill } from '../components/ui'
import {
  useAccounts,
  useDeleteBalance,
  useModels,
  useSaveBalance,
  useUpdateModel,
  useUsage,
  useUsageSummary,
} from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { formatAmount, formatDate, formatRelative, UNIT_LABEL } from '../lib/format'
import { describePricing } from '../lib/generation'
import type { AiAccount, AiModelInfo, Balance, CreditUnit, Pricing } from '../lib/types'
import { toast } from '../stores/ui'

const UNITS: CreditUnit[] = ['CREDITS', 'USD', 'UNITS', 'SECONDS']

export function CreditsPage() {
  const accounts = useAccounts()
  const summary = useUsageSummary(30)

  return (
    <>
      <PageHeader
        title="Credits & usage"
        description="What you have left on each account, what each generation cost, and the prices used for estimates."
      />

      <section aria-labelledby="month" className="mb-12">
        <h2 id="month" className="sr-only">
          Last 30 days
        </h2>
        {summary.data && (
          <div className="grid grid-cols-2 md:grid-cols-4 rounded-2xl border border-line bg-surface">
            <SummaryStat label="Generations, last 30 days" value={summary.data.generations.toString()} sub={summary.data.failed ? `${summary.data.failed} failed` : 'none failed'} />
            <SummaryStat label="Video generated" value={`${Math.round(summary.data.seconds)} s`} />
            <SummaryStat
              label="Spent"
              value={Object.entries(summary.data.costByUnit).map(([u, v]) => formatAmount(v, u)).join(' + ') || '—'}
              wide
            />
          </div>
        )}
      </section>

      {accounts.isPending && <Spinner label="Loading balances" />}
      {accounts.error && <ErrorNotice error={accounts.error} onRetry={() => accounts.refetch()} />}
      {accounts.data &&
        (accounts.data.length === 0 ? (
          <EmptyState
            title="No accounts yet"
            body="Connect a provider first; its balance will show up here."
            action={
              <Link to="/manage/ai-accounts" className="h-10 inline-flex items-center px-4 rounded-lg bg-marigold-500 text-[#1e2240] font-semibold">
                Add an account
              </Link>
            }
          />
        ) : (
          <section aria-labelledby="balances" className="mb-14">
            <h2 id="balances" className="text-xl font-semibold mb-4">
              Balances
            </h2>
            <div className="grid lg:grid-cols-2 gap-4">
              {accounts.data.map((a) => (
                <AccountBalances key={a.id} account={a} />
              ))}
            </div>
          </section>
        ))}

      <ModelPrices />
      <UsageHistory accounts={accounts.data ?? []} />
    </>
  )
}

function SummaryStat({ label, value, sub, wide }: { label: string; value: string; sub?: string; wide?: boolean }) {
  return (
    <div className={cn('px-5 py-5 border-line [&:not(:first-child)]:border-l', wide && 'col-span-2 max-md:border-l-0 max-md:border-t')}>
      <p className="text-sm text-muted">{label}</p>
      <p className="font-display text-2xl font-semibold tabular mt-1">{value}</p>
      {sub && <p className="text-xs text-faint mt-0.5">{sub}</p>}
    </div>
  )
}

// ---- balances ---------------------------------------------------------------

function AccountBalances({ account: a }: { account: AiAccount }) {
  const [editing, setEditing] = useState<Balance | 'new'>()
  const remove = useDeleteBalance()
  const primary = a.totals[0]

  return (
    <article className="rounded-2xl border border-line bg-surface p-5">
      <header className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-semibold truncate">{a.name}</h3>
          <p className="text-sm text-faint">
            {a.providerName}
            {a.balanceSyncedAt ? `, synced ${formatRelative(a.balanceSyncedAt)}` : a.balanceApi ? '' : ', entered by hand'}
          </p>
        </div>
        <div className="text-right">
          <p className={cn('font-display text-2xl font-semibold tabular', a.lowCredit && 'text-rose-signal')}>
            {primary ? formatAmount(primary.amount, primary.unit) : '—'}
          </p>
          {a.lowCredit && (
            <p className="text-xs text-rose-signal inline-flex items-center gap-1">
              <AlertTriangle className="size-3.5" aria-hidden />
              Low
            </p>
          )}
        </div>
      </header>

      {a.balances.length > 0 ? (
        <ul className="mt-4 divide-y divide-[var(--line)] border-t border-line">
          {a.balances.map((b) => (
            <li key={b.id} className={cn('flex items-center gap-3 py-2.5 text-sm', b.expired && 'opacity-50')}>
              <div className="min-w-0 flex-1">
                <p className="truncate">
                  {b.label}{' '}
                  <StatusPill tone={b.kind === 'FREE' ? 'done' : 'neutral'}>{b.kind === 'FREE' ? 'Free' : 'Paid'}</StatusPill>
                </p>
                <p className={cn('text-xs', b.expiringSoon ? 'text-rose-signal' : 'text-faint')}>
                  {b.expired ? `Expired ${formatDate(b.expiresAt)}` : b.expiresAt ? `Expires ${formatDate(b.expiresAt)}` : 'No expiry'}
                  {b.source === 'PROVIDER' ? `, from ${a.providerName}` : ''}
                </p>
              </div>
              <span className="tabular font-medium">{formatAmount(b.amount, b.unit)}</span>
              {b.source === 'MANUAL' && (
                <span className="flex">
                  <button onClick={() => setEditing(b)} className="p-1.5 rounded text-faint hover:text-ink" aria-label={`Edit ${b.label}`}>
                    <Pencil className="size-4" />
                  </button>
                  <button
                    onClick={() => remove.mutate(b.id, { onSuccess: () => toast.success(`Removed ${b.label}`), onError: (e) => toast.error(e.message) })}
                    className="p-1.5 rounded text-faint hover:text-rose-signal"
                    aria-label={`Remove ${b.label}`}
                  >
                    <Trash2 className="size-4" />
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-sm text-faint border-t border-line pt-3">
          {a.balanceApi ? 'Use Sync balance on the accounts page to read it from the provider.' : 'No balance recorded yet.'}
        </p>
      )}

      <Button size="sm" variant="ghost" className="mt-3 -ml-2" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
        Add credits by hand
      </Button>
      {editing && <BalanceDialog account={a} balance={editing === 'new' ? undefined : editing} onClose={() => setEditing(undefined)} />}
    </article>
  )
}

function BalanceDialog({ account, balance, onClose }: { account: AiAccount; balance?: Balance; onClose: () => void }) {
  const save = useSaveBalance()
  const [label, setLabel] = useState(balance?.label ?? '')
  const [kind, setKind] = useState<'FREE' | 'PAID'>(balance?.kind ?? 'PAID')
  const [amount, setAmount] = useState(balance?.amount?.toString() ?? '')
  const [unit, setUnit] = useState<CreditUnit>(balance?.unit ?? account.totals[0]?.unit ?? 'CREDITS')
  const [expires, setExpires] = useState(balance?.expiresAt ? balance.expiresAt.slice(0, 10) : '')

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate(
      {
        accountId: account.id,
        id: balance?.id,
        label: label.trim(),
        kind,
        amount: Number(amount),
        unit,
        expiresAt: expires ? new Date(`${expires}T23:59:59`).toISOString() : undefined,
        clearExpiry: !expires,
      },
      { onSuccess: () => { toast.success('Balance saved'); onClose() }, onError: (err) => toast.error(err.message) },
    )
  }

  return (
    <Dialog open title={balance ? 'Edit credits' : `Add credits to ${account.name}`} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Label" hint="e.g. “Monthly plan”, “Welcome bonus”.">
          {(id, d) => <Input id={id} aria-describedby={d} value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} />}
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Amount">
            {(id) => <Input id={id} type="number" min={0} step="any" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />}
          </Field>
          <Field label="Unit">
            {(id) => (
              <Select id={id} value={unit} onChange={(e) => setUnit(e.target.value as CreditUnit)}>
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {u === 'USD' ? 'US dollars' : UNIT_LABEL[u]}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Type">
            {(id) => (
              <Select id={id} value={kind} onChange={(e) => setKind(e.target.value as 'FREE' | 'PAID')}>
                <option value="PAID">Paid</option>
                <option value="FREE">Free</option>
              </Select>
            )}
          </Field>
          <Field label="Expires on" hint="Optional">
            {(id, d) => <Input id={id} aria-describedby={d} type="date" value={expires} onChange={(e) => setExpires(e.target.value)} />}
          </Field>
        </div>
        <p className="text-sm text-faint">Leenax subtracts each generation's cost from these credits, free ones first.</p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={save.isPending} disabled={!label.trim() || amount === '' || Number(amount) < 0}>
            Save credits
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

// ---- model prices -----------------------------------------------------------

function ModelPrices() {
  const models = useModels()
  const update = useUpdateModel()
  const [editing, setEditing] = useState<AiModelInfo>()
  const video = models.data?.filter((m) => m.capability === 'VIDEO') ?? []

  return (
    <section aria-labelledby="prices" className="mb-14">
      <h2 id="prices" className="text-xl font-semibold">
        Model prices
      </h2>
      <p className="text-sm text-muted mb-4 max-w-2xl">
        Used for cost estimates before you generate. Runway reports exact costs after each video; for the others, keep these in line with your provider's price page.
      </p>
      {models.isPending && <Spinner label="Loading models" />}
      {models.data && (
        <div className="overflow-x-auto rounded-2xl border border-line bg-surface">
          <table className="w-full text-sm">
            <thead className="text-left text-faint">
              <tr className="border-b border-line">
                <th className="font-medium px-5 py-3">Model</th>
                <th className="font-medium px-3 py-3">Price</th>
                <th className="font-medium px-3 py-3">Shown in generator</th>
                <th className="px-3 py-3">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--line)]">
              {video.map((m) => (
                <tr key={m.id}>
                  <td className="px-5 py-3">
                    <p className="font-medium">{m.displayName}</p>
                    <p className="text-xs text-faint">{m.modelCode}</p>
                  </td>
                  <td className={cn('px-3 py-3', (m.pricing.rates?.length ?? 0) === 0 && 'text-faint')}>{describePricing(m.pricing)}</td>
                  <td className="px-3 py-3">
                    <label className="inline-flex items-center gap-2">
                      <input
                        type="checkbox"
                        className="size-4 accent-[var(--color-marigold-500)]"
                        checked={m.enabled}
                        onChange={(e) => update.mutate({ id: m.id, enabled: e.target.checked }, { onError: (err) => toast.error(err.message) })}
                      />
                      <span className="sr-only">Show {m.displayName}</span>
                      {m.enabled ? 'Yes' : 'Hidden'}
                    </label>
                  </td>
                  <td className="px-3 py-3 text-right">
                    <Button size="sm" variant="ghost" onClick={() => setEditing(m)}>
                      Edit price
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <PriceDialog model={editing} onClose={() => setEditing(undefined)} />}
    </section>
  )
}

interface RateRow {
  perSecond: string
  audio: '' | 'true' | 'false'
  resolution: string
}

function PriceDialog({ model, onClose }: { model: AiModelInfo; onClose: () => void }) {
  const update = useUpdateModel()
  const [unit, setUnit] = useState<CreditUnit>(model.pricing.unit ?? 'CREDITS')
  const [minimum, setMinimum] = useState(model.pricing.minimum?.toString() ?? '')
  const [rows, setRows] = useState<RateRow[]>(
    (model.pricing.rates ?? []).map((r) => ({
      perSecond: r.perSecond.toString(),
      audio: r.audio === undefined ? '' : r.audio ? 'true' : 'false',
      resolution: r.resolution ?? '',
    })),
  )
  const resolutions = model.options.resolutions ?? []

  function submit(e: FormEvent) {
    e.preventDefault()
    const pricing: Pricing = {
      unit,
      ...(minimum ? { minimum: Number(minimum) } : {}),
      rates: rows
        .filter((r) => r.perSecond !== '')
        .map((r) => ({
          perSecond: Number(r.perSecond),
          ...(r.audio ? { audio: r.audio === 'true' } : {}),
          ...(r.resolution ? { resolution: r.resolution } : {}),
        })),
    }
    update.mutate({ id: model.id, pricing }, { onSuccess: () => { toast.success('Price saved'); onClose() }, onError: (err) => toast.error(err.message) })
  }

  return (
    <Dialog open title={`Price for ${model.displayName}`} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-4">
          <Field label="Charged in">
            {(id) => (
              <Select id={id} value={unit} onChange={(e) => setUnit(e.target.value as CreditUnit)}>
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {u === 'USD' ? 'US dollars' : UNIT_LABEL[u]}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Minimum per video" hint="Optional">
            {(id, d) => <Input id={id} aria-describedby={d} type="number" min={0} step="any" value={minimum} onChange={(e) => setMinimum(e.target.value)} />}
          </Field>
        </div>

        <fieldset>
          <legend className="text-sm font-medium mb-2">Price per second of video</legend>
          {rows.length === 0 && <p className="text-sm text-faint mb-2">No price yet. Add a rate.</p>}
          <ul className="flex flex-col gap-2">
            {rows.map((r, i) => (
              <li key={i} className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2 items-center">
                <Input
                  aria-label="Price per second"
                  type="number"
                  min={0}
                  step="any"
                  value={r.perSecond}
                  onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, perSecond: e.target.value } : x)))}
                />
                <Select aria-label="Sound" value={r.audio} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, audio: e.target.value as RateRow['audio'] } : x)))}>
                  <option value="">Any sound</option>
                  <option value="false">Silent</option>
                  <option value="true">With sound</option>
                </Select>
                <Select aria-label="Resolution" value={r.resolution} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, resolution: e.target.value } : x)))}>
                  <option value="">Any resolution</option>
                  {resolutions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </Select>
                <button type="button" onClick={() => setRows(rows.filter((_, j) => j !== i))} className="p-2 text-faint hover:text-rose-signal" aria-label="Remove rate">
                  <Trash2 className="size-4" />
                </button>
              </li>
            ))}
          </ul>
          <Button type="button" size="sm" variant="ghost" className="mt-2 -ml-2" icon={<Plus className="size-4" />} onClick={() => setRows([...rows, { perSecond: '', audio: '', resolution: '' }])}>
            Add rate
          </Button>
        </fieldset>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={update.isPending}>
            Save price
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

// ---- history ----------------------------------------------------------------

const SOURCE_TEXT = { PROVIDER_REPORTED: 'reported', ESTIMATED: 'estimated', UNKNOWN: '' } as const

function UsageHistory({ accounts }: { accounts: AiAccount[] }) {
  const [accountId, setAccountId] = useState<string>()
  const [page, setPage] = useState(0)
  const usage = useUsage({ accountId, page })

  return (
    <section aria-labelledby="history">
      <div className="flex flex-wrap items-end justify-between gap-3 mb-4">
        <h2 id="history" className="text-xl font-semibold">
          Usage history
        </h2>
        {accounts.length > 1 && (
          <div className="w-full sm:w-72">
          <Select
            aria-label="Filter by account"
            value={accountId ?? ''}
            onChange={(e) => {
              setAccountId(e.target.value || undefined)
              setPage(0)
            }}
          >
            <option value="">All accounts</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.providerName})
              </option>
            ))}
          </Select>
          </div>
        )}
      </div>
      {usage.isPending && <Spinner label="Loading history" />}
      {usage.data &&
        (usage.data.items.length === 0 ? (
          <EmptyState title="Nothing generated yet" body="Each generation will be listed here with its cost." />
        ) : (
          <>
            <div className="overflow-x-auto rounded-2xl border border-line bg-surface">
              <table className="w-full text-sm">
                <thead className="text-left text-faint">
                  <tr className="border-b border-line">
                    <th className="font-medium px-5 py-3">When</th>
                    <th className="font-medium px-3 py-3">Account</th>
                    <th className="font-medium px-3 py-3">Model</th>
                    <th className="font-medium px-3 py-3 text-right">Length</th>
                    <th className="font-medium px-5 py-3 text-right">Cost</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--line)]">
                  {usage.data.items.map((u) => (
                    <tr key={u.id} className={cn(!u.success && 'text-faint')}>
                      <td className="px-5 py-2.5 whitespace-nowrap">{formatRelative(u.createdAt)}</td>
                      <td className="px-3 py-2.5">
                        {u.accountName}
                        <span className="text-faint">, {u.providerName}</span>
                      </td>
                      <td className="px-3 py-2.5">{u.modelCode}</td>
                      <td className="px-3 py-2.5 text-right tabular">{u.outputSeconds != null ? `${Math.round(u.outputSeconds)} s` : '—'}</td>
                      <td className="px-5 py-2.5 text-right tabular whitespace-nowrap">
                        {!u.success ? 'Failed' : u.cost != null ? formatAmount(u.cost, u.costUnit) : '—'}
                        {u.success && SOURCE_TEXT[u.costSource] && <span className="text-faint text-xs"> {SOURCE_TEXT[u.costSource]}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {usage.data.totalPages > 1 && (
              <div className="flex items-center justify-center gap-3 mt-6">
                <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <span className="text-sm text-muted tabular">
                  Page {page + 1} of {usage.data.totalPages}
                </span>
                <Button size="sm" disabled={page + 1 >= usage.data.totalPages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            )}
          </>
        ))}
    </section>
  )
}
