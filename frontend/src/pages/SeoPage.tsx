import { ChevronLeft, FileUp, Trash2 } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { TextModelPicker, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, PageHeader, Select, Spinner, StatusPill, Textarea } from '../components/ui'
import { cn } from '../lib/cn'
import { formatDate, formatRelative } from '../lib/format'
import { useProject } from '../lib/queries'
import { STATUS_LABEL, statusTone, type ReviewStatus } from '../lib/reviews'
import { useSeoOverview, useStartResearch, useTrendsActions, type SeoOverview, type TopicSeo } from '../lib/seo'
import { toast } from '../stores/ui'

/** /projects/:id/seo — YouTube SEO for every approved topic, within the day's YouTube search budget. */
export function SeoPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const data = useSeoOverview(id)
  const [researching, setResearching] = useState<TopicSeo | null>(null)

  if (project.isPending || data.isPending) return <Spinner label="Loading YouTube SEO" />
  if (project.error || data.error) return <ErrorNotice error={project.error ?? data.error} onRetry={() => data.refetch()} />
  const p = project.data
  const d = data.data
  const b = d.budget

  return (
    <>
      <Link to={`/projects/${p.id}/materials`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Notes and questions
      </Link>
      <PageHeader
        title="YouTube SEO"
        description="Keywords, competitor videos and title options for each topic, from YouTube’s own API and your Google Trends downloads. Nothing here is a search volume: YouTube doesn’t publish them."
      />

      <section aria-label="YouTube search budget" className="mb-8 rounded-2xl border border-line px-5 py-4 flex flex-wrap items-baseline gap-x-6 gap-y-1">
        <p className="text-2xl font-semibold tabular-nums">
          {b.left} <span className="text-base font-normal text-muted">of {b.dailySearches} YouTube searches left today</span>
        </p>
        <p className="text-sm text-muted">
          Shared by everyone on this Leenax. Resets {new Date(b.resetsAt).toLocaleString(undefined, { weekday: 'short', hour: 'numeric', minute: '2-digit' })} (midnight in California, as YouTube counts). Repeat searches within {b.cacheDays} days are free.
        </p>
      </section>

      <TrendsSection projectId={id} overview={d} />

      <h2 className="text-lg font-semibold mt-10 mb-2">Topics</h2>
      {!d.topicsApproved || d.topics.length === 0 ? (
        <EmptyState title="The topic list isn't approved yet" body="SEO research is done per approved topic."
          action={<Link className="underline underline-offset-2" to={`/projects/${id}/topics`}>Go to topics</Link>} />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {d.topics.map((t) => (
            <li key={t.topicId} className="py-4 grid gap-2 lg:grid-cols-[minmax(0,18rem)_1fr_auto] lg:items-center">
              <p className={cn('break-words', t.depth === 1 ? 'font-semibold' : 'font-medium')} style={{ paddingLeft: `${(t.depth - 1) * 1.25}rem` }}>{t.title}</p>
              <div className="text-sm min-w-0">
                {t.running ? (
                  <p className="text-muted" role="status">Researching… this takes a minute or two.</p>
                ) : t.reportId ? (
                  <Link to={`/seo/${t.reportId}`} className="inline-flex flex-wrap items-center gap-2 hover:underline">
                    SEO report, version {t.reportVersionNo}
                    {t.reportStatus && <StatusPill tone={statusTone(t.reportStatus as ReviewStatus)}>{STATUS_LABEL[t.reportStatus as ReviewStatus]}</StatusPill>}
                  </Link>
                ) : (
                  <p className="text-muted">Not researched</p>
                )}
                {t.chosenTitle && !t.running && <p className="text-muted truncate">“{t.chosenTitle}”</p>}
                {t.researchedAt && !t.running && (
                  <p className="text-faint">Researched {formatRelative(t.researchedAt)}{t.researchStatus === 'PARTIAL' ? ', partial' : ''}</p>
                )}
                {t.jobError && !t.running && <p className="text-rose-signal">Last research failed: {t.jobError}</p>}
              </div>
              {d.canEdit && (
                <Button size="sm" variant={t.reportId ? 'ghost' : 'secondary'} disabled={t.running || t.reportStatus === 'IN_REVIEW'} onClick={() => setResearching(t)}
                  title={t.reportStatus === 'IN_REVIEW' ? 'The report is in review' : undefined}>
                  {t.reportId ? 'Research again' : 'Research'}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {researching && <ResearchDialog projectId={id} topic={researching} overview={d} onClose={() => setResearching(null)} />}
    </>
  )
}

function TrendsSection({ projectId, overview: d }: { projectId: string; overview: SeoOverview }) {
  const input = useRef<HTMLInputElement>(null)
  const { upload, remove } = useTrendsActions(projectId)
  return (
    <section aria-labelledby="trends-h">
      <div className="flex flex-wrap items-center gap-3 mb-2">
        <h2 id="trends-h" className="text-lg font-semibold flex-1">Google Trends files</h2>
        {d.canEdit && (
          <>
            <input ref={input} type="file" accept=".csv,text/csv" className="sr-only" aria-label="Choose a Google Trends CSV"
              onChange={(e) => {
                const f = e.target.files?.[0]
                e.target.value = ''
                if (f) upload.mutate(f, { onSuccess: (t) => toast.success(`Imported ${t.terms.length} term${t.terms.length === 1 ? '' : 's'}`), onError: (er) => toast.error(er.message) })
              }} />
            <Button size="sm" icon={<FileUp className="size-4" />} loading={upload.isPending} onClick={() => input.current?.click()}>
              Import a CSV
            </Button>
          </>
        )}
      </div>
      <p className="text-sm text-muted mb-3">
        On trends.google.com, compare up to five search terms (choose YouTube Search), then download “Interest over time”. Values are relative,
        0 to 100 within that one download, so they’re used as a demand signal, never as a number of searches.
      </p>
      {d.trends.length === 0 ? (
        <p className="text-sm text-faint">None imported. Without one, opportunity scores are partial.</p>
      ) : (
        <ul className="space-y-2">
          {d.trends.map((t) => (
            <li key={t.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm rounded-xl border border-line px-4 py-2">
              <span className="font-medium">{t.filename}</span>
              <span className="text-muted flex-1 min-w-0">
                {t.terms.join(', ')}. {t.geo ?? 'Worldwide'}, {formatDate(t.firstDate ?? undefined)} to {formatDate(t.lastDate ?? undefined)}
              </span>
              {d.canEdit && (
                <button type="button" className="grid place-items-center size-8 rounded-md text-muted hover:text-ink hover:bg-surface-2" aria-label={`Remove ${t.filename}`}
                  onClick={() => remove.mutate(t.id, { onError: (e) => toast.error(e.message) })}>
                  <Trash2 className="size-4" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ResearchDialog({ projectId, topic, overview: d, onClose }: { projectId: string; topic: TopicSeo; overview: SeoOverview; onClose: () => void }) {
  const { usable, isPending } = useUsableAccounts('TEXT')
  const [accountId, setAccountId] = useState('')
  const [model, setModel] = useState('')
  const yt = d.youtube.filter((a) => a.status !== 'NEEDS_RECONNECT')
  const [youtubeId, setYoutubeId] = useState(yt[0]?.id ?? '')
  const [languages, setLanguages] = useState<string[]>(['en', 'ta'])
  const [searches, setSearches] = useState(Math.min(5, d.budget.left))
  const [region, setRegion] = useState('LK')
  const [trends, setTrends] = useState<string[]>(d.trends.map((t) => t.id))
  const [guidance, setGuidance] = useState('')
  const start = useStartResearch(projectId)
  useDefaultAccount(usable, accountId, setAccountId)
  const toggle = (l: string[], set: (v: string[]) => void, v: string) => set(l.includes(v) ? l.filter((x) => x !== v) : [...l, v])
  const tooMany = searches > d.budget.left
  const noYoutube = searches > 0 && !youtubeId
  return (
    <Dialog
      open
      title={`SEO research: ${topic.title}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={start.isPending}
            disabled={!accountId || !model || languages.length === 0 || tooMany || noYoutube || searches < 0}
            onClick={() => start.mutate(
              { topicId: topic.topicId, youtubeAccountId: searches > 0 ? youtubeId : undefined, accountId, model, languages, region, searches, trendsIds: trends, guidance: guidance.trim() || undefined },
              { onSuccess: () => { toast.success('Researching'); onClose() }, onError: (e) => toast.error(e.message) },
            )}
          >
            Research
          </Button>
        </>
      }
    >
      <p className="text-muted mb-4">
        The AI suggests keywords; Leenax searches YouTube for the best ones and reads the top 20 videos’ public numbers; the AI then writes 10 to 15 titles,
        a description, tags and plans. Everything lands in a draft report for you to edit and approve.
        {topic.reportStatus === 'DRAFT' && ' The current draft will be rewritten.'}
      </p>
      <div className="space-y-4">
        <fieldset>
          <legend className="font-medium mb-1">Languages</legend>
          <div className="flex gap-4">
            {[['en', 'English'], ['ta', 'Tamil']].map(([v, label]) => (
              <label key={v} className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="size-4 accent-marigold-600" checked={languages.includes(v)} onChange={() => toggle(languages, setLanguages, v)} />
                {label}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="grid sm:grid-cols-[1fr_8rem] gap-4">
          <Field label="YouTube searches" hint={`${d.budget.left} left today; each costs 1 (cached ones are free)`} error={tooMany ? `Only ${d.budget.left} left today` : undefined}>
            {(fid, hid) => <Input id={fid} aria-describedby={hid} type="number" min={0} max={10} value={searches} onChange={(e) => setSearches(Number(e.target.value))} />}
          </Field>
          <Field label="Region" hint="Country code">
            {(fid, hid) => <Input id={fid} aria-describedby={hid} maxLength={2} value={region} onChange={(e) => setRegion(e.target.value.toUpperCase())} />}
          </Field>
        </div>
        {searches > 0 && (
          yt.length === 0 ? (
            <p className="text-sm text-rose-signal">No YouTube account is connected (an administrator connects one under Social accounts). Set searches to 0 to use cached results only.</p>
          ) : (
            <Field label="Search with this YouTube account" hint="Read-only: it only searches and reads public numbers">
              {(fid, hid) => (
                <Select id={fid} aria-describedby={hid} value={youtubeId} onChange={(e) => setYoutubeId(e.target.value)}>
                  {yt.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                </Select>
              )}
            </Field>
          )
        )}
        {d.trends.length > 0 && (
          <fieldset>
            <legend className="font-medium mb-1">Google Trends files to use</legend>
            {d.trends.map((t) => (
              <label key={t.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" className="size-4 accent-marigold-600" checked={trends.includes(t.id)} onChange={() => toggle(trends, setTrends, t.id)} />
                {t.filename} <span className="text-muted">({t.terms.join(', ')})</span>
              </label>
            ))}
          </fieldset>
        )}
        {isPending ? <Spinner label="Loading AI accounts" /> : <TextModelPicker accounts={usable} accountId={accountId} setAccountId={setAccountId} model={model} setModel={setModel} required hint="Uses your own account: 2 AI calls." />}
        <Field label="Guidance (optional)" hint="For example: “Students sit the University of Jaffna exam.”">
          {(fid, hid) => <Textarea id={fid} aria-describedby={hid} rows={2} maxLength={1000} value={guidance} onChange={(e) => setGuidance(e.target.value)} />}
        </Field>
      </div>
    </Dialog>
  )
}
