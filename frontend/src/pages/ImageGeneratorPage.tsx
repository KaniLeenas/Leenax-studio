import { Eraser, Frame, Scaling, Trash2, Wand2 } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { AccountSelect, Card, ConnectAccount, MediaField, Panel, Tabs, useDefaultAccount, useUsableAccounts } from '../components/studio'
import { MediaThumb } from '../components/media'
import { Button, ErrorNotice, Field, Input, Select, Spinner, Textarea } from '../components/ui'
import { useModels } from '../lib/aiQueries'
import { cn } from '../lib/cn'
import { useImageTool, useImageTools, useGenerateImages } from '../lib/studioQueries'
import type { MediaAsset, OptionItem } from '../lib/types'
import { toast } from '../stores/ui'

type TabId = 'generate' | 'tools'

export function ImageGeneratorPage() {
  const [tab, setTab] = useState<TabId>('generate')
  const [results, setResults] = useState<MediaAsset[]>([])
  const [toolImage, setToolImage] = useState<MediaAsset>()

  return (
    <>
      <header className="mb-6">
        <h1 className="text-3xl sm:text-[2.15rem] font-semibold">AI image generator</h1>
        <p className="text-muted mt-1 max-w-2xl">
          Scene images, thumbnail backgrounds and social graphics with your OpenAI or Google account. Then cut out backgrounds or resize for each platform.
        </p>
      </header>
      <Tabs
        tabs={[
          { id: 'generate', label: 'Create images' },
          { id: 'tools', label: 'Background & resize' },
        ]}
        value={tab}
        onChange={setTab}
        label="Image tools"
      />
      <Panel id={tab}>
        {tab === 'generate' && (
          <GenerateTab
            results={results}
            onResults={(r) => setResults((x) => [...r, ...x].slice(0, 24))}
            onEdit={(m) => {
              setToolImage(m)
              setTab('tools')
            }}
          />
        )}
        {tab === 'tools' && <ToolsTab image={toolImage} onImage={setToolImage} />}
      </Panel>
    </>
  )
}

const PROMPT_STARTERS = [
  'Bold YouTube thumbnail background: steaming plate of Chettinad chicken on a banana leaf, dramatic side light, empty space on the left for text',
  'Minimal Instagram post background, soft gradient from marigold to deep blue, subtle kolam pattern, no text',
  'Cinematic wide shot of Madurai Meenakshi temple towers at sunrise, warm haze, photorealistic',
]

function GenerateTab({ results, onResults, onEdit }: { results: MediaAsset[]; onResults: (m: MediaAsset[]) => void; onEdit: (m: MediaAsset) => void }) {
  const accounts = useUsableAccounts('IMAGE')
  const models = useModels()
  const generate = useGenerateImages()
  const [accountId, setAccountId] = useState('')
  const [modelCode, setModelCode] = useState('')
  const [prompt, setPrompt] = useState('')
  const [size, setSize] = useState('')
  const [ratio, setRatio] = useState('')
  const [imageSize, setImageSize] = useState('')
  const [quality, setQuality] = useState('')
  const [count, setCount] = useState(1)
  const [transparent, setTransparent] = useState(false)
  const [refs, setRefs] = useState<MediaAsset[]>([])
  useDefaultAccount(accounts.usable, accountId, setAccountId)

  const account = accounts.usable.find((a) => a.id === accountId)
  const imageModels = useMemo(
    () => (models.data ?? []).filter((m) => m.capability === 'IMAGE' && m.enabled && m.provider === account?.provider),
    [models.data, account?.provider],
  )
  const model = imageModels.find((m) => m.modelCode === modelCode)
  const o = model?.options
  const ratios: OptionItem[] = Array.isArray(o?.aspectRatios) ? (o.aspectRatios as OptionItem[]) : []

  useEffect(() => {
    if (imageModels.length && !imageModels.some((m) => m.modelCode === modelCode)) setModelCode(imageModels[0].modelCode)
  }, [imageModels, modelCode])

  useEffect(() => {
    setSize(o?.sizes?.[0]?.value ?? '')
    setRatio(ratios[0]?.value ?? '')
    setImageSize(o?.imageSizes?.[0]?.value ?? '')
    setQuality(o?.qualities?.[0]?.value ?? '')
    setCount((c) => Math.min(c, o?.maxCount ?? 1))
    if (!o?.transparent) setTransparent(false)
    if (!o?.references) setRefs([])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelCode])

  if (accounts.isPending || models.isPending) return <Spinner label="Loading your accounts" />
  if (accounts.usable.length === 0) {
    return <ConnectAccount title="Connect an image account first" body="Add an OpenAI or Google Gemini API key. Images are billed by that provider to your own account." />
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!model || !prompt.trim()) return
    generate.mutate(
      {
        accountId,
        model: model.modelCode,
        prompt: prompt.trim(),
        size: o?.sizes ? size : undefined,
        aspectRatio: ratios.length ? ratio : undefined,
        imageSize: o?.imageSizes ? imageSize : undefined,
        quality: o?.qualities ? quality : undefined,
        count,
        transparent,
        references: refs.map((r) => r.id),
      },
      {
        onSuccess: (r) => {
          onResults(r.images)
          toast.success(r.images.length > 1 ? `${r.images.length} images saved` : 'Image saved to your media library')
        },
        onError: (err) => toast.error(err.message),
      },
    )
  }

  return (
    <div className="grid xl:grid-cols-[minmax(0,26rem)_minmax(0,1fr)] gap-8 items-start">
      <form onSubmit={submit} className="xl:sticky xl:top-6">
        <Card label="Image settings">
          <Field label="Account">{(id) => <AccountSelect id={id} accounts={accounts.usable} value={accountId} onChange={setAccountId} />}</Field>
          {imageModels.length === 0 ? (
            <p className="text-sm text-muted">No image models are switched on for this provider.</p>
          ) : (
            <Field label="Model" hint={model?.notes}>
              {(id, d) => (
                <Select id={id} aria-describedby={d} value={modelCode} onChange={(e) => setModelCode(e.target.value)}>
                  {imageModels.map((m) => (
                    <option key={m.id} value={m.modelCode}>
                      {m.displayName}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          )}
          <Field label="Describe the image" hint="Say what goes where, the light and the style. Leave room for text if it’s a thumbnail background.">
            {(id, d) => <Textarea id={id} aria-describedby={d} rows={5} maxLength={4000} value={prompt} onChange={(e) => setPrompt(e.target.value)} />}
          </Field>
          {!prompt && (
            <div className="flex flex-col gap-1 -mt-2">
              {PROMPT_STARTERS.map((p) => (
                <button key={p} type="button" onClick={() => setPrompt(p)} className="text-left text-xs text-muted hover:text-ink line-clamp-1">
                  “{p}”
                </button>
              ))}
            </div>
          )}
          <div className="grid grid-cols-2 gap-4">
            {o?.sizes && (
              <Field label="Size">
                {(id) => (
                  <Select id={id} value={size} onChange={(e) => setSize(e.target.value)}>
                    {o.sizes!.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
            {ratios.length > 0 && (
              <Field label="Shape">
                {(id) => (
                  <Select id={id} value={ratio} onChange={(e) => setRatio(e.target.value)}>
                    {ratios.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
            {o?.imageSizes && (
              <Field label="Resolution">
                {(id) => (
                  <Select id={id} value={imageSize} onChange={(e) => setImageSize(e.target.value)}>
                    {o.imageSizes!.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
            {o?.qualities && (
              <Field label="Quality">
                {(id) => (
                  <Select id={id} value={quality} onChange={(e) => setQuality(e.target.value)}>
                    {o.qualities!.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
            <Field label="How many">
              {(id) => (
                <Select id={id} value={count} onChange={(e) => setCount(Number(e.target.value))}>
                  {Array.from({ length: o?.maxCount ?? 1 }, (_, i) => i + 1).map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>
          {o?.transparent && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={transparent} onChange={(e) => setTransparent(e.target.checked)} className="accent-marigold-500" />
              Transparent background (PNG cut-out)
            </label>
          )}
          {o?.references && (
            <div className="flex flex-col gap-2">
              <span className="text-sm font-medium">Reference images</span>
              <p className="text-xs text-faint -mt-1">Optional, up to 4. Use them for a face, product or style to match, or an image to edit.</p>
              {refs.length > 0 && (
                <ul className="flex gap-2 flex-wrap">
                  {refs.map((r) => (
                    <li key={r.id} className="relative">
                      <MediaThumb asset={r} className="size-16 rounded-lg" />
                      <button
                        type="button"
                        aria-label={`Remove ${r.filename}`}
                        onClick={() => setRefs((x) => x.filter((y) => y.id !== r.id))}
                        className="absolute -top-1.5 -right-1.5 rounded-full bg-surface border border-line p-0.5"
                      >
                        <Trash2 className="size-3" />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {refs.length < 4 && (
                <MediaField label="" kind="IMAGE" value={undefined} onChange={(m) => m && !refs.some((r) => r.id === m.id) && setRefs((x) => [...x, m])} pickerTitle="Pick a reference image" />
              )}
            </div>
          )}
          <Button type="submit" variant="primary" disabled={!model || !prompt.trim()} loading={generate.isPending} icon={<Wand2 className="size-4" />}>
            Create {count > 1 ? `${count} images` : 'image'}
          </Button>
          {generate.isPending && <p className="text-sm text-muted" role="status">Images usually take 10–60 seconds.</p>}
        </Card>
      </form>

      <section aria-label="Results" className="flex flex-col gap-4">
        {generate.error && <ErrorNotice error={generate.error} />}
        {results.length === 0 && !generate.isPending && (
          <div className="rounded-2xl border border-dashed border-line px-6 py-16 text-center text-muted">Your images appear here and in the media library.</div>
        )}
        <ul className="grid sm:grid-cols-2 gap-4">
          {generate.isPending &&
            Array.from({ length: count }, (_, i) => <li key={`p${i}`} className="aspect-video rounded-xl bg-surface-2 animate-pulse" aria-hidden />)}
          {results.map((m) => (
            <li key={m.id} className="rounded-xl border border-line bg-surface overflow-hidden">
              <a href={m.url} target="_blank" rel="noreferrer">
                <img src={m.url} alt={m.filename} className="w-full aspect-video object-contain bg-[repeating-conic-gradient(var(--surface-2)_0_25%,transparent_0_50%)] bg-[length:16px_16px]" />
              </a>
              <div className="p-3 flex items-center gap-2">
                <span className="text-sm truncate flex-1">{m.filename}</span>
                <Button size="sm" variant="ghost" onClick={() => onEdit(m)} icon={<Eraser className="size-4" />}>
                  Edit
                </Button>
                <Link to={`/create/thumbnails?background=${m.id}`} className="inline-flex h-8 items-center gap-1.5 px-3 rounded-lg text-sm text-muted hover:text-ink hover:bg-surface-2">
                  <Frame className="size-4" aria-hidden /> Thumbnail
                </Link>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

function ToolsTab({ image, onImage }: { image?: MediaAsset; onImage: (m?: MediaAsset) => void }) {
  const tools = useImageTools()
  const run = useImageTool()
  const [preset, setPreset] = useState('youtube-thumbnail')
  const [custom, setCustom] = useState({ width: 1280, height: 720 })
  const [fit, setFit] = useState<'COVER' | 'CONTAIN'>('COVER')
  const [background, setBackground] = useState('#000000')
  const [transparentPad, setTransparentPad] = useState(false)
  const [format, setFormat] = useState<'png' | 'jpg'>('jpg')
  const [results, setResults] = useState<MediaAsset[]>([])

  const p = tools.data?.presets.find((x) => x.id === preset)
  const width = preset === 'custom' ? custom.width : (p?.width ?? 1280)
  const height = preset === 'custom' ? custom.height : (p?.height ?? 720)

  function done(r: { images: MediaAsset[] }, msg: string) {
    setResults((x) => [...r.images, ...x])
    toast.success(msg)
  }

  return (
    <div className="grid xl:grid-cols-[minmax(0,26rem)_minmax(0,1fr)] gap-8 items-start">
      <div className="flex flex-col gap-5">
        <Card>
          <MediaField label="Image" kind="IMAGE" value={image} onChange={onImage} />
        </Card>
        <Card label="Remove background">
          <h2 className="font-semibold flex items-center gap-2">
            <Eraser className="size-4" aria-hidden /> Remove background
          </h2>
          <p className="text-sm text-muted -mt-3">Cuts out the person or object as a transparent PNG. Runs on your own server.</p>
          {tools.data && !tools.data.backgroundRemoval && (
            <p className="text-sm text-rose-signal">The background removal service isn’t running. Start the imagetools service (see README).</p>
          )}
          <Button
            variant="primary"
            disabled={!image || !tools.data?.backgroundRemoval}
            loading={run.isPending && run.variables?.tool === 'remove-background'}
            onClick={() => image && run.mutate({ tool: 'remove-background', mediaId: image.id }, { onSuccess: (r) => done(r, 'Cut-out saved'), onError: (e) => toast.error(e.message) })}
            className="self-start"
          >
            Remove background
          </Button>
        </Card>
        <Card label="Resize">
          <h2 className="font-semibold flex items-center gap-2">
            <Scaling className="size-4" aria-hidden /> Resize for a platform
          </h2>
          <Field label="Format">
            {(id) => (
              <Select id={id} value={preset} onChange={(e) => setPreset(e.target.value)}>
                {(tools.data?.presets ?? []).map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.label} · {x.width}×{x.height}
                  </option>
                ))}
                <option value="custom">Custom size</option>
              </Select>
            )}
          </Field>
          {preset === 'custom' && (
            <div className="grid grid-cols-2 gap-3">
              <Field label="Width">{(id) => <Input id={id} type="number" min={64} max={4096} value={custom.width} onChange={(e) => setCustom((c) => ({ ...c, width: Number(e.target.value) }))} />}</Field>
              <Field label="Height">{(id) => <Input id={id} type="number" min={64} max={4096} value={custom.height} onChange={(e) => setCustom((c) => ({ ...c, height: Number(e.target.value) }))} />}</Field>
            </div>
          )}
          <div role="radiogroup" aria-label="Fit" className="grid grid-cols-2 gap-2">
            {(
              [
                ['COVER', 'Fill and crop', 'No empty space; edges may be cut'],
                ['CONTAIN', 'Fit inside', 'Whole image; bars fill the rest'],
              ] as const
            ).map(([v, l, b]) => (
              <button
                key={v}
                type="button"
                role="radio"
                aria-checked={fit === v}
                onClick={() => setFit(v)}
                className={cn('rounded-xl border p-3 text-left', fit === v ? 'border-marigold-500 bg-marigold-500/10' : 'border-line hover:bg-surface-2')}
              >
                <span className="block text-sm font-medium">{l}</span>
                <span className="block text-xs text-muted">{b}</span>
              </button>
            ))}
          </div>
          {fit === 'CONTAIN' && (
            <div className="flex items-center gap-3">
              <label className="flex items-center gap-2 text-sm">
                Bar colour
                <input type="color" value={background} onChange={(e) => setBackground(e.target.value)} disabled={transparentPad} className="h-8 w-12 rounded border border-line" />
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={transparentPad}
                  onChange={(e) => {
                    setTransparentPad(e.target.checked)
                    if (e.target.checked) setFormat('png')
                  }}
                  className="accent-marigold-500"
                />
                Transparent
              </label>
            </div>
          )}
          <Field label="Save as">
            {(id) => (
              <Select id={id} value={format} onChange={(e) => setFormat(e.target.value as 'png' | 'jpg')}>
                <option value="jpg">JPG (smaller, for photos)</option>
                <option value="png">PNG (sharp text, transparency)</option>
              </Select>
            )}
          </Field>
          <Button
            variant="primary"
            disabled={!image}
            loading={run.isPending && run.variables?.tool === 'resize'}
            onClick={() =>
              image &&
              run.mutate(
                { tool: 'resize', mediaId: image.id, width, height, fit, background: transparentPad ? undefined : background, format: transparentPad ? 'png' : format },
                { onSuccess: (r) => done(r, `Saved at ${width}×${height}`), onError: (e) => toast.error(e.message) },
              )
            }
            className="self-start"
          >
            Resize to {width}×{height}
          </Button>
        </Card>
      </div>
      <section aria-label="Results" className="flex flex-col gap-4">
        {run.error && <ErrorNotice error={run.error} />}
        {image && results.length === 0 && <img src={image.url} alt={image.filename} className="rounded-xl max-h-[70vh] w-auto self-start" />}
        <ul className="grid sm:grid-cols-2 gap-4">
          {results.map((m) => (
            <li key={m.id} className="rounded-xl border border-line bg-surface overflow-hidden">
              <img src={m.url} alt={m.filename} className="w-full object-contain max-h-80 bg-[repeating-conic-gradient(var(--surface-2)_0_25%,transparent_0_50%)] bg-[length:16px_16px]" />
              <p className="p-3 text-sm truncate">{m.filename}</p>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
