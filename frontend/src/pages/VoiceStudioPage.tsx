import { Play, Plus, Trash2, Wand2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { MicRecorder } from '../components/MicRecorder'
import {
  AccountSelect,
  Card,
  ConnectAccount,
  MediaField,
  Panel,
  ResultCard,
  Slider,
  Tabs,
  useDefaultAccount,
  useUsableAccounts,
} from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { useModels } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { useProjects } from '../lib/queries'
import {
  previewVoice,
  useAudioTool,
  useCloneVoice,
  useConsentStatement,
  useDeleteProfile,
  useDeleteRule,
  useDialogue,
  useNarrate,
  useRules,
  useSaveProfile,
  useSaveRule,
  useVoiceProfiles,
  useVoices,
} from '../lib/studioQueries'
import { EMOTIONS, detectSpeakers, insertAtCursor, scriptStats } from '../lib/voice'
import type { AudioOperation, Language, MediaAsset, PronunciationRule, SpeechResult, VoiceChoice, VoiceProfile, VoiceStyle } from '../lib/types'
import { useAuthStatus } from '../lib/queries'
import { toast } from '../stores/ui'

type TabId = 'narration' | 'dialogue' | 'voices' | 'sfx' | 'tools' | 'pronunciation'

const TABS: { id: TabId; label: string }[] = [
  { id: 'narration', label: 'Narration' },
  { id: 'dialogue', label: 'Dialogue' },
  { id: 'voices', label: 'My voices' },
  { id: 'sfx', label: 'Sound effects' },
  { id: 'tools', label: 'Audio tools' },
  { id: 'pronunciation', label: 'Pronunciation' },
]

export function VoiceStudioPage() {
  const [tab, setTab] = useState<TabId>('narration')
  return (
    <>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">Voice & audio</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Tamil and English voice-overs, your own cloned voice, sound effects and clean-up. Everything you make is saved to the media library as MP3.
        </p>
      </header>
      <Tabs tabs={TABS} value={tab} onChange={setTab} label="Voice and audio tools" />
      <Panel id={tab}>
        {tab === 'narration' && <NarrationTab />}
        {tab === 'dialogue' && <DialogueTab onManageVoices={() => setTab('voices')} />}
        {tab === 'voices' && <VoicesTab />}
        {tab === 'sfx' && <SoundEffectsTab />}
        {tab === 'tools' && <AudioToolsTab />}
        {tab === 'pronunciation' && <PronunciationTab />}
      </Panel>
    </>
  )
}

// ---- shared voice picker ------------------------------------------------------

interface VoicePick {
  source: 'profile' | 'provider'
  profileId: string
  accountId: string
  voiceId: string
  model: string
}

function useVoicePicker(initial?: Partial<VoicePick>) {
  const [pick, setPick] = useState<VoicePick>({ source: 'profile', profileId: '', accountId: '', voiceId: '', model: '', ...initial })
  const profiles = useVoiceProfiles()
  const voiceAccounts = useUsableAccounts('VOICE')
  const setAccount = useCallback((id: string) => setPick((p) => ({ ...p, accountId: id, voiceId: '', model: '' })), [])
  useDefaultAccount(voiceAccounts.usable, pick.accountId, setAccount)

  // No saved voices yet → start on provider voices.
  useEffect(() => {
    if (profiles.data && profiles.data.length === 0) setPick((p) => (p.source === 'profile' ? { ...p, source: 'provider' } : p))
    if (profiles.data && profiles.data.length > 0 && !pick.profileId) setPick((p) => ({ ...p, profileId: profiles.data[0].id }))
  }, [profiles.data, pick.profileId])

  const choice: VoiceChoice | undefined =
    pick.source === 'profile'
      ? pick.profileId
        ? { profileId: pick.profileId, model: pick.model || undefined }
        : undefined
      : pick.accountId && pick.voiceId
        ? { accountId: pick.accountId, voiceId: pick.voiceId, model: pick.model || undefined }
        : undefined
  const profile = profiles.data?.find((p) => p.id === pick.profileId)
  const accountId = pick.source === 'profile' ? profile?.accountId : pick.accountId
  const provider = voiceAccounts.usable.find((a) => a.id === accountId)?.provider ?? profile?.provider
  return { pick, setPick, setAccount, profiles, voiceAccounts, choice, profile, provider }
}

function VoicePicker({ state, language }: { state: ReturnType<typeof useVoicePicker>; language: Language }) {
  const { pick, setPick, setAccount, profiles, voiceAccounts, provider } = state
  const voices = useVoices(pick.source === 'provider' ? pick.accountId : undefined)
  const models = useModels()
  const voiceModels = (models.data ?? []).filter((m) => m.capability === 'VOICE' && m.enabled && m.provider === provider)
  const shownVoices = useMemo(() => {
    const list = voices.data ?? []
    // Show clones first, then voices matching the language when the provider says so.
    return [...list].sort((a, b) => Number(b.cloned) - Number(a.cloned) || a.name.localeCompare(b.name))
  }, [voices.data])
  const selectedVoice = voices.data?.find((v) => v.id === pick.voiceId)

  useEffect(() => {
    if (pick.source === 'provider' && !pick.voiceId && shownVoices.length > 0) setPick((p) => ({ ...p, voiceId: shownVoices[0].id }))
  }, [shownVoices, pick.source, pick.voiceId, setPick])

  return (
    <div className="flex flex-col gap-4">
      <div role="radiogroup" aria-label="Voice source" className="grid grid-cols-2 gap-1 rounded-xl bg-surface-2 p-1">
        {(['profile', 'provider'] as const).map((s) => (
          <button
            key={s}
            type="button"
            role="radio"
            aria-checked={pick.source === s}
            onClick={() => setPick((p) => ({ ...p, source: s }))}
            className={cn('rounded-lg py-2 text-sm font-medium', pick.source === s ? 'bg-surface shadow-sm text-ink' : 'text-muted hover:text-ink')}
          >
            {s === 'profile' ? 'My voices' : 'Provider voices'}
          </button>
        ))}
      </div>

      {pick.source === 'profile' &&
        (profiles.isPending ? (
          <Spinner label="Loading voices" />
        ) : profiles.data && profiles.data.length > 0 ? (
          <Field label="Voice">
            {(id) => (
              <Select id={id} value={pick.profileId} onChange={(e) => setPick((p) => ({ ...p, profileId: e.target.value }))}>
                {profiles.data.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} {p.kind === 'CLONED' ? '(your voice)' : ''} · {p.accountName ?? p.provider}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : (
          <p className="text-sm text-muted">No saved voices yet. Pick a provider voice, then save it as one of your voices.</p>
        ))}

      {pick.source === 'provider' && (
        <>
          <Field label="Account">{(id) => <AccountSelect id={id} accounts={voiceAccounts.usable} value={pick.accountId} onChange={setAccount} />}</Field>
          {voices.isPending && pick.accountId && <Spinner label="Loading voices" />}
          {voices.error && <ErrorNotice error={voices.error} onRetry={() => voices.refetch()} />}
          {voices.data && (
            <Field
              label="Voice"
              hint={
                selectedVoice
                  ? [selectedVoice.gender, selectedVoice.accent, selectedVoice.description].filter(Boolean).join(' · ') ||
                    (language === 'TA' ? 'Every voice can speak Tamil.' : undefined)
                  : undefined
              }
            >
              {(id, d) => (
                <Select id={id} aria-describedby={d} value={pick.voiceId} onChange={(e) => setPick((p) => ({ ...p, voiceId: e.target.value }))}>
                  {shownVoices.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.name}
                      {v.cloned ? ' (cloned)' : ''}
                      {v.gender ? ` · ${v.gender}` : ''}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          )}
          {selectedVoice?.previewUrl && <audio controls preload="none" src={selectedVoice.previewUrl} className="w-full" aria-label="Provider sample" />}
        </>
      )}

      {voiceModels.length > 0 && (
        <Field label="Voice model" hint={voiceModels.find((m) => m.modelCode === pick.model)?.notes}>
          {(id, d) => (
            <Select id={id} aria-describedby={d} value={pick.model} onChange={(e) => setPick((p) => ({ ...p, model: e.target.value }))}>
              <option value="">{pick.source === 'profile' ? 'Voice’s saved model' : 'Provider default'}</option>
              {voiceModels.map((m) => (
                <option key={m.id} value={m.modelCode}>
                  {m.displayName}
                </option>
              ))}
            </Select>
          )}
        </Field>
      )}
    </div>
  )
}

function StyleControls({ style, onChange, promptSteered }: { style: VoiceStyle; onChange: (s: VoiceStyle) => void; promptSteered: boolean }) {
  return (
    <div className="flex flex-col gap-4">
      <fieldset>
        <legend className="text-sm font-medium mb-2">Delivery</legend>
        <div className="flex flex-wrap gap-1.5">
          {EMOTIONS.map((e) => (
            <button
              key={e.id}
              type="button"
              aria-pressed={(style.emotion ?? 'NEUTRAL') === e.id}
              onClick={() => onChange({ ...style, emotion: e.id })}
              className={cn(
                'rounded-full border px-3 py-1 text-sm',
                (style.emotion ?? 'NEUTRAL') === e.id ? 'border-marigold-500 bg-marigold-500/15 text-ink' : 'border-line text-muted hover:text-ink',
              )}
            >
              {e.label}
            </button>
          ))}
        </div>
      </fieldset>
      {promptSteered && (
        <Field label="Extra direction" hint="Optional. For example: “warm Chennai Tamil accent, slow and calm”.">
          {(id, d) => (
            <Input id={id} aria-describedby={d} maxLength={300} value={style.direction ?? ''} onChange={(e) => onChange({ ...style, direction: e.target.value })} />
          )}
        </Field>
      )}
      <div className="grid sm:grid-cols-2 gap-4">
        <Slider label="Speed" min={0.5} max={2} step={0.05} value={style.speed ?? 1} onChange={(v) => onChange({ ...style, speed: v })} format={(v) => `${v.toFixed(2)}×`} />
        <Slider
          label="Pitch"
          min={-6}
          max={6}
          step={0.5}
          value={style.pitch ?? 0}
          onChange={(v) => onChange({ ...style, pitch: v })}
          format={(v) => (v === 0 ? 'Natural' : `${v > 0 ? '+' : ''}${v} semitones`)}
        />
      </div>
    </div>
  )
}

function usePreviewPlayer() {
  const [url, setUrl] = useState<string>()
  const [loading, setLoading] = useState(false)
  const audio = useRef<HTMLAudioElement>(null)
  useEffect(() => () => void (url && URL.revokeObjectURL(url)), [url])
  async function play(body: Parameters<typeof previewVoice>[0]) {
    setLoading(true)
    try {
      const next = await previewVoice(body)
      setUrl(next)
      requestAnimationFrame(() => audio.current?.play().catch(() => {}))
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Preview failed')
    } finally {
      setLoading(false)
    }
  }
  const player = url ? <audio ref={audio} controls src={url} className="w-full" aria-label="Preview" /> : null
  return { play, loading, player }
}

function ProjectSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const projects = useProjects({ page: 0 })
  return (
    <Field label="Project" hint="Optional. The file is also added to this project.">
      {(id, d) => (
        <Select id={id} aria-describedby={d} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">No project</option>
          {(projects.data?.items ?? []).map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </Select>
      )}
    </Field>
  )
}

// ---- narration ------------------------------------------------------------------

function NarrationTab() {
  const voice = useVoicePicker()
  const [language, setLanguage] = useState<Language>('TA')
  const [text, setText] = useState('')
  const [title, setTitle] = useState('')
  const [projectId, setProjectId] = useState('')
  const [style, setStyle] = useState<VoiceStyle>({ emotion: 'NEUTRAL', speed: 1, pitch: 0 })
  const [result, setResult] = useState<SpeechResult>()
  const [saving, setSaving] = useState(false)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const narrate = useNarrate()
  const preview = usePreviewPlayer()

  // A saved voice brings its own style.
  useEffect(() => {
    if (voice.pick.source === 'profile' && voice.profile) setStyle({ emotion: 'NEUTRAL', speed: 1, pitch: 0, ...voice.profile.settings })
    if (voice.profile?.language) setLanguage(voice.profile.language)
  }, [voice.profile, voice.pick.source])

  const stats = scriptStats(text)
  const promptSteered = voice.provider === 'GOOGLE_GEMINI'

  if (voice.voiceAccounts.isPending || voice.profiles.isPending) return <Spinner label="Loading your voices" />
  if (voice.voiceAccounts.usable.length === 0 && (voice.profiles.data?.length ?? 0) === 0) {
    return <ConnectAccount title="Connect a voice account first" body="Add an ElevenLabs or Google Gemini API key. Voice-overs are billed by that provider to your own account." />
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!voice.choice || !text.trim()) return
    narrate.mutate(
      { voice: voice.choice, text, language, style, title: title.trim() || undefined, projectId: projectId || undefined },
      {
        onSuccess: (r) => {
          setResult(r)
          toast.success('Voice-over saved to your media library')
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit} className="grid xl:grid-cols-[minmax(0,24rem)_minmax(0,1fr)] gap-8 items-start">
      <Card label="Voice">
        <VoicePicker state={voice} language={language} />
        <Field label="Language">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
              <option value="TA">Tamil</option>
              <option value="EN">English</option>
            </Select>
          )}
        </Field>
        <StyleControls style={style} onChange={setStyle} promptSteered={promptSteered} />
        {voice.pick.source === 'provider' && voice.choice && (
          <Button type="button" variant="ghost" icon={<Plus className="size-4" />} onClick={() => setSaving(true)}>
            Save as one of my voices
          </Button>
        )}
      </Card>

      <div className="flex flex-col gap-5">
        <Card>
          <Field label="Script" hint={`${stats.characters.toLocaleString()} characters · about ${stats.minutes} min. Type [pause 1s] for a pause. Paragraphs are read with natural breaks.`}>
            {(id, d) => (
              <Textarea
                ref={textarea}
                id={id}
                aria-describedby={d}
                rows={12}
                maxLength={50_000}
                value={text}
                onChange={(e) => setText(e.target.value)}
                lang={language === 'TA' ? 'ta' : 'en'}
                className="text-[1.05rem] leading-relaxed"
                placeholder={language === 'TA' ? 'வணக்கம் நண்பர்களே! இன்று நாம்…' : 'Hello everyone, welcome back to the channel…'}
              />
            )}
          </Field>
          <div className="flex flex-wrap gap-2 -mt-2">
            {[0.5, 1, 2].map((s) => (
              <Button
                key={s}
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => setText((t) => insertAtCursor(textarea.current, t, ` [pause ${s}s] `))}
              >
                + {s}s pause
              </Button>
            ))}
          </div>
          <div className="grid sm:grid-cols-2 gap-4">
            <Field label="File name" hint="Optional. Defaults to the first words of the script.">
              {(id, d) => <Input id={id} aria-describedby={d} maxLength={160} value={title} onChange={(e) => setTitle(e.target.value)} />}
            </Field>
            <ProjectSelect value={projectId} onChange={setProjectId} />
          </div>
          <div className="flex flex-wrap gap-2 items-center">
            <Button
              type="button"
              disabled={!voice.choice || !text.trim()}
              loading={preview.loading}
              icon={<Play className="size-4" />}
              onClick={() => voice.choice && preview.play({ voice: voice.choice, text: stats.previewText, language, style })}
            >
              Preview first lines
            </Button>
            <Button type="submit" variant="primary" disabled={!voice.choice || !text.trim()} loading={narrate.isPending} icon={<Wand2 className="size-4" />}>
              Make voice-over
            </Button>
            {narrate.isPending && <span className="text-sm text-muted" role="status">Long scripts are made in parts and joined. This can take a minute.</span>}
          </div>
          {preview.player}
        </Card>
        {narrate.error && <ErrorNotice error={narrate.error} />}
        {result && <ResultCard media={result.media} note={`${result.characters.toLocaleString()} characters${result.chunks > 1 ? ` in ${result.chunks} parts` : ''}`} />}
      </div>

      {saving && voice.choice && (
        <SaveProfileDialog
          initial={{ accountId: voice.pick.accountId, voiceId: voice.pick.voiceId, model: voice.pick.model, language, style }}
          onClose={() => setSaving(false)}
        />
      )}
    </form>
  )
}

function SaveProfileDialog({
  initial,
  profile,
  onClose,
}: {
  initial?: { accountId: string; voiceId: string; model: string; language: Language; style: VoiceStyle }
  profile?: VoiceProfile
  onClose: () => void
}) {
  const save = useSaveProfile()
  const [name, setName] = useState(profile?.name ?? '')
  const [language, setLanguage] = useState<Language>(profile?.language ?? initial?.language ?? 'TA')
  const [style, setStyle] = useState<VoiceStyle>(profile?.settings ?? initial?.style ?? {})
  const promptSteered = (profile?.provider ?? '') === 'GOOGLE_GEMINI'

  function submit() {
    if (!name.trim()) return
    save.mutate(
      profile
        ? { id: profile.id, name: name.trim(), language, style }
        : { accountId: initial!.accountId, voiceId: initial!.voiceId, model: initial!.model || undefined, name: name.trim(), language, style },
      {
        onSuccess: () => {
          toast.success(profile ? 'Voice updated' : 'Saved to My voices')
          onClose()
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  return (
    <Dialog
      open
      title={profile ? `Edit “${profile.name}”` : 'Save voice'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={save.isPending} disabled={!name.trim()}>
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Name" hint="For example: “Documentary Tamil (male)”.">
          {(id, d) => <Input id={id} aria-describedby={d} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} autoFocus />}
        </Field>
        <Field label="Usual language">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
              <option value="TA">Tamil</option>
              <option value="EN">English</option>
            </Select>
          )}
        </Field>
        <StyleControls style={style} onChange={setStyle} promptSteered={promptSteered} />
      </div>
    </Dialog>
  )
}

// ---- dialogue ------------------------------------------------------------------

const SAMPLE_DIALOGUE = `Kani: வணக்கம்! இன்னைக்கு நம்ம ஊர் சமையல் பத்தி பேசலாம்.
Guest: Thanks for having me. I've been waiting to try this recipe.
Kani: Let's start with the spices.`

function DialogueTab({ onManageVoices }: { onManageVoices: () => void }) {
  const profiles = useVoiceProfiles()
  const dialogue = useDialogue()
  const [script, setScript] = useState('')
  const [language, setLanguage] = useState<Language>('TA')
  const [gap, setGap] = useState(0.35)
  const [title, setTitle] = useState('')
  const [projectId, setProjectId] = useState('')
  const [assign, setAssign] = useState<Record<string, string>>({})
  const [result, setResult] = useState<SpeechResult>()
  const speakers = detectSpeakers(script)

  useEffect(() => {
    if (!profiles.data?.length) return
    setAssign((a) => {
      const next = { ...a }
      speakers.forEach((s, i) => {
        if (!next[s] || !profiles.data.some((p) => p.id === next[s])) next[s] = profiles.data[i % profiles.data.length].id
      })
      return next
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [speakers.join('|'), profiles.data])

  if (profiles.isPending) return <Spinner label="Loading your voices" />
  if (profiles.error) return <ErrorNotice error={profiles.error} onRetry={() => profiles.refetch()} />
  if (profiles.data.length === 0) {
    return (
      <EmptyState
        title="Save a voice first"
        body="Dialogues use voices from My voices, one per speaker. Save a provider voice or clone your own."
        action={<Button variant="primary" onClick={onManageVoices}>Go to My voices</Button>}
      />
    )
  }

  const ready = speakers.length > 0 && speakers.length <= 6 && speakers.every((s) => assign[s])

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!ready) return
    dialogue.mutate(
      {
        script,
        speakers: speakers.map((s) => ({ name: s, profileId: assign[s] })),
        language,
        gapSeconds: gap,
        title: title.trim() || undefined,
        projectId: projectId || undefined,
      },
      {
        onSuccess: (r) => {
          setResult(r)
          toast.success('Dialogue saved to your media library')
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit} className="grid xl:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] gap-8 items-start">
      <Card>
        <Field label="Script" hint="One turn per line: “Name: what they say”. A line without a name continues the previous speaker.">
          {(id, d) => (
            <Textarea
              id={id}
              aria-describedby={d}
              rows={12}
              maxLength={30_000}
              value={script}
              onChange={(e) => setScript(e.target.value)}
              className="leading-relaxed"
              placeholder={SAMPLE_DIALOGUE}
            />
          )}
        </Field>
        {!script && (
          <Button type="button" size="sm" variant="ghost" className="self-start -mt-2" onClick={() => setScript(SAMPLE_DIALOGUE)}>
            Use the example
          </Button>
        )}
        <div className="grid sm:grid-cols-2 gap-4">
          <Field label="File name">{(id) => <Input id={id} maxLength={160} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
          <ProjectSelect value={projectId} onChange={setProjectId} />
        </div>
      </Card>
      <Card label="Speakers">
        <h2 className="font-semibold">Speakers</h2>
        {speakers.length === 0 && <p className="text-sm text-muted">Speakers appear here as you write the script.</p>}
        {speakers.length > 6 && <p className="text-sm text-rose-signal">Use at most 6 speakers.</p>}
        {speakers.map((s) => (
          <Field key={s} label={s}>
            {(id) => (
              <Select id={id} value={assign[s] ?? ''} onChange={(e) => setAssign((a) => ({ ...a, [s]: e.target.value }))}>
                {profiles.data.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ))}
        <Field label="Language">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
              <option value="TA">Tamil</option>
              <option value="EN">English</option>
            </Select>
          )}
        </Field>
        <Slider label="Gap between turns" min={0} max={1.5} step={0.05} value={gap} onChange={setGap} format={(v) => `${v.toFixed(2)} s`} />
        <Button type="submit" variant="primary" disabled={!ready} loading={dialogue.isPending} icon={<Wand2 className="size-4" />}>
          Make dialogue
        </Button>
        {dialogue.error && <ErrorNotice error={dialogue.error} />}
      </Card>
      {result && (
        <div className="xl:col-span-2">
          <ResultCard media={result.media} note={`${detectSpeakers(script).length} speakers`} />
        </div>
      )}
    </form>
  )
}

// ---- my voices & cloning -----------------------------------------------------------

function VoicesTab() {
  const profiles = useVoiceProfiles()
  const del = useDeleteProfile()
  const share = useSaveProfile()
  const me = useAuthStatus().data?.user
  const [editing, setEditing] = useState<VoiceProfile>()
  const [deleting, setDeleting] = useState<VoiceProfile>()
  // Older voices (before accounts) have no creator recorded: they belong to whoever sees them.
  const mine = (p: VoiceProfile) => !p.createdBy || p.createdBy === me?.id || me?.role === 'ADMIN'

  return (
    <div className="grid xl:grid-cols-[minmax(0,1fr)_minmax(0,28rem)] gap-8 items-start">
      <section aria-labelledby="my-voices-heading">
        <h2 id="my-voices-heading" className="text-lg font-semibold mb-3">
          My voices
        </h2>
        {profiles.isPending && <Spinner label="Loading voices" />}
        {profiles.error && <ErrorNotice error={profiles.error} onRetry={() => profiles.refetch()} />}
        {profiles.data?.length === 0 && (
          <EmptyState title="No saved voices yet" body="In Narration, pick a provider voice and choose “Save as one of my voices”, or clone your own voice here." />
        )}
        {profiles.data && profiles.data.length > 0 && (
          <ul className="rounded-2xl border border-line bg-surface divide-y divide-[var(--line)]">
            {profiles.data.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <p className="font-medium flex items-center gap-2">
                    {p.name}
                    {p.kind === 'CLONED' && <StatusPill tone="done">{mine(p) ? 'Your voice' : 'Cloned voice'}</StatusPill>}
                    {p.shared && <StatusPill tone="neutral">Shared with the team</StatusPill>}
                  </p>
                  <p className="text-sm text-muted">
                    {p.accountName ?? p.provider} · {p.language === 'EN' ? 'English' : p.language === 'TA' ? 'Tamil' : 'Any language'} ·{' '}
                    {EMOTIONS.find((e) => e.id === (p.settings.emotion ?? 'NEUTRAL'))?.label}
                    {p.settings.speed && p.settings.speed !== 1 ? ` · ${p.settings.speed}×` : ''}
                  </p>
                  {p.consent && (
                    <p className="text-xs text-faint mt-0.5">Consent recorded {new Date(p.consent.confirmedAt).toLocaleDateString()}</p>
                  )}
                </div>
                {mine(p) ? (
                  <>
                    <label className="flex items-center gap-2 text-sm mr-2">
                      <input
                        type="checkbox"
                        className="accent-marigold-500"
                        checked={!!p.shared}
                        disabled={share.isPending}
                        onChange={(e) =>
                          share.mutate(
                            { id: p.id, shared: e.target.checked },
                            { onSuccess: (v) => toast.success(v.shared ? `${v.name} is shared with the team` : `${v.name} is just yours now`), onError: (err) => toast.error(err.message) },
                          )
                        }
                      />
                      Share with the team
                    </label>
                    <Button size="sm" variant="ghost" onClick={() => setEditing(p)}>
                      Edit
                    </Button>
                    <Button size="sm" variant="ghost" aria-label={`Delete ${p.name}`} onClick={() => setDeleting(p)} icon={<Trash2 className="size-4" />} />
                  </>
                ) : (
                  <span className="text-sm text-faint">Shared by a teammate</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <CloneVoiceCard />

      {editing && <SaveProfileDialog profile={editing} onClose={() => setEditing(undefined)} />}
      {deleting && (
        <Dialog
          open
          title={`Delete “${deleting.name}”?`}
          onClose={() => setDeleting(undefined)}
          footer={
            <>
              <Button onClick={() => setDeleting(undefined)}>Cancel</Button>
              <Button
                variant="danger"
                loading={del.isPending}
                onClick={() =>
                  del.mutate(deleting.id, {
                    onSuccess: () => {
                      toast.success('Voice deleted')
                      setDeleting(undefined)
                    },
                    onError: (e) => toast.error(e.message),
                  })
                }
              >
                Delete
              </Button>
            </>
          }
        >
          <p className="text-muted">
            {deleting.kind === 'CLONED'
              ? 'Your cloned voice is also deleted from your ElevenLabs account. Files you already made stay in the media library.'
              : 'This only removes it from My voices. Files you already made stay in the media library.'}
          </p>
        </Dialog>
      )}
    </div>
  )
}

function CloneVoiceCard() {
  const accounts = useUsableAccounts('VOICE')
  const eleven = accounts.usable.filter((a) => a.provider === 'ELEVENLABS')
  const consent = useConsentStatement()
  const clone = useCloneVoice()
  const [accountId, setAccountId] = useState('')
  const [name, setName] = useState('My voice')
  const [language, setLanguage] = useState<Language>('TA')
  const [samples, setSamples] = useState<MediaAsset[]>([])
  const [own, setOwn] = useState(false)
  const [typed, setTyped] = useState('')
  const [denoise, setDenoise] = useState(true)
  useDefaultAccount(eleven, accountId, setAccountId)

  const totalSeconds = samples.reduce((s, m) => s + (m.durationMs ?? 0) / 1000, 0)
  const typedOk = !!consent.data && typed.trim().replace(/\s+/g, ' ').toLowerCase() === consent.data.statement.toLowerCase()
  const ready = !!accountId && name.trim() && samples.length > 0 && own && typedOk

  function addSample(m?: MediaAsset) {
    if (m && !samples.some((s) => s.id === m.id)) setSamples((s) => [...s, m].slice(0, 25))
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!ready || !consent.data) return
    clone.mutate(
      {
        accountId,
        name: name.trim(),
        language,
        sampleMediaIds: samples.map((s) => s.id),
        consentOwnVoice: own,
        consentStatement: typed,
        removeBackgroundNoise: denoise,
      },
      {
        onSuccess: () => {
          toast.success('Your voice is ready in My voices')
          setSamples([])
          setTyped('')
          setOwn(false)
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit} aria-labelledby="clone-heading">
      <Card>
        <div>
          <h2 id="clone-heading" className="text-lg font-semibold">
            Clone your own voice
          </h2>
          <p className="text-sm text-muted mt-1">
            Instant voice cloning with ElevenLabs. Only clone your own voice. The clone stays private to your ElevenLabs account.
          </p>
        </div>
        {accounts.isPending ? (
          <Spinner label="Loading accounts" />
        ) : eleven.length === 0 ? (
          <p className="text-sm text-muted">Add an ElevenLabs account under AI accounts & keys to clone your voice.</p>
        ) : (
          <>
            <Field label="ElevenLabs account">{(id) => <AccountSelect id={id} accounts={eleven} value={accountId} onChange={setAccountId} />}</Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Voice name">{(id) => <Input id={id} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
              <Field label="Main language">
                {(id) => (
                  <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
                    <option value="TA">Tamil</option>
                    <option value="EN">English</option>
                  </Select>
                )}
              </Field>
            </div>

            <div className="flex flex-col gap-2">
              <span className="text-sm font-medium">Recordings of your voice</span>
              <p className="text-xs text-faint">
                1–3 minutes of clear speech in a quiet room works best. Avoid music and other voices. Total so far: {Math.round(totalSeconds)} s.
              </p>
              {samples.length > 0 && (
                <ul className="flex flex-col gap-1">
                  {samples.map((s) => (
                    <li key={s.id} className="flex items-center gap-2 text-sm rounded-lg bg-surface-2/60 px-3 py-1.5">
                      <span className="truncate flex-1">{s.filename}</span>
                      <button
                        type="button"
                        aria-label={`Remove ${s.filename}`}
                        onClick={() => setSamples((x) => x.filter((y) => y.id !== s.id))}
                        className="text-faint hover:text-ink"
                      >
                        <Trash2 className="size-4" />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <div className="flex flex-wrap gap-2">
                <MicRecorder onSaved={addSample} label="Record a sample" />
                <MediaField label="" kind="AUDIO" value={undefined} onChange={addSample} pickerTitle="Pick a recording of your voice" />
              </div>
            </div>

            <label className="flex items-start gap-2 text-sm">
              <input type="checkbox" checked={denoise} onChange={(e) => setDenoise(e.target.checked)} className="mt-1 accent-marigold-500" />
              Remove background noise from the recordings first
            </label>

            <fieldset className="rounded-xl border border-marigold-500/40 bg-marigold-500/6 p-4 flex flex-col gap-3">
              <legend className="px-1 text-sm font-semibold">Consent</legend>
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" checked={own} onChange={(e) => setOwn(e.target.checked)} className="mt-1 accent-marigold-500" />
                These recordings are of my own voice.
              </label>
              <Field label="Type this sentence to confirm" hint={consent.data?.statement}>
                {(id, d) => (
                  <Textarea id={id} aria-describedby={d} rows={2} value={typed} onChange={(e) => setTyped(e.target.value)} className="min-h-0" />
                )}
              </Field>
              <p className="text-xs text-faint">Your confirmation, the time and fingerprints of the recordings are stored with the voice.</p>
            </fieldset>

            <Button type="submit" variant="primary" disabled={!ready} loading={clone.isPending}>
              Create my voice
            </Button>
            {clone.error && <ErrorNotice error={clone.error} />}
          </>
        )}
      </Card>
    </form>
  )
}

// ---- sound effects ---------------------------------------------------------------

const SFX_IDEAS = ['Temple bell ringing in the distance', 'Crowd cheering in a cricket stadium', 'Rain on a tin roof', 'Whoosh transition', 'Sizzling dosa on a hot tawa']

function SoundEffectsTab() {
  const accounts = useUsableAccounts('SOUND')
  const tool = useAudioTool()
  const [accountId, setAccountId] = useState('')
  const [prompt, setPrompt] = useState('')
  const [auto, setAuto] = useState(true)
  const [duration, setDuration] = useState(5)
  const [loop, setLoop] = useState(false)
  const [result, setResult] = useState<MediaAsset>()
  useDefaultAccount(accounts.usable, accountId, setAccountId)

  if (accounts.isPending) return <Spinner label="Loading accounts" />
  if (accounts.usable.length === 0) return <ConnectAccount title="Sound effects need ElevenLabs" body="Add an ElevenLabs API key under AI accounts & keys." />

  function submit(e: FormEvent) {
    e.preventDefault()
    tool.mutate(
      { tool: 'sound-effects', accountId, prompt: prompt.trim(), durationSeconds: auto ? undefined : duration, loop },
      {
        onSuccess: (r) => {
          setResult(r.media)
          toast.success('Sound effect saved')
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <form onSubmit={submit} className="max-w-2xl flex flex-col gap-5">
      <Card>
        <Field label="Account">{(id) => <AccountSelect id={id} accounts={accounts.usable} value={accountId} onChange={setAccountId} />}</Field>
        <Field label="Describe the sound" hint="English descriptions work best.">
          {(id, d) => <Textarea id={id} aria-describedby={d} rows={3} maxLength={450} value={prompt} onChange={(e) => setPrompt(e.target.value)} />}
        </Field>
        <div className="flex flex-wrap gap-1.5 -mt-2">
          {SFX_IDEAS.map((s) => (
            <button key={s} type="button" onClick={() => setPrompt(s)} className="rounded-full border border-line px-3 py-1 text-xs text-muted hover:text-ink">
              {s}
            </button>
          ))}
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} className="accent-marigold-500" />
          Let the model choose the length
        </label>
        {!auto && <Slider label="Length" min={0.5} max={30} step={0.5} value={duration} onChange={setDuration} format={(v) => `${v} s`} />}
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={loop} onChange={(e) => setLoop(e.target.checked)} className="accent-marigold-500" />
          Seamless loop (for ambience)
        </label>
        <Button type="submit" variant="primary" disabled={!prompt.trim()} loading={tool.isPending} className="self-start">
          Make sound effect
        </Button>
      </Card>
      {tool.error && <ErrorNotice error={tool.error} />}
      {result && <ResultCard media={result} />}
    </form>
  )
}

// ---- audio tools ----------------------------------------------------------------------

const OPS: { id: AudioOperation; label: string; body: string }[] = [
  { id: 'DENOISE', label: 'Remove noise', body: 'Fan hum, hiss and rumble.' },
  { id: 'REMOVE_SILENCE', label: 'Trim long pauses', body: 'Pauses over 0.6 s become short natural gaps.' },
  { id: 'ENHANCE', label: 'Enhance voice', body: 'Clearer, fuller narration with gentle compression.' },
  { id: 'NORMALIZE', label: 'Even loudness', body: '−14 LUFS, the level YouTube and Instagram play at.' },
]

type ToolId = 'cleanup' | 'speed' | 'music' | 'extract'

function AudioToolsTab() {
  const [toolId, setToolId] = useState<ToolId>('cleanup')
  const tool = useAudioTool()
  const [source, setSource] = useState<MediaAsset>()
  const [music, setMusic] = useState<MediaAsset>()
  const [ops, setOps] = useState<AudioOperation[]>(['DENOISE', 'ENHANCE', 'NORMALIZE'])
  const [speed, setSpeed] = useState(1)
  const [semitones, setSemitones] = useState(0)
  const [volume, setVolume] = useState(-18)
  const [duck, setDuck] = useState(true)
  const [fade, setFade] = useState(2)
  const [result, setResult] = useState<MediaAsset>()

  const ready =
    toolId === 'cleanup' ? !!source && ops.length > 0 : toolId === 'speed' ? !!source : toolId === 'music' ? !!source && !!music : !!source && source.kind === 'VIDEO'

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!source) return
    const call =
      toolId === 'cleanup'
        ? ({ tool: 'cleanup', mediaId: source.id, operations: ops } as const)
        : toolId === 'speed'
          ? ({ tool: 'speed-pitch', mediaId: source.id, speed, semitones } as const)
          : toolId === 'music'
            ? ({ tool: 'mix', voiceMediaId: source.id, musicMediaId: music!.id, musicVolumeDb: volume, duck, fadeSeconds: fade } as const)
            : ({ tool: 'extract', videoMediaId: source.id } as const)
    tool.mutate(call, {
      onSuccess: (r) => {
        setResult(r.media)
        toast.success('New file saved to your media library')
      },
      onError: (err) => toast.error(err.message),
    })
  }

  const TOOLS: { id: ToolId; label: string; body: string }[] = [
    { id: 'cleanup', label: 'Clean up', body: 'Noise, pauses, clarity, loudness' },
    { id: 'speed', label: 'Speed & pitch', body: 'Faster, slower, higher, deeper' },
    { id: 'music', label: 'Background music', body: 'Music that dips when you speak' },
    { id: 'extract', label: 'Audio from video', body: 'Save a video’s sound as MP3' },
  ]

  return (
    <form onSubmit={submit} className="flex flex-col gap-5 max-w-3xl">
      <div role="radiogroup" aria-label="Tool" className="grid grid-cols-2 lg:grid-cols-4 gap-2">
        {TOOLS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="radio"
            aria-checked={toolId === t.id}
            onClick={() => {
              setToolId(t.id)
              setResult(undefined)
            }}
            className={cn(
              'rounded-xl border p-3 text-left',
              toolId === t.id ? 'border-marigold-500 bg-marigold-500/10' : 'border-line bg-surface hover:bg-surface-2',
            )}
          >
            <span className="block font-medium">{t.label}</span>
            <span className="block text-xs text-muted mt-0.5">{t.body}</span>
          </button>
        ))}
      </div>

      <Card>
        <MediaField
          label={toolId === 'extract' ? 'Video' : toolId === 'music' ? 'Narration' : 'Audio or video'}
          kind={toolId === 'extract' ? 'VIDEO' : ['AUDIO', 'VIDEO']}
          value={source}
          onChange={setSource}
        />
        <p className="text-xs text-faint -mt-3">The original stays as it is. The result is saved as a new MP3.</p>

        {toolId === 'cleanup' && (
          <fieldset className="grid sm:grid-cols-2 gap-2">
            <legend className="text-sm font-medium mb-2">Steps</legend>
            {OPS.map((o) => (
              <label key={o.id} className="flex items-start gap-2 rounded-lg border border-line p-3 text-sm">
                <input
                  type="checkbox"
                  className="mt-1 accent-marigold-500"
                  checked={ops.includes(o.id)}
                  onChange={(e) => setOps((x) => (e.target.checked ? [...x, o.id] : x.filter((y) => y !== o.id)))}
                />
                <span>
                  <span className="font-medium block">{o.label}</span>
                  <span className="text-muted">{o.body}</span>
                </span>
              </label>
            ))}
          </fieldset>
        )}

        {toolId === 'speed' && (
          <div className="grid sm:grid-cols-2 gap-4">
            <Slider label="Speed" min={0.5} max={2} step={0.05} value={speed} onChange={setSpeed} format={(v) => `${v.toFixed(2)}×`} hint="Pitch stays the same." />
            <Slider
              label="Pitch"
              min={-12}
              max={12}
              step={0.5}
              value={semitones}
              onChange={setSemitones}
              format={(v) => (v === 0 ? 'Natural' : `${v > 0 ? '+' : ''}${v} semitones`)}
              hint="Length stays the same."
            />
          </div>
        )}

        {toolId === 'music' && (
          <>
            <MediaField label="Music" kind="AUDIO" value={music} onChange={setMusic} />
            <p className="text-xs text-faint -mt-3">Use music you have the rights to. It loops to the narration’s length.</p>
            <Slider label="Music level" min={-36} max={0} step={1} value={volume} onChange={setVolume} format={(v) => `${v} dB`} />
            <Slider label="Fade in and out" min={0} max={8} step={0.5} value={fade} onChange={setFade} format={(v) => `${v} s`} />
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={duck} onChange={(e) => setDuck(e.target.checked)} className="accent-marigold-500" />
              Lower the music while someone speaks
            </label>
          </>
        )}

        <Button type="submit" variant="primary" disabled={!ready} loading={tool.isPending} className="self-start">
          {toolId === 'cleanup' ? 'Clean up' : toolId === 'speed' ? 'Apply' : toolId === 'music' ? 'Mix' : 'Extract audio'}
        </Button>
      </Card>
      {tool.error && <ErrorNotice error={tool.error} />}
      {result && <ResultCard media={result} />}
    </form>
  )
}

// ---- pronunciation -------------------------------------------------------------------

function PronunciationTab() {
  const rules = useRules()
  const save = useSaveRule()
  const del = useDeleteRule()
  const [term, setTerm] = useState('')
  const [replacement, setReplacement] = useState('')
  const [language, setLanguage] = useState<Language | ''>('')
  const [editing, setEditing] = useState<PronunciationRule>()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!term.trim() || !replacement.trim()) return
    save.mutate(
      { id: editing?.id, term: term.trim(), replacement: replacement.trim(), language: language || undefined },
      {
        onSuccess: () => {
          setTerm('')
          setReplacement('')
          setEditing(undefined)
          toast.success(editing ? 'Rule updated' : 'Rule added')
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <div className="max-w-3xl flex flex-col gap-6">
      <p className="text-muted">
        Fix words a voice keeps getting wrong: names, brands, English words inside Tamil. Rules apply to every voice-over and dialogue before it is sent to the provider.
      </p>
      <form onSubmit={submit} className="rounded-2xl border border-line bg-surface p-5 grid sm:grid-cols-[1fr_1fr_9rem_auto] gap-3 items-end">
        <Field label="Written as">{(id) => <Input id={id} maxLength={120} value={term} onChange={(e) => setTerm(e.target.value)} placeholder="Leenax" />}</Field>
        <Field label="Say it as">{(id) => <Input id={id} maxLength={240} value={replacement} onChange={(e) => setReplacement(e.target.value)} placeholder="Lee-nax" />}</Field>
        <Field label="Language">
          {(id) => (
            <Select id={id} value={language} onChange={(e) => setLanguage(e.target.value as Language | '')}>
              <option value="">Any</option>
              <option value="TA">Tamil</option>
              <option value="EN">English</option>
            </Select>
          )}
        </Field>
        <div className="flex gap-2">
          {editing && (
            <Button
              type="button"
              onClick={() => {
                setEditing(undefined)
                setTerm('')
                setReplacement('')
              }}
            >
              Cancel
            </Button>
          )}
          <Button type="submit" variant="primary" loading={save.isPending} disabled={!term.trim() || !replacement.trim()}>
            {editing ? 'Save' : 'Add'}
          </Button>
        </div>
      </form>
      {rules.isPending && <Spinner label="Loading rules" />}
      {rules.error && <ErrorNotice error={rules.error} onRetry={() => rules.refetch()} />}
      {rules.data?.length === 0 && <EmptyState title="No rules yet" body="Add one above. For example, write “AI” and say it as “A.I.”." />}
      {rules.data && rules.data.length > 0 && (
        <table className="w-full text-sm rounded-2xl overflow-hidden border border-line">
          <thead className="bg-surface-2 text-left text-muted">
            <tr>
              <th className="p-3 font-medium">Written as</th>
              <th className="p-3 font-medium">Said as</th>
              <th className="p-3 font-medium">Language</th>
              <th className="p-3">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--line)] bg-surface">
            {rules.data.map((r) => (
              <tr key={r.id}>
                <td className="p-3">{r.term}</td>
                <td className="p-3">{r.replacement}</td>
                <td className="p-3 text-muted">{r.language === 'TA' ? 'Tamil' : r.language === 'EN' ? 'English' : 'Any'}</td>
                <td className="p-3 text-right whitespace-nowrap">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setEditing(r)
                      setTerm(r.term)
                      setReplacement(r.replacement)
                      setLanguage(r.language ?? '')
                    }}
                  >
                    Edit
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`Delete rule for ${r.term}`}
                    icon={<Trash2 className="size-4" />}
                    onClick={() => del.mutate(r.id, { onError: (e) => toast.error(e.message) })}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

