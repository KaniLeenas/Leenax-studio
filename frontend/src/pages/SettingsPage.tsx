import { Moon, Sun } from 'lucide-react'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ActivityLog, SystemHealth, TwoStepSignIn } from '../components/security'
import { Panel, Tabs, type TabDef } from '../components/studio'
import { Button, Field, Input, PageHeader, Select } from '../components/ui'
import { cn } from '../lib/cn'
import { useAuthStatus, useChangePassword, useUpdateProfile } from '../lib/queries'
import { useRegistrationSettings, useUpdateRegistrationMode, type RegistrationMode } from '../lib/people'
import { toast, useUi } from '../stores/ui'

function Section({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <section className="grid md:grid-cols-[260px_1fr] gap-x-10 gap-y-4 py-8 border-t border-line first:border-t-0 first:pt-0">
      <div>
        <h2 className="text-lg font-semibold">{title}</h2>
        <p className="text-sm text-muted mt-1">{description}</p>
      </div>
      <div className="max-w-md">{children}</div>
    </section>
  )
}

type Tab = 'account' | 'security' | 'system'
const TABS: TabDef<Tab>[] = [
  { id: 'account', label: 'Account' },
  { id: 'security', label: 'Security' },
  { id: 'system', label: 'System' },
]

export function SettingsPage() {
  const [params, setParams] = useSearchParams()
  const isAdmin = useAuthStatus().data?.user?.role === 'ADMIN'
  const tabs = isAdmin ? TABS : TABS.filter((t) => t.id !== 'system')
  const tab = (tabs.find((t) => t.id === params.get('tab'))?.id ?? 'account') as Tab
  return (
    <>
      <PageHeader title="Settings" />
      <Tabs tabs={tabs} value={tab} onChange={(t) => setParams(t === 'account' ? {} : { tab: t }, { replace: true })} label="Settings sections" />
      {tab === 'account' && (
        <Panel id="account">
          <ProfileSection />
          <PasswordSection />
          <AppearanceSection />
        </Panel>
      )}
      {tab === 'security' && (
        <Panel id="security">
          <Section title="Two-step sign-in" description="A code from an authenticator app on your phone, as well as your password.">
            <TwoStepSignIn />
          </Section>
          <section className="py-8 border-t border-line">
            <h2 className="text-lg font-semibold">Recent activity</h2>
            <p className="text-sm text-muted mt-1 mb-4">
              {isAdmin
                ? "Everyone's sign-ins, security changes, people, connections and publishing, with the address each came from."
                : 'Your sign-ins, security changes and publishing, with the address each came from.'}
            </p>
            <div className="max-w-3xl"><ActivityLog /></div>
          </section>
        </Panel>
      )}
      {tab === 'system' && (
        <Panel id="system" className="max-w-3xl">
          <RegistrationPolicy />
          <SystemHealth />
        </Panel>
      )}
    </>
  )
}

function RegistrationPolicy() {
  const settings = useRegistrationSettings()
  const update = useUpdateRegistrationMode()
  const [mode, setMode] = useState<RegistrationMode>('INVITATION_ONLY')
  useEffect(() => { if (settings.data) setMode(settings.data.mode) }, [settings.data])
  return (
    <Section title="Registration" description="Choose who can create an account on this studio.">
      <div className="flex flex-col gap-3">
        <Field label="Registration mode" hint={settings.data?.emailConfigured ? 'Public accounts must verify their email before they can sign in.' : 'Invitation-only is available. Configure outbound email before enabling public registration.'}>
          {(id) => (
            <Select id={id} value={mode} disabled={settings.isPending || update.isPending} onChange={(e) => setMode(e.target.value as RegistrationMode)}>
              <option value="INVITATION_ONLY">Invitation only</option>
              <option value="PUBLIC" disabled={!settings.data?.emailConfigured}>Public, with email verification</option>
            </Select>
          )}
        </Field>
        {settings.error && <p role="alert" className="text-sm text-rose-signal">Couldn't load registration settings.</p>}
        <div><Button variant="primary" disabled={!settings.data || mode === settings.data.mode || (mode === 'PUBLIC' && !settings.data.emailConfigured)} loading={update.isPending}
          onClick={() => update.mutate(mode, { onError: (e) => toast.error(e.message), onSuccess: () => toast.success('Registration settings saved') })}>Save registration mode</Button></div>
      </div>
    </Section>
  )
}

function ProfileSection() {
  const { data } = useAuthStatus()
  const [name, setName] = useState('')
  const update = useUpdateProfile()
  useEffect(() => setName(data?.user?.displayName ?? ''), [data?.user?.displayName])

  function submit(e: FormEvent) {
    e.preventDefault()
    update.mutate({ displayName: name.trim() }, { onSuccess: () => toast.success('Profile saved'), onError: (err) => toast.error(err.message) })
  }

  return (
    <Section title="Profile" description="How the studio greets you.">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Name">{(id) => <Input id={id} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Email" hint="Your sign-in. Changing it is not supported yet.">
          {(id, d) => <Input id={id} aria-describedby={d} value={data?.user?.email ?? ''} disabled />}
        </Field>
        <div>
          <Button type="submit" variant="primary" loading={update.isPending} disabled={!name.trim() || name.trim() === data?.user?.displayName}>
            Save profile
          </Button>
        </div>
      </form>
    </Section>
  )
}

function PasswordSection() {
  const [form, setForm] = useState({ current: '', next: '', confirm: '' })
  const [error, setError] = useState<string>()
  const change = useChangePassword()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (form.next.length < 12) return setError('Use at least 12 characters.')
    if (form.next !== form.confirm) return setError('The new passwords do not match.')
    setError(undefined)
    change.mutate(
      { currentPassword: form.current, newPassword: form.next },
      {
        onSuccess: () => {
          toast.success('Password changed')
          setForm({ current: '', next: '', confirm: '' })
        },
        onError: (err) => setError(err.message),
      },
    )
  }

  return (
    <Section title="Password" description="At least 12 characters. Failed sign-ins are locked out for 15 minutes after 5 tries.">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Current password">
          {(id) => <Input id={id} type="password" autoComplete="current-password" value={form.current} onChange={(e) => setForm({ ...form, current: e.target.value })} />}
        </Field>
        <Field label="New password">
          {(id) => <Input id={id} type="password" autoComplete="new-password" value={form.next} onChange={(e) => setForm({ ...form, next: e.target.value })} />}
        </Field>
        <Field label="Confirm new password">
          {(id) => <Input id={id} type="password" autoComplete="new-password" value={form.confirm} onChange={(e) => setForm({ ...form, confirm: e.target.value })} />}
        </Field>
        {error && (
          <p role="alert" className="text-sm text-rose-signal">
            {error}
          </p>
        )}
        <div>
          <Button type="submit" variant="primary" loading={change.isPending} disabled={!form.current || !form.next || !form.confirm}>
            Change password
          </Button>
        </div>
      </form>
    </Section>
  )
}

function AppearanceSection() {
  const { theme, setTheme } = useUi()
  const options = [
    { value: 'light' as const, label: 'Light', icon: Sun },
    { value: 'dark' as const, label: 'Dark', icon: Moon },
  ]
  return (
    <Section title="Appearance" description="Saved on this device only.">
      <div role="radiogroup" aria-label="Theme" className="grid grid-cols-2 gap-3">
        {options.map((o) => (
          <button
            key={o.value}
            role="radio"
            aria-checked={theme === o.value}
            onClick={() => setTheme(o.value)}
            className={cn(
              'flex items-center gap-3 rounded-xl border px-4 h-14',
              theme === o.value ? 'border-marigold-500 ring-2 ring-marigold-500/25' : 'border-line hover:bg-surface-2',
            )}
          >
            <o.icon className="size-5" aria-hidden />
            {o.label}
          </button>
        ))}
      </div>
    </Section>
  )
}
