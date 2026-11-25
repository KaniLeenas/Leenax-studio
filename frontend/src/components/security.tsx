import QRCode from 'qrcode'
import { CheckCircle2, Copy, Download, ShieldCheck, ShieldOff } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { describeAction, formatUptime, type ServiceState } from '../lib/analytics'
import { useAudit, useMe, useMfaDisable, useMfaEnable, useMfaNewCodes, useMfaSetup, useSystemStatus } from '../lib/analyticsQueries'
import { cn } from '../lib/cn'
import { formatBytes, formatDate, formatRelative } from '../lib/format'
import { toast } from '../stores/ui'
import { Button, ErrorNotice, Field, Input, Spinner } from './ui'

// ---- two-step sign-in -------------------------------------------------------------------------------

export function TwoStepSignIn() {
  const me = useMe()
  const [codes, setCodes] = useState<string[]>()
  if (me.isPending) return <Spinner label="Loading" />
  if (me.error) return <ErrorNotice error={me.error} onRetry={() => me.refetch()} />
  if (codes) return <RecoveryCodes codes={codes} onDone={() => setCodes(undefined)} />
  return me.data.mfaEnabled ? <MfaOn left={me.data.recoveryCodesLeft ?? 0} since={me.data.mfaEnabledAt} onCodes={setCodes} /> : <MfaOff onCodes={setCodes} />
}

function MfaOff({ onCodes }: { onCodes: (c: string[]) => void }) {
  const setup = useMfaSetup()
  const enable = useMfaEnable()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [qr, setQr] = useState<string>()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!setup.data) return
    let live = true
    QRCode.toDataURL(setup.data.otpauthUri, { margin: 1, width: 200, errorCorrectionLevel: 'M' }).then((u) => live && setQr(u)).catch(() => setQr(undefined))
    return () => {
      live = false
    }
  }, [setup.data])

  if (!setup.data) {
    return (
      <form
        className="flex flex-col gap-4"
        onSubmit={(e: FormEvent) => {
          e.preventDefault()
          setup.mutate(password, { onError: (err) => toast.error(err.message) })
        }}
      >
        <p className="text-sm text-muted flex gap-2"><ShieldOff className="size-4 shrink-0 mt-0.5" aria-hidden />Off. Anyone with your password can sign in.</p>
        <p className="text-sm">With two-step sign-in, you also type a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, 2FAS, 1Password…).</p>
        <Field label="Your password" hint="To confirm it's you.">
          {(id, d) => <Input id={id} aria-describedby={d} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
        </Field>
        <div><Button type="submit" variant="primary" loading={setup.isPending} disabled={!password}>Set up two-step sign-in</Button></div>
      </form>
    )
  }

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e: FormEvent) => {
        e.preventDefault()
        enable.mutate(code, { onSuccess: (r) => { toast.success('Two-step sign-in is on'); onCodes(r.recoveryCodes) }, onError: (err) => toast.error(err.message) })
      }}
    >
      <ol className="text-sm flex flex-col gap-1 list-decimal pl-5">
        <li>Open your authenticator app and add an account.</li>
        <li>Scan this code, or type the key below.</li>
        <li>Enter the 6-digit code the app shows.</li>
      </ol>
      <div className="flex flex-wrap items-center gap-4">
        <div className="rounded-xl bg-white p-2 border border-line size-[216px] grid place-items-center">
          {qr ? <img src={qr} alt="QR code for your authenticator app" width={200} height={200} /> : <Spinner label="Drawing" />}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-xs text-muted mb-1">Key (if you can't scan)</p>
          <code className="block break-words rounded-md bg-surface-2 px-2.5 py-2 text-sm tracking-wider">{setup.data.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
          <Button type="button" size="sm" variant="ghost" className="mt-1" icon={copied ? <CheckCircle2 className="size-3.5" /> : <Copy className="size-3.5" />}
            onClick={() => void navigator.clipboard?.writeText(setup.data!.secret).then(() => setCopied(true))}>
            {copied ? 'Copied' : 'Copy key'}
          </Button>
        </div>
      </div>
      <Field label="6-digit code">
        {(id) => <Input id={id} inputMode="numeric" autoComplete="one-time-code" maxLength={7} value={code} onChange={(e) => setCode(e.target.value.replace(/[^\d ]/g, ''))} className="max-w-40 tracking-[0.3em] tabular" />}
      </Field>
      <div className="flex gap-2">
        <Button type="submit" variant="primary" loading={enable.isPending} disabled={code.replace(/\s/g, '').length !== 6}>Turn on</Button>
        <Button type="button" variant="ghost" onClick={() => setup.reset()}>Cancel</Button>
      </div>
    </form>
  )
}

function MfaOn({ left, since, onCodes }: { left: number; since?: string; onCodes: (c: string[]) => void }) {
  const [mode, setMode] = useState<'codes' | 'off'>()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const disable = useMfaDisable()
  const renew = useMfaNewCodes()
  const busy = disable.isPending || renew.isPending
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm flex gap-2"><ShieldCheck className="size-4 shrink-0 mt-0.5 text-peacock-600 dark:text-peacock-400" aria-hidden />
        <span>On{since ? ` since ${formatDate(since)}` : ''}. Signing in needs your password and a code from your app.</span>
      </p>
      <p className={cn('text-sm', left <= 2 ? 'text-rose-signal' : 'text-muted')}>
        {left} recovery code{left === 1 ? '' : 's'} left{left <= 2 ? ': make new ones now.' : '.'}
      </p>
      {!mode ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => setMode('codes')}>New recovery codes</Button>
          <Button variant="ghost" onClick={() => setMode('off')}>Turn off</Button>
        </div>
      ) : (
        <form
          className="flex flex-col gap-4 rounded-xl border border-line p-4"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            const body = { password, code }
            if (mode === 'off') disable.mutate(body, { onSuccess: () => { toast.success('Two-step sign-in is off'); setMode(undefined) }, onError: (err) => toast.error(err.message) })
            else renew.mutate(body, { onSuccess: (r) => onCodes(r.recoveryCodes), onError: (err) => toast.error(err.message) })
          }}
        >
          <p className="text-sm font-medium">{mode === 'off' ? 'Turn off two-step sign-in' : 'Replace your recovery codes'}</p>
          <Field label="Your password">{(id) => <Input id={id} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
          <Field label="Code from your app (or a recovery code)">{(id) => <Input id={id} autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} className="max-w-56" />}</Field>
          <div className="flex gap-2">
            <Button type="submit" variant={mode === 'off' ? 'danger' : 'primary'} loading={busy} disabled={!password || !code.trim()}>{mode === 'off' ? 'Turn off' : 'Make new codes'}</Button>
            <Button type="button" variant="ghost" onClick={() => setMode(undefined)}>Cancel</Button>
          </div>
        </form>
      )}
    </div>
  )
}

function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const text = `Leenax Studio recovery codes (each works once)\n\n${codes.join('\n')}\n`
  return (
    <div className="flex flex-col gap-4" aria-live="polite">
      <p className="text-sm font-medium">Save these recovery codes now</p>
      <p className="text-sm text-muted">If you lose your phone, each code signs you in once instead of an app code. They won't be shown again.</p>
      <ul className="grid grid-cols-2 gap-2 rounded-xl bg-surface-2 p-4 font-mono text-sm tabular">
        {codes.map((c) => <li key={c}>{c}</li>)}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button icon={<Download className="size-4" />} onClick={() => {
          const a = document.createElement('a')
          a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
          a.download = 'leenax-recovery-codes.txt'
          a.click()
          URL.revokeObjectURL(a.href)
        }}>Download</Button>
        <Button icon={<Copy className="size-4" />} onClick={() => void navigator.clipboard?.writeText(text).then(() => toast.info('Copied'))}>Copy</Button>
        <Button variant="primary" onClick={onDone}>I've saved them</Button>
      </div>
    </div>
  )
}

// ---- activity -----------------------------------------------------------------------------------------------

const GROUPS = [
  { id: 'security', label: 'Sign-in & security' },
  { id: 'accounts', label: 'Accounts' },
  { id: 'publishing', label: 'Publishing' },
  { id: 'files', label: 'Files' },
  { id: 'people', label: 'People' },
  { id: 'all', label: 'Everything' },
]

export function ActivityLog() {
  const [group, setGroup] = useState('security')
  const [page, setPage] = useState(0)
  const audit = useAudit(group, page)
  return (
    <div className="flex flex-col gap-3">
      <div role="radiogroup" aria-label="Show" className="flex flex-wrap gap-1">
        {GROUPS.map((g) => (
          <button key={g.id} role="radio" aria-checked={group === g.id} onClick={() => { setGroup(g.id); setPage(0) }}
            className={cn('px-3 h-8 rounded-full text-sm border', group === g.id ? 'border-marigold-500 bg-marigold-500/10 text-ink' : 'border-line text-muted hover:text-ink')}>
            {g.label}
          </button>
        ))}
      </div>
      {audit.isPending && <Spinner label="Loading activity" />}
      {audit.error && <ErrorNotice error={audit.error} onRetry={() => audit.refetch()} />}
      {audit.data && (
        audit.data.items.length === 0 ? <p className="text-sm text-muted py-4">Nothing recorded yet.</p> : (
          <ol className="divide-y divide-line border-y border-line">
            {audit.data.items.map((e) => {
              const bad = /FAILED|REJECTED/.test(e.action)
              return (
                <li key={e.id} className="py-2.5 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-sm">
                  <span className={cn('flex-1 min-w-48', bad && 'text-rose-signal')}>
                    {describeAction(e.action)}
                    {typeof e.details.name === 'string' && <span className="text-muted"> · {e.details.name}</span>}
                    {typeof e.details.platform === 'string' && !e.details.name && <span className="text-muted"> · {String(e.details.platform).toLowerCase()}</span>}
                  </span>
                  {e.userName && <span className="text-xs text-muted">{e.userName}</span>}
                  <span className="text-xs text-faint tabular">{e.ipAddress ?? ''}</span>
                  <time className="text-xs text-muted w-28 text-right" dateTime={e.createdAt} title={new Date(e.createdAt).toLocaleString()}>{formatRelative(e.createdAt)}</time>
                </li>
              )
            })}
          </ol>
        )
      )}
      {audit.data && audit.data.totalPages > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
          <span className="text-muted tabular">Page {page + 1} of {audit.data.totalPages}</span>
          <Button size="sm" variant="ghost" disabled={page + 1 >= audit.data.totalPages} onClick={() => setPage(page + 1)}>Older</Button>
        </div>
      )}
    </div>
  )
}

// ---- system health --------------------------------------------------------------------------------------------

const STATE: Record<ServiceState, { label: string; dot: string }> = {
  OK: { label: 'Working', dot: 'bg-peacock-500' },
  WARNING: { label: 'Check', dot: 'bg-marigold-500' },
  DOWN: { label: 'Down', dot: 'bg-rose-signal' },
  OFF: { label: 'Off', dot: 'bg-line' },
}

export function SystemHealth() {
  const status = useSystemStatus()
  if (status.isPending) return <Spinner label="Checking services" />
  if (status.error) return <ErrorNotice error={status.error} onRetry={() => status.refetch()} />
  const s = status.data
  const b = s.lastBackup
  return (
    <div className="flex flex-col gap-6">
      {s.warnings.length > 0 ? (
        <ul role="alert" className="rounded-xl border border-marigold-500/50 bg-marigold-500/8 p-4 flex flex-col gap-1.5 text-sm">
          {s.warnings.map((w) => <li key={w} className="flex gap-2"><span aria-hidden>•</span>{w}</li>)}
        </ul>
      ) : (
        <p className="text-sm flex gap-2 text-peacock-600 dark:text-peacock-400"><CheckCircle2 className="size-4 mt-0.5" aria-hidden />Everything looks fine.</p>
      )}

      <div>
        <h3 className="font-semibold mb-2">Services</h3>
        <ul className="divide-y divide-line border-y border-line">
          {s.services.map((c) => (
            <li key={c.name} className="py-2.5 flex items-center gap-3 text-sm">
              <span aria-hidden className={cn('size-2.5 rounded-full', STATE[c.state].dot)} />
              <span className="font-medium w-56 shrink-0">{c.name}</span>
              <span className="text-muted flex-1 min-w-0">{c.detail}</span>
              <span className="text-xs text-faint">{STATE[c.state].label}</span>
            </li>
          ))}
        </ul>
      </div>

      <div>
        <h3 className="font-semibold mb-2">Background work</h3>
        <table className="w-full text-sm tabular">
          <thead className="text-xs text-muted"><tr><th className="text-left font-medium pb-2">Queue</th><th className="text-right font-medium pb-2">Waiting</th><th className="text-right font-medium pb-2">Running</th><th className="text-right font-medium pb-2">Failed (24 h)</th></tr></thead>
          <tbody>
            {s.queues.map((q) => (
              <tr key={q.name} className="border-t border-line">
                <td className="py-2">{q.name}</td>
                <td className="py-2 text-right">{q.waiting < 0 ? '—' : q.waiting}</td>
                <td className="py-2 text-right">{q.running < 0 ? '—' : q.running}</td>
                <td className={cn('py-2 text-right', q.failedToday > 0 && 'text-rose-signal')}>{q.failedToday}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid sm:grid-cols-2 gap-4">
        <div className="rounded-xl border border-line p-4 text-sm flex flex-col gap-1">
          <h3 className="font-semibold mb-1">Backups</h3>
          {b ? (
            <>
              <p className={cn(b.ok ? 'text-ink' : 'text-rose-signal')}>{b.ok ? 'Last backup' : 'Last backup failed'}{b.finishedAt ? ` ${formatRelative(b.finishedAt)}` : ''}</p>
              {b.ok && <p className="text-muted">Database {formatBytes(b.databaseBytes ?? 0)} · media {formatBytes(b.mediaBytes ?? 0)}</p>}
              {b.folder && <p className="text-faint text-xs">backups/{b.folder}</p>}
            </>
          ) : <p className="text-muted">No backup yet.</p>}
          {s.backupDisk && <p className="text-faint text-xs">{formatBytes(s.backupDisk.freeBytes)} free of {formatBytes(s.backupDisk.totalBytes)}</p>}
        </div>
        <div className="rounded-xl border border-line p-4 text-sm flex flex-col gap-1">
          <h3 className="font-semibold mb-1">Storage</h3>
          <p>{s.mediaFiles.toLocaleString()} files · {formatBytes(s.mediaBytes)}</p>
          {s.databaseBytes != null && <p className="text-muted">Database {formatBytes(s.databaseBytes)}</p>}
          <p className="text-faint text-xs">Up {formatUptime(s.uptime)} · Java {s.javaVersion}{s.schemaVersion ? ` · schema v${s.schemaVersion}` : ''}</p>
        </div>
      </div>
      <p className="text-xs text-faint">Checked {formatRelative(s.checkedAt)}; refreshes every minute.</p>
    </div>
  )
}
