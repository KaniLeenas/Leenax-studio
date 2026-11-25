import { CheckCircle2, Copy, LogIn, RefreshCw, Unplug } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PlatformMark } from '../components/publish'
import { Button, ErrorNotice, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { formatDate } from '../lib/format'
import { PLATFORM_ORDER, type PlatformInfo, type SignIn, type SocialAccount } from '../lib/publish'
import { useAccountAction, useConnect, usePlatforms, useSocialAccounts } from '../lib/publishQueries'
import { useAuthStatus } from '../lib/queries'
import { toast } from '../stores/ui'

const SIGN_IN: { id: SignIn; name: string; connects: string }[] = [
  { id: 'GOOGLE', name: 'Google', connects: 'YouTube channel' },
  { id: 'META', name: 'Meta', connects: 'Facebook Pages and the Instagram professional accounts linked to them' },
  { id: 'TIKTOK', name: 'TikTok', connects: 'TikTok account' },
]

export function SocialAccountsPage() {
  const platforms = usePlatforms()
  const accounts = useSocialAccounts()
  const [params, setParams] = useSearchParams()

  // The sign-in pages send the browser back here with the outcome in the address.
  useEffect(() => {
    const connected = params.get('connected')
    const error = params.get('error')
    if (!connected && !error) return
    if (error) toast.error(error)
    else toast.success(connected === '1' ? 'Account connected' : `${connected} accounts connected`)
    setParams({}, { replace: true })
  }, [params, setParams])

  return (
    <>
      <header className="mb-8">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Social accounts</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Connect each platform through its own sign-in page. The studio never sees your passwords; sign-in tokens are stored encrypted on your server and never sent to this browser.
        </p>
      </header>
      {(platforms.isPending || accounts.isPending) && <Spinner label="Loading accounts" />}
      {platforms.error && <ErrorNotice error={platforms.error} onRetry={() => platforms.refetch()} />}
      {accounts.error && <ErrorNotice error={accounts.error} onRetry={() => accounts.refetch()} />}
      {platforms.data && accounts.data && (
        <div className="flex flex-col gap-6 max-w-4xl">
          {SIGN_IN.map((s) => (
            <SignInSection
              key={s.id}
              signIn={s}
              platforms={platforms.data.filter((p) => p.signIn === s.id)}
              accounts={accounts.data
                .filter((a) => platforms.data.find((p) => p.platform === a.platform)?.signIn === s.id)
                .sort((a, b) => PLATFORM_ORDER.indexOf(a.platform) - PLATFORM_ORDER.indexOf(b.platform))}
            />
          ))}
        </div>
      )}
    </>
  )
}

function SignInSection({ signIn, platforms, accounts }: { signIn: (typeof SIGN_IN)[number]; platforms: PlatformInfo[]; accounts: SocialAccount[] }) {
  const connect = useConnect()
  const isAdmin = useAuthStatus().data?.user?.role === 'ADMIN'
  const configured = platforms.every((p) => p.configured)
  const needsReconnect = accounts.some((a) => a.status === 'NEEDS_RECONNECT')
  return (
    <section aria-labelledby={`signin-${signIn.id}`} className="rounded-2xl border border-line bg-surface">
      <div className="p-5 flex flex-wrap items-start gap-4">
        <div className="flex gap-1.5 pt-0.5">
          {platforms.map((p) => (
            <PlatformMark key={p.platform} platform={p.platform} className="size-8 text-xs" />
          ))}
        </div>
        <div className="min-w-0 flex-1">
          <h2 id={`signin-${signIn.id}`} className="text-lg font-semibold">
            {platforms.map((p) => p.name).join(' & ')}
          </h2>
          <p className="text-sm text-muted">Sign in with {signIn.name} to connect your {signIn.connects}.</p>
        </div>
        {!isAdmin ? (
          <span className="text-sm text-faint self-center">An administrator connects channels</span>
        ) : configured ? (
          <Button
            variant={accounts.length === 0 || needsReconnect ? 'primary' : 'secondary'}
            icon={<LogIn className="size-4" />}
            loading={connect.isPending}
            onClick={() => connect.mutate(signIn.id, { onError: (e) => toast.error(e.message) })}
          >
            {accounts.length === 0 ? `Connect with ${signIn.name}` : needsReconnect ? 'Reconnect' : 'Connect another'}
          </Button>
        ) : (
          <span className="text-sm text-faint self-center">Not set up on this server</span>
        )}
      </div>

      {!configured && <SetupHelp platform={platforms[0]} />}

      {accounts.length > 0 && (
        <ul className="border-t border-line divide-y divide-line">
          {accounts.map((a) => (
            <AccountRow key={a.id} account={a} />
          ))}
        </ul>
      )}

      <ul className="border-t border-line px-5 py-3 flex flex-col gap-1 text-xs text-faint">
        {platforms.flatMap((p) => p.limits.map((l) => <li key={p.platform + l}>{platforms.length > 1 ? `${p.name}: ` : ''}{l}</li>))}
      </ul>
    </section>
  )
}

function SetupHelp({ platform }: { platform: PlatformInfo }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="mx-5 mb-5 rounded-xl bg-surface-2/60 p-4 text-sm flex flex-col gap-2">
      <p>{platform.setupHint}</p>
      <p className="text-muted">Register this exact redirect address in the developer console:</p>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded-md bg-surface px-2.5 py-1.5 text-xs border border-line">{platform.redirectUri}</code>
        <Button
          size="sm"
          variant="ghost"
          icon={copied ? <CheckCircle2 className="size-4" /> : <Copy className="size-4" />}
          onClick={() => {
            void navigator.clipboard?.writeText(platform.redirectUri).then(() => setCopied(true))
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
      <p className="text-faint text-xs">Then restart the backend. The README's “Social publishing set-up” section has step-by-step instructions.</p>
    </div>
  )
}

function AccountRow({ account: a }: { account: SocialAccount }) {
  const action = useAccountAction()
  const isAdmin = useAuthStatus().data?.user?.role === 'ADMIN'
  const broken = a.status === 'NEEDS_RECONNECT'
  const initials = a.displayName.replace(/^@/, '').slice(0, 2).toUpperCase()
  return (
    <li className="px-5 py-3.5 flex flex-wrap items-center gap-3">
      <span aria-hidden className={cn('grid place-items-center size-10 rounded-full text-sm font-semibold', broken ? 'bg-rose-signal/12 text-rose-signal' : 'bg-peacock-500/15 text-peacock-600 dark:text-peacock-400')}>
        {initials}
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-medium truncate flex items-center gap-2">
          <PlatformMark platform={a.platform} className="size-5 text-[0.6rem]" />
          {a.profileUrl ? (
            <a href={a.profileUrl} target="_blank" rel="noreferrer noopener" className="truncate hover:underline">{a.displayName}</a>
          ) : (
            <span className="truncate">{a.displayName}</span>
          )}
        </p>
        <p className={cn('text-xs mt-0.5', broken ? 'text-rose-signal' : 'text-faint')}>
          {broken
            ? a.lastError ?? 'Sign-in expired. Reconnect to keep posting.'
            : `Connected ${formatDate(a.connectedAt)}${a.accessExpiresAt && a.platform === 'TIKTOK' ? ` · sign in again before ${formatDate(a.accessExpiresAt)}` : ''}`}
        </p>
      </div>
      {isAdmin && <div className="flex gap-1">
        <Button
          size="sm"
          variant="ghost"
          icon={<RefreshCw className="size-3.5" />}
          loading={action.isPending && action.variables?.action === 'check'}
          onClick={() =>
            action.mutate({ id: a.id, action: 'check' }, {
              onSuccess: () => toast.info(`Checked ${a.displayName}`),
              onError: (e) => toast.error(e.message),
            })
          }
        >
          Check
        </Button>
        <Button
          size="sm"
          variant="ghost"
          icon={<Unplug className="size-3.5" />}
          loading={action.isPending && action.variables?.action === 'disconnect'}
          onClick={() => {
            if (!window.confirm(`Disconnect ${a.displayName}? Its scheduled posts will not go out.`)) return
            action.mutate({ id: a.id, action: 'disconnect' }, { onSuccess: () => toast.success('Disconnected'), onError: (e) => toast.error(e.message) })
          }}
        >
          Disconnect
        </Button>
      </div>}
    </li>
  )
}
