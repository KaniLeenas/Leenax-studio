import { useState, type FormEvent, type ReactNode } from 'react'
import { BrandMark } from '../components/AppShell'
import { Button, Field, Input } from '../components/ui'
import { ApiError } from '../lib/api'
import { useLogin, useMfaLogin, useSetup } from '../lib/queries'
import { useLinkInfo, useRedeemLink } from '../lib/people'

function AuthFrame({ title, intro, children }: { title: string; intro: string; children: ReactNode }) {
  return (
    <div className="min-h-full grid lg:grid-cols-[1fr_1.1fr]">
      <section className="relative hidden lg:flex flex-col justify-between overflow-hidden bg-[#1e2240] text-[#e8eaf6] p-12">
        <div className="flex items-center gap-3">
          <BrandMark className="size-9" />
          <span className="font-display text-xl font-semibold">Leenax Studio</span>
        </div>
        <FilmTimeline />
        <p className="font-display text-[2.6rem] leading-[1.05] font-semibold max-w-md">
          Script, shoot, voice, cut and post. One private studio.
        </p>
      </section>

      <section className="flex items-center justify-center px-5 py-12">
        <div className="w-full max-w-sm">
          <div className="lg:hidden flex items-center gap-2.5 mb-10">
            <BrandMark className="size-8" />
            <span className="font-display text-lg font-semibold">Leenax Studio</span>
          </div>
          <h1 className="text-3xl font-semibold">{title}</h1>
          <p className="text-muted mt-2 mb-8">{intro}</p>
          {children}
        </div>
      </section>
    </div>
  )
}

/** Decorative edit-timeline: three tracks of clips under a ruler with a marigold playhead. */
function FilmTimeline() {
  const tracks = [
    [18, 26, 12, 30],
    [40, 22, 24],
    [10, 34, 8, 20, 14],
  ]
  return (
    <div aria-hidden className="relative my-10 select-none">
      <div className="flex justify-between text-[0.7rem] text-[#a3a8c6]/70 tabular mb-2">
        {['0:00', '0:15', '0:30', '0:45', '1:00'].map((t) => (
          <span key={t}>{t}</span>
        ))}
      </div>
      <div className="flex flex-col gap-2">
        {tracks.map((clips, i) => (
          <div key={i} className="flex gap-1.5 h-9">
            {clips.map((w, j) => (
              <div
                key={j}
                style={{ flexGrow: w }}
                className={
                  i === 0
                    ? 'rounded-md bg-[#2f3a6b]'
                    : i === 1
                      ? 'rounded-md bg-peacock-600/70'
                      : 'rounded-md bg-[#3a3f63]'
                }
              />
            ))}
          </div>
        ))}
      </div>
      <div className="absolute top-5 bottom-[-6px] left-[38%] w-0.5 bg-marigold-500">
        <div className="absolute -top-1.5 -left-[5px] size-3 rotate-45 bg-marigold-500" />
      </div>
    </div>
  )
}

export function LoginPage() {
  const login = useLogin()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    login.mutate({ email, password })
  }

  if (login.data?.mfaRequired) return <CodeStep onRestart={() => { login.reset(); setPassword('') }} />

  return (
    <AuthFrame title="Sign in" intro="Welcome back to your studio.">
      <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
        <Field label="Email">
          {(id) => (
            <Input id={id} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
          )}
        </Field>
        <Field label="Password">
          {(id) => (
            <Input
              id={id}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          )}
        </Field>
        {login.error && (
          <p role="alert" className="text-rose-signal text-sm">
            {login.error.message}
          </p>
        )}
        <Button type="submit" variant="primary" loading={login.isPending} disabled={!email || !password}>
          Sign in
        </Button>
      </form>
    </AuthFrame>
  )
}

/** Second sign-in step: the authenticator app's code, or a recovery code. */
function CodeStep({ onRestart }: { onRestart: () => void }) {
  const mfa = useMfaLogin()
  const [code, setCode] = useState('')
  const [recovery, setRecovery] = useState(false)
  return (
    <AuthFrame title="Enter your code" intro={recovery ? 'Type one of the recovery codes you saved. Each works once.' : 'Open your authenticator app and type the 6-digit code for Leenax Studio.'}>
      <form
        className="flex flex-col gap-5"
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          mfa.mutate(code, { onError: (err) => { if (err instanceof ApiError && err.status === 401 && err.code === 'UNAUTHORIZED') onRestart() } })
        }}
      >
        <Field label={recovery ? 'Recovery code' : '6-digit code'}>
          {(id) => (
            <Input id={id} autoFocus autoComplete="one-time-code" inputMode={recovery ? 'text' : 'numeric'} value={code}
              onChange={(e) => setCode(e.target.value)} className={recovery ? '' : 'tracking-[0.3em] tabular'} maxLength={recovery ? 20 : 7} />
          )}
        </Field>
        {mfa.error && <p role="alert" className="text-rose-signal text-sm">{mfa.error.message}</p>}
        <Button type="submit" variant="primary" loading={mfa.isPending} disabled={!code.trim()}>Sign in</Button>
        <div className="flex justify-between text-sm">
          <button type="button" className="text-muted hover:text-ink" onClick={() => { setRecovery(!recovery); setCode('') }}>
            {recovery ? 'Use the app code' : 'Use a recovery code'}
          </button>
          <button type="button" className="text-muted hover:text-ink" onClick={onRestart}>Start again</button>
        </div>
      </form>
    </AuthFrame>
  )
}

export function SetupPage({ enabled }: { enabled: boolean }) {
  const setup = useSetup()
  const [form, setForm] = useState({ setupToken: '', displayName: '', email: '', password: '', confirm: '' })
  const [localError, setLocalError] = useState<string>()

  const update = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }))

  function submit(e: FormEvent) {
    e.preventDefault()
    if (form.password.length < 12) return setLocalError('Use at least 12 characters for your password.')
    if (form.password !== form.confirm) return setLocalError('The two passwords do not match.')
    setLocalError(undefined)
    setup.mutate({
      setupToken: form.setupToken.trim(),
      displayName: form.displayName,
      email: form.email,
      password: form.password,
    })
  }

  if (!enabled) {
    return (
      <AuthFrame title="Set up your studio" intro="Owner setup is switched off on this server.">
        <div className="rounded-xl border border-line bg-surface p-5 text-muted">
          Add a <code className="text-ink">SETUP_TOKEN</code> value to the server's <code className="text-ink">.env</code> file,
          restart the backend, then reload this page.
        </div>
      </AuthFrame>
    )
  }

  return (
    <AuthFrame
      title="Set up your studio"
      intro="Create the first administrator account. This only happens once; after that, administrators invite the rest of the team."
    >
      <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
        <Field label="Setup token" hint="The SETUP_TOKEN value from the server's .env file.">
          {(id, d) => (
            <Input id={id} aria-describedby={d} type="password" autoComplete="off" value={form.setupToken} onChange={update('setupToken')} />
          )}
        </Field>
        <Field label="Your name">
          {(id) => <Input id={id} autoComplete="name" value={form.displayName} onChange={update('displayName')} />}
        </Field>
        <Field label="Email">
          {(id) => <Input id={id} type="email" autoComplete="username" value={form.email} onChange={update('email')} />}
        </Field>
        <Field label="Password" hint="At least 12 characters. A short sentence works well.">
          {(id, d) => (
            <Input id={id} aria-describedby={d} type="password" autoComplete="new-password" value={form.password} onChange={update('password')} />
          )}
        </Field>
        <Field label="Confirm password">
          {(id) => <Input id={id} type="password" autoComplete="new-password" value={form.confirm} onChange={update('confirm')} />}
        </Field>
        {(localError || setup.error) && (
          <p role="alert" className="text-rose-signal text-sm">
            {localError ?? setup.error?.message}
          </p>
        )}
        <Button
          type="submit"
          variant="primary"
          loading={setup.isPending}
          disabled={!form.setupToken || !form.displayName || !form.email || !form.password}
        >
          Create owner account
        </Button>
      </form>
    </AuthFrame>
  )
}

/**
 * Opened from a one-time link: an invited person chooses their password, or someone
 * resets a forgotten one. No sign-in needed: the link is the secret.
 */
export function LinkPage({ token, onDone }: { token: string | null; onDone: () => void }) {
  const info = useLinkInfo(token)
  const redeem = useRedeemLink()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [localError, setLocalError] = useState<string>()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (password.length < 12) return setLocalError('Use at least 12 characters for your password.')
    if (password !== confirm) return setLocalError('The two passwords do not match.')
    setLocalError(undefined)
    if (token) redeem.mutate({ token, password })
  }

  if (!token || info.error) {
    return (
      <AuthFrame title="This link doesn't work" intro="It has expired or was already used. Links work once.">
        <p className="text-muted mb-6">Ask an administrator for a new link.</p>
        <Button onClick={onDone}>Go to sign-in</Button>
      </AuthFrame>
    )
  }
  if (info.isPending) {
    return (
      <AuthFrame title="Checking your link" intro="One moment.">
        <span className="sr-only" role="status">Checking</span>
      </AuthFrame>
    )
  }
  const activating = info.data.purpose === 'ACTIVATE'
  if (redeem.isSuccess) {
    return (
      <AuthFrame title={activating ? 'Your account is ready' : 'Password changed'} intro={`Sign in as ${info.data.email} with your new password.`}>
        <Button variant="primary" onClick={onDone}>
          Sign in
        </Button>
      </AuthFrame>
    )
  }
  return (
    <AuthFrame
      title={activating ? `Welcome, ${info.data.displayName}` : 'Choose a new password'}
      intro={activating ? `Choose a password to finish setting up ${info.data.email}.` : `For ${info.data.email}.`}
    >
      <form onSubmit={submit} className="flex flex-col gap-5" noValidate>
        <input type="email" name="username" autoComplete="username" value={info.data.email} readOnly hidden />
        <Field label="Password" hint="At least 12 characters. A short sentence works well.">
          {(id, d) => <Input id={id} aria-describedby={d} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
        </Field>
        <Field label="Confirm password">
          {(id) => <Input id={id} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
        </Field>
        {(localError || redeem.error) && (
          <p role="alert" className="text-rose-signal text-sm">
            {localError ?? redeem.error?.message}
          </p>
        )}
        <Button type="submit" variant="primary" loading={redeem.isPending}>
          {activating ? 'Activate account' : 'Save password'}
        </Button>
      </form>
    </AuthFrame>
  )
}
