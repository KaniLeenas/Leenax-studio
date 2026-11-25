import { ChevronLeft, Download, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { History, ReviewPanel, VersionList } from '../components/review'
import { Button, ErrorNotice, Input, Select, Spinner, Textarea } from '../components/ui'
import { useReviewPageAt } from '../components/useReviewPage'
import { cn } from '../lib/cn'
import { formatRelative } from '../lib/format'
import {
  CRITERIA_LABEL,
  exportUrl,
  KIND_LABEL,
  LIMITS,
  metricLabel,
  metricText,
  parseTime,
  secondsText,
  STRATEGIES,
  STRATEGY_LABEL,
  STYLE_LABEL,
  tagChars,
  TITLE_STYLES,
  titleWarnings,
  useResearch,
  type Metric,
  type Research,
  type SeoReport,
  type Strategy,
  type Title,
} from '../lib/seo'
import { toast } from '../stores/ui'

/** A number with its source or "estimated" beside it; the full method on hover. */
export function MetricValue({ m, label = true }: { m?: Metric | null; label?: boolean }) {
  if (!m) return <span className="text-faint">–</span>
  const tip = [m.estimated ? `Estimated. ${m.method ?? ''}` : m.source, m.retrievedAt && `Retrieved ${m.retrievedAt.slice(0, 10)}`, m.note]
    .filter(Boolean).join('. ')
  return (
    <span title={tip || undefined}>
      <span className={cn('tabular-nums whitespace-nowrap', m.value == null && 'text-muted')}>{metricText(m)}</span>
      {label && m.value != null && <span className="text-xs text-muted"> {m.estimated ? 'est.' : `· ${shortSource(m)}`}</span>}
    </span>
  )
}

/** A short name for a source; the full one is on hover. */
function shortSource(m: Metric) {
  const src = (m.source ?? '')
    .replace('YouTube Data API videos.list (public numbers)', 'YouTube')
    .replace('YouTube Data API search.list', 'YouTube search')
    .replace(/^Counted by Leenax.*$/, 'counted')
    .replace(/^Google Trends CSV.*$/, 'Trends')
    .replace(/ \(.*\)$/, '')
  return [src, m.retrievedAt?.slice(0, 10)].filter(Boolean).join(', ')
}

/** /seo/:resourceId — a topic's SEO report: what people choose and write, and the research beneath it. */
export function SeoReportPage() {
  const { resourceId = '' } = useParams()
  const page = useReviewPageAt<SeoReport>({ resourceId })
  const research = useResearch(page.content?.researchId)

  if (page.loading) return <Spinner label="Opening the SEO report" />
  if (page.error) return <ErrorNotice error={page.error} onRetry={() => page.state.refetch()} />
  const state = page.state.data!
  const d = page.detail
  const rep = page.content
  if (!state.resource || !d || !rep) return <ErrorNotice error={new Error('This report has no versions')} />
  const r = state.resource
  const edit = page.editable
  const set = (patch: Partial<SeoReport>) => page.setContent({ ...rep, ...patch })
  const res = research.data?.data

  return (
    <>
      <Link to={`/projects/${r.projectId}/seo`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        YouTube SEO
      </Link>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{rep.topicTitle}: YouTube SEO</h1>
        <p className="text-muted mt-1">
          {research.data ? <>Researched {formatRelative(research.data.createdAt)}{research.data.status === 'PARTIAL' ? ' (partial: see the limits below)' : ''}</> : 'Loading the research…'}
          {rep.model ? `. Written with ${rep.model}` : ''}
        </p>
      </header>

      <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_21rem] items-start">
        <div className="min-w-0 space-y-10">
          {page.dirty && d.version.status === 'DRAFT' && (
            <div className="sticky top-0 z-20 -mx-2 px-2 py-2 bg-[var(--bg)] flex items-center gap-3 border-b border-line">
              <span className="text-sm text-muted flex-1">Unsaved changes</span>
              <Button variant="ghost" onClick={page.discard}>Discard</Button>
              <Button variant="primary" loading={page.saving} onClick={() => page.saveDraft().then(() => toast.success('Draft saved'), (e) => toast.error(e.message))}>Save draft</Button>
            </div>
          )}
          {page.reviewerEditing && <p className="rounded-xl border border-marigold-500/50 px-4 py-3 text-sm">You're editing as the reviewer. Approving with your edits saves them as a new, approved version.</p>}

          <Titles rep={rep} edit={edit} set={set} />

          <section aria-labelledby="desc-h" className="space-y-4">
            <h2 id="desc-h" className="text-lg font-semibold">Description, keywords and tags</h2>
            <Text label="Description" value={rep.description ?? ''} max={LIMITS.description} rows={8} edit={edit} onChange={(v) => set({ description: v })} />
            <div className="grid sm:grid-cols-2 gap-4">
              <Lines label="Primary keywords" hint="One per line" value={rep.primaryKeywords} edit={edit} onChange={(v) => set({ primaryKeywords: v })} />
              <Lines label="Secondary keywords" hint="One per line" value={rep.secondaryKeywords} edit={edit} onChange={(v) => set({ secondaryKeywords: v })} />
            </div>
            <div>
              <Lines label="Tags" hint="One per line" value={rep.tags} edit={edit} onChange={(v) => set({ tags: v })} />
              <p className={cn('text-sm mt-1', tagChars(rep.tags) > LIMITS.tags ? 'text-rose-signal' : 'text-muted')}>
                {tagChars(rep.tags)} of {LIMITS.tags} characters YouTube allows for tags (counted here)
              </p>
            </div>
            <div>
              <Lines label="Hashtags" hint="One per line; the first three show above the title" value={rep.hashtags} edit={edit} onChange={(v) => set({ hashtags: v.map((h) => (h.startsWith('#') ? h : '#' + h)) })} />
              {rep.hashtags.length > LIMITS.hashtags && <p className="text-sm text-rose-signal mt-1">YouTube ignores hashtags past {LIMITS.hashtags}</p>}
            </div>
            <div className="grid sm:grid-cols-2 gap-4">
              <Text label="Thumbnail text" value={rep.thumbnailText ?? ''} max={60} rows={1} edit={edit} onChange={(v) => set({ thumbnailText: v })} />
              <Text label="Thumbnail picture prompt" value={rep.thumbnailPrompt ?? ''} max={1000} rows={3} edit={edit} onChange={(v) => set({ thumbnailPrompt: v })} />
            </div>
          </section>

          <Chapters rep={rep} edit={edit} set={set} />
          <SuggestionList title="Playlists" items={rep.playlists} edit={edit} onChange={(v) => set({ playlists: v })} />
          <SuggestionList title="Suggested next videos" items={rep.nextVideos} edit={edit} onChange={(v) => set({ nextVideos: v })} />
          <Strategies rep={rep} edit={edit} set={set} />

          {research.isPending ? <Spinner label="Loading the research" /> : research.error ? <ErrorNotice error={research.error} /> : res && <ResearchView res={res} />}
        </div>

        <aside className="space-y-6 xl:sticky xl:top-6">
          <ReviewPanel detail={d} kindLabel="SEO report" dirty={page.dirty} edited={rep} saveFirst={page.saveDraft} editing={page.reviewerEditing}
            onReviewerEdit={(on) => { page.setReviewerEditing(on); if (!on) page.discard() }} onEdit={page.startDraft}
            originLabel={d.version.origin === 'AI' ? 'researched and written with AI' : undefined} />
          <section aria-labelledby="export-h">
            <h2 id="export-h" className="font-semibold mb-2">Download version {d.version.versionNo}</h2>
            <div className="flex flex-wrap gap-2">
              {([['pdf', 'PDF'], ['docx', 'Word'], ['xlsx', 'Excel']] as const).map(([f, label]) => (
                <a key={f} className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-3 h-9 text-sm hover:bg-surface-2" href={exportUrl(d.version.id, f)}>
                  <Download className="size-4" aria-hidden /> {label}
                </a>
              ))}
            </div>
            <p className="text-sm text-muted mt-1">The 23-part report; Excel has the keywords, competitors and titles as sheets.</p>
          </section>
          <section aria-labelledby="versions-h">
            <h2 id="versions-h" className="font-semibold mb-2">Versions</h2>
            <VersionList versions={state.versions} current={d.version.id} approvedId={state.approvedVersionId} onPick={page.pick} />
          </section>
          <section aria-labelledby="history-h">
            <h2 id="history-h" className="font-semibold mb-2">History</h2>
            <History resourceId={r.id} />
          </section>
        </aside>
      </div>
    </>
  )
}

// ---- titles -------------------------------------------------------------------------------------

function Titles({ rep, edit, set }: { rep: SeoReport; edit: boolean; set: (p: Partial<SeoReport>) => void }) {
  const [open, setOpen] = useState<string | null>(null)
  const change = (id: string, patch: Partial<Title>) => set({ titles: rep.titles.map((t) => (t.id === id ? { ...t, ...patch } : t)) })
  const n = rep.titles.length
  return (
    <section aria-labelledby="titles-h">
      <h2 id="titles-h" className="text-lg font-semibold">Title options</h2>
      <p className={cn('text-sm mb-3', n < 10 || n > 15 ? 'text-rose-signal' : 'text-muted')}>
        {n} of 10 to 15. Choose the one to use. AI scores are the model’s own estimates, 1 to 5; editing a title clears its score.
      </p>
      {rep.recommendationReason && (
        <p className="text-sm mb-3 rounded-xl bg-surface-2 px-4 py-2">
          <span className="font-medium">Recommended: “{rep.titles.find((t) => t.id === rep.recommendedTitleId)?.text}”.</span> {rep.recommendationReason}
        </p>
      )}
      <fieldset>
        <legend className="sr-only">Title to use</legend>
        <ol className="space-y-2">
          {rep.titles.map((t) => {
            const warnings = edit ? [...new Set([...titleWarnings(t.text), ...(t.check?.warnings ?? []).filter((w) => !/^(Empty|Longer|May be cut|Sounds like)/.test(w))])] : t.check?.warnings ?? []
            const len = [...t.text].length
            return (
              <li key={t.id} className={cn('rounded-xl border px-4 py-3', t.id === rep.chosenTitleId ? 'border-marigold-500' : 'border-line')}>
                <div className="flex items-start gap-3">
                  <input type="radio" name="chosen-title" className="mt-2.5 size-4 accent-marigold-600" checked={t.id === rep.chosenTitleId} disabled={!edit}
                    onChange={() => set({ chosenTitleId: t.id })} aria-label={`Use “${t.text}”`} />
                  <div className="flex-1 min-w-0">
                    {edit ? (
                      <Input aria-label="Title" value={t.text} maxLength={150} onChange={(e) => change(t.id, { text: e.target.value })} />
                    ) : (
                      <p className="font-medium break-words pt-1.5">{t.text}</p>
                    )}
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 mt-1 text-sm text-muted">
                      {edit ? (
                        <div className="w-44">
                          <Select aria-label="Style" value={t.style} onChange={(e) => change(t.id, { style: e.target.value })}>
                            {TITLE_STYLES.map((s) => <option key={s} value={s}>{STYLE_LABEL[s]}</option>)}
                          </Select>
                        </div>
                      ) : <span>{STYLE_LABEL[t.style] ?? t.style}</span>}
                      <span className={cn('tabular-nums', len > LIMITS.title ? 'text-rose-signal' : len > LIMITS.titleShown && 'text-ink')}>{len} characters</span>
                      {t.id === rep.recommendedTitleId && <span className="text-ink">Recommended</span>}
                      {t.check?.overall ? (
                        <button type="button" className="underline underline-offset-2" aria-expanded={open === t.id} onClick={() => setOpen(open === t.id ? null : t.id)}>
                          AI score {metricText(t.check.overall)} (estimate)
                        </button>
                      ) : <span>Not scored</span>}
                      {edit && (
                        <button type="button" className="ml-auto grid place-items-center size-8 rounded-md hover:text-ink hover:bg-surface-2" aria-label={`Remove “${t.text}”`}
                          onClick={() => set({ titles: rep.titles.filter((x) => x.id !== t.id), chosenTitleId: rep.chosenTitleId === t.id ? null : rep.chosenTitleId })}>
                          <Trash2 className="size-4" />
                        </button>
                      )}
                    </div>
                    {warnings.length > 0 && <p className="text-sm text-rose-signal mt-1">{warnings.join('. ')}.</p>}
                    {open === t.id && t.check?.ai && (
                      <div className="mt-2 text-sm">
                        <dl className="grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-0.5">
                          {Object.entries(t.check.ai).map(([k, m]) => (
                            <div key={k} className="flex justify-between gap-2"><dt className="text-muted">{CRITERIA_LABEL[k] ?? k}</dt><dd><MetricValue m={m} label={false} /></dd></div>
                          ))}
                        </dl>
                        {t.check.reasoning && <p className="text-muted mt-1">{t.check.reasoning}</p>}
                      </div>
                    )}
                  </div>
                </div>
              </li>
            )
          })}
        </ol>
      </fieldset>
      {edit && n < 15 && (
        <Button className="mt-2" variant="ghost" icon={<Plus className="size-4" />}
          onClick={() => set({ titles: [...rep.titles, { id: 't' + Math.random().toString(36).slice(2, 10), text: '', style: 'EDUCATIONAL', language: 'en', check: null }] })}>
          Add a title
        </Button>
      )}
    </section>
  )
}

// ---- small editors ------------------------------------------------------------------------------

function Text({ label, value, max, rows, edit, onChange }: { label: string; value: string; max: number; rows: number; edit: boolean; onChange: (v: string) => void }) {
  const id = 'f-' + label.replace(/\W+/g, '-').toLowerCase()
  return (
    <div>
      <label htmlFor={id} className="font-medium block mb-1">{label}</label>
      {edit ? (
        rows === 1 ? <Input id={id} value={value} maxLength={max} onChange={(e) => onChange(e.target.value)} />
          : <Textarea id={id} rows={rows} value={value} onChange={(e) => onChange(e.target.value)} />
      ) : (
        <p id={id} className="whitespace-pre-wrap break-words">{value || <span className="text-faint">None</span>}</p>
      )}
      {edit && rows > 1 && <p className={cn('text-sm mt-1 tabular-nums', value.length > max ? 'text-rose-signal' : 'text-muted')}>{value.length} of {max} characters</p>}
    </div>
  )
}

function Lines({ label, hint, value, edit, onChange }: { label: string; hint: string; value: string[]; edit: boolean; onChange: (v: string[]) => void }) {
  const id = 'f-' + label.replace(/\W+/g, '-').toLowerCase()
  const [draft, setDraft] = useState<string | null>(null)
  return (
    <div>
      <label htmlFor={id} className="font-medium block mb-1">{label}</label>
      {edit ? (
        <>
          <Textarea id={id} rows={Math.min(8, Math.max(3, value.length + 1))} value={draft ?? value.join('\n')}
            onChange={(e) => { setDraft(e.target.value); onChange(e.target.value.split('\n').map((x) => x.trim()).filter(Boolean)) }}
            onBlur={() => setDraft(null)} />
          <p className="text-sm text-muted mt-1">{hint}</p>
        </>
      ) : value.length ? (
        <ul id={id} className="flex flex-wrap gap-1.5">{value.map((v) => <li key={v} className="rounded-full border border-line px-2.5 py-0.5 text-sm">{v}</li>)}</ul>
      ) : <p id={id} className="text-faint">None</p>}
    </div>
  )
}

function Chapters({ rep, edit, set }: { rep: SeoReport; edit: boolean; set: (p: Partial<SeoReport>) => void }) {
  const ch = rep.chapters
  const update = (i: number, title: string, seconds: number) =>
    set({ chapters: ch.map((c, j) => (j === i ? { title, start: { ...c.start, value: seconds, estimated: true } } : c)) })
  return (
    <section aria-labelledby="ch-h">
      <h2 id="ch-h" className="text-lg font-semibold">Chapters</h2>
      <p className="text-sm text-muted mb-2">Planned times (estimated); set the real ones after recording. YouTube needs at least three, the first at 0:00.</p>
      <ol className="space-y-1.5">
        {ch.map((c, i) => (
          <li key={i} className="flex items-center gap-2">
            {edit ? (
              <>
                <Input aria-label={`Start of chapter ${i + 1}`} className="w-24 tabular-nums" defaultValue={secondsText(c.start.value ?? 0)}
                  onBlur={(e) => { const s = parseTime(e.target.value); if (s == null) toast.error('Use minutes:seconds, like 4:05'); else update(i, c.title, s) }} />
                <Input aria-label={`Chapter ${i + 1} title`} value={c.title} maxLength={100} onChange={(e) => update(i, e.target.value, c.start.value ?? 0)} />
                <button type="button" className="grid place-items-center size-8 rounded-md text-muted hover:text-ink hover:bg-surface-2 shrink-0" aria-label={`Remove chapter ${i + 1}`}
                  onClick={() => set({ chapters: ch.filter((_, j) => j !== i) })}><Trash2 className="size-4" /></button>
              </>
            ) : (
              <><span className="w-16 tabular-nums text-muted">{secondsText(c.start.value ?? 0)}</span><span>{c.title}</span></>
            )}
          </li>
        ))}
      </ol>
      {edit && (
        <Button className="mt-2" size="sm" variant="ghost" icon={<Plus className="size-4" />}
          onClick={() => set({ chapters: [...ch, { title: '', start: { value: (ch.at(-1)?.start.value ?? 0) + 60, unit: 'seconds', estimated: true, method: 'Planned start' } }] })}>
          Add a chapter
        </Button>
      )}
    </section>
  )
}

function SuggestionList({ title, items, edit, onChange }: { title: string; items: { title: string; why?: string | null }[]; edit: boolean; onChange: (v: { title: string; why?: string | null }[]) => void }) {
  return (
    <section>
      <h2 className="text-lg font-semibold mb-2">{title}</h2>
      {items.length === 0 && !edit && <p className="text-faint">None</p>}
      <ul className="space-y-1.5">
        {items.map((s, i) => (
          <li key={i} className={cn(edit && 'grid sm:grid-cols-[1fr_1.5fr_auto] gap-2')}>
            {edit ? (
              <>
                <Input aria-label={`${title} ${i + 1}`} value={s.title} maxLength={200} onChange={(e) => onChange(items.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)))} />
                <Input aria-label={`Why ${i + 1}`} value={s.why ?? ''} maxLength={500} placeholder="Why" onChange={(e) => onChange(items.map((x, j) => (j === i ? { ...x, why: e.target.value } : x)))} />
                <button type="button" className="grid place-items-center size-10 rounded-md text-muted hover:text-ink hover:bg-surface-2" aria-label={`Remove ${s.title || 'this one'}`}
                  onClick={() => onChange(items.filter((_, j) => j !== i))}><Trash2 className="size-4" /></button>
              </>
            ) : (
              <p><span className="font-medium">{s.title}</span>{s.why && <span className="text-muted">: {s.why}</span>}</p>
            )}
          </li>
        ))}
      </ul>
      {edit && <Button className="mt-2" size="sm" variant="ghost" icon={<Plus className="size-4" />} onClick={() => onChange([...items, { title: '', why: '' }])}>Add</Button>}
    </section>
  )
}

function Strategies({ rep, edit, set }: { rep: SeoReport; edit: boolean; set: (p: Partial<SeoReport>) => void }) {
  const change = (k: string, patch: Partial<Strategy>) => set({ strategies: { ...rep.strategies, [k]: { ...(rep.strategies[k] ?? {}), ...patch } } })
  return (
    <section aria-labelledby="st-h">
      <h2 id="st-h" className="text-lg font-semibold mb-2">Plans by video type</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        {STRATEGIES.map((k) => {
          const s = rep.strategies[k] ?? {}
          return (
            <div key={k} className="rounded-xl border border-line px-4 py-3 text-sm space-y-1.5">
              <h3 className="font-semibold">{STRATEGY_LABEL[k]}</h3>
              {(['titleIdea', 'angle', 'format', 'notes'] as const).map((f) => {
                const name = f === 'titleIdea' ? 'Title idea' : f[0].toUpperCase() + f.slice(1)
                if (!edit) return s[f] ? <p key={f}><span className="text-muted">{name}:</span> {s[f]}</p> : null
                const props = { 'aria-label': `${STRATEGY_LABEL[k]}: ${name.toLowerCase()}`, placeholder: name, value: s[f] ?? '' }
                return f === 'titleIdea' || f === 'format'
                  ? <Input key={f} {...props} maxLength={f === 'format' ? 300 : 200} onChange={(e) => change(k, { [f]: e.target.value })} />
                  : <Textarea key={f} {...props} rows={2} className="min-h-16" onChange={(e) => change(k, { [f]: e.target.value })} />
              })}
              {!edit && s.keywords && s.keywords.length > 0 && <p><span className="text-muted">Keywords:</span> {s.keywords.join(', ')}</p>}
              {!edit && !s.titleIdea && !s.angle && <p className="text-faint">No plan yet</p>}
            </div>
          )
        })}
      </div>
    </section>
  )
}

// ---- the research (read only) -------------------------------------------------------------------

function ResearchView({ res }: { res: Research }) {
  const [all, setAll] = useState(false)
  const kws = all ? res.keywords : res.keywords.slice(0, 15)
  return (
    <section aria-labelledby="research-h" className="space-y-8 border-t border-line pt-8">
      <div>
        <h2 id="research-h" className="text-xl font-semibold">The research</h2>
        <p className="text-sm text-muted">Kept as it was found. Every number shows where it came from, or “est.” for an estimate; hover for how.</p>
      </div>

      <div>
        <h3 className="font-semibold mb-1">Keywords</h3>
        <p className="text-sm text-muted mb-2">
          Suggested by the AI; the top ones were searched on YouTube. Opportunity = R × (wD·D + wC·(1 − C) + wG·G), each 0 to 1; without a Trends value D is left out and the score is partial.
          It’s a relative estimate, not a search volume.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-sm min-w-[46rem]">
            <thead><tr className="text-left text-muted">
              <th className="font-normal py-1 pr-2">Keyword</th><th className="font-normal pr-2">Kind</th><th className="font-normal pr-2" title="Relevance to the approved topic">R</th>
              <th className="font-normal pr-2" title="Relative demand from Google Trends">D</th><th className="font-normal pr-2" title="Competition among the top results">C</th>
              <th className="font-normal pr-2" title="Content gap among the top results">G</th><th className="font-normal">Opportunity</th>
            </tr></thead>
            <tbody>
              {kws.map((k) => (
                <tr key={k.language + k.text} className="border-t border-line">
                  <td className="py-1.5 pr-2"><span lang={k.language}>{k.text}</span>{k.language === 'ta' && <span className="text-faint"> · Tamil</span>}</td>
                  <td className="pr-2 text-muted">{KIND_LABEL[k.kind] ?? k.kind}</td>
                  <td className="pr-2"><MetricValue m={k.relevance} label={false} /></td>
                  <td className="pr-2">{k.demand ? <MetricValue m={k.demand} label={false} /> : <span className="text-faint" title="No Trends value">–</span>}</td>
                  <td className="pr-2">{k.competition ? <MetricValue m={k.competition} label={false} /> : <span className="text-faint">–</span>}</td>
                  <td className="pr-2">{k.gap ? <MetricValue m={k.gap} label={false} /> : <span className="text-faint">–</span>}</td>
                  <td>{k.score ? <><MetricValue m={k.score} /> {k.partial && <span className="text-xs text-muted">partial</span>}</> : <span className="text-faint">not searched</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {res.keywords.length > 15 && <button type="button" className="text-sm text-muted hover:text-ink mt-1" onClick={() => setAll((a) => !a)}>{all ? 'Show fewer' : `Show all ${res.keywords.length}`}</button>}
      </div>

      <div>
        <h3 className="font-semibold mb-1">Competitor videos</h3>
        {res.competitors.length === 0 ? <p className="text-sm text-muted">None looked up in this research.</p> : (
          <>
            <p className="text-sm text-muted mb-2">Public numbers only, from YouTube’s API. Their impressions, click-through and retention aren’t public.</p>
            <ol className="space-y-2">
              {res.competitors.slice(0, 12).map((c) => (
                <li key={c.videoId} className="rounded-xl border border-line px-4 py-2.5 text-sm">
                  <a href={c.url} target="_blank" rel="noopener noreferrer" className="font-medium hover:underline break-words">{c.title}</a>
                  <p className="text-muted">{c.channel}{c.publishedAt ? `, ${c.publishedAt.slice(0, 10)}` : ''}</p>
                  <p className="flex flex-wrap gap-x-4 gap-y-0.5 mt-0.5">
                    <MetricValue m={c.views} /> <MetricValue m={c.likes} /> <MetricValue m={c.comments} />
                    {c.duration?.value != null && <span title={metricLabel(c.duration)}>{secondsText(c.duration.value)} long</span>}
                  </p>
                  {c.covers.length > 0 && <p className="text-muted mt-0.5">Covers: {c.covers.join(', ')}</p>}
                </li>
              ))}
            </ol>
          </>
        )}
      </div>

      {res.coverage.length > 0 && (
        <div>
          <h3 className="font-semibold mb-1">Content gaps</h3>
          <ul className="text-sm space-y-0.5">
            {res.coverage.map((c) => <li key={c.subtopic}><span className="font-medium">{c.subtopic}</span>: <MetricValue m={c.competitorsCovering} /></li>)}
          </ul>
          {res.analysis?.contentGaps && res.analysis.contentGaps.length > 0 && (
            <ul className="list-disc pl-5 text-sm mt-2">{res.analysis.contentGaps.map((g) => <li key={g}>{g}</li>)}</ul>
          )}
          {res.analysis?.titleStrategies && <p className="text-sm mt-2"><span className="text-muted">How competitors title their videos:</span> {res.analysis.titleStrategies}</p>}
          {res.analysis?.source && <p className="text-xs text-muted mt-1">{res.analysis.source}</p>}
        </div>
      )}

      {res.trends && res.trends.length > 0 && (
        <div>
          <h3 className="font-semibold mb-1">Trends</h3>
          <ul className="text-sm space-y-0.5">{res.trends.map((t) => <li key={t.term}>{t.term}: <MetricValue m={t.recent} /> {t.direction && <span className="text-muted">({t.direction})</span>}</li>)}</ul>
        </div>
      )}

      <div>
        <h3 className="font-semibold mb-1">Sources</h3>
        <ul className="text-sm space-y-0.5">{res.sources.map((s, i) => <li key={i}><span className="font-medium">{s.name}</span>{s.detail && `: ${s.detail}`}{s.retrievedAt && <span className="text-muted"> ({s.retrievedAt.slice(0, 10)})</span>}</li>)}</ul>
        {res.budget && (
          <p className="text-sm text-muted mt-1">YouTube searches this run: <MetricValue m={res.budget.thisRun} label={false} />, with <MetricValue m={res.budget.usedBefore} label={false} /> already used that day of <MetricValue m={res.budget.dailySearches} label={false} />.</p>
        )}
      </div>
      <div>
        <h3 className="font-semibold mb-1">Limits of this research</h3>
        <ul className="list-disc pl-5 text-sm text-muted space-y-0.5">{res.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
      </div>
    </section>
  )
}
