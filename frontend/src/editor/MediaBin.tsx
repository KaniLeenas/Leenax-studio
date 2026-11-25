import { Captions, Plus, Search, Type } from 'lucide-react'
import { useState } from 'react'
import { MediaThumb, UploadZone } from '../components/media'
import { Tabs } from '../components/studio'
import { Input, Select, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { useDebounced } from '../lib/hooks'
import { useMedia } from '../lib/queries'
import { studioKeys, useTracks } from '../lib/studioQueries'
import { api } from '../lib/api'
import { useQueryClient } from '@tanstack/react-query'
import type { MediaAsset, MediaKind, SubtitleTrack } from '../lib/types'
import { TEXT_PRESETS } from '../remotion/timeline'
import { MEDIA_DRAG_TYPE } from './TimelinePanel'

type Tab = 'media' | 'text' | 'captions'

export function MediaBin({
  onAdd,
  onAddText,
  onAddCaptions,
  videoMedia,
}: {
  onAdd: (m: MediaAsset) => void
  onAddText: (presetId: string) => void
  onAddCaptions: (track: SubtitleTrack) => void
  /** Video/audio files already on the timeline (their subtitles can be added). */
  videoMedia: MediaAsset[]
}) {
  const [tab, setTab] = useState<Tab>('media')
  return (
    <div className="flex flex-col min-h-0 h-full">
      <div className="px-2 pt-1">
        <Tabs
          tabs={[
            { id: 'media', label: 'Media' },
            { id: 'text', label: 'Text' },
            { id: 'captions', label: 'Captions' },
          ]}
          value={tab}
          onChange={setTab}
          label="Add to timeline"
        />
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto px-3 pb-3 -mt-3">
        {tab === 'media' && <MediaTab onAdd={onAdd} />}
        {tab === 'text' && (
          <ul className="flex flex-col gap-2">
            {TEXT_PRESETS.map((p) => (
              <li key={p.id}>
                <button type="button" onClick={() => onAddText(p.id)} className="w-full flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-left hover:bg-surface-2">
                  <Type className="size-4 text-faint" aria-hidden />
                  <span className="flex-1">{p.label}</span>
                  <Plus className="size-4 text-faint" aria-hidden />
                </button>
              </li>
            ))}
            <li className="text-xs text-faint px-1">Added at the playhead. Edit the words and style on the right.</li>
          </ul>
        )}
        {tab === 'captions' && <CaptionsTab videoMedia={videoMedia} onAdd={onAddCaptions} />}
      </div>
    </div>
  )
}

function MediaTab({ onAdd }: { onAdd: (m: MediaAsset) => void }) {
  const [kind, setKind] = useState<MediaKind | ''>('')
  const [search, setSearch] = useState('')
  const q = useMedia({ kind: kind || undefined, search: useDebounced(search) })
  return (
    <div className="flex flex-col gap-2">
      <div className="flex gap-2">
        <div className="relative flex-1">
          <Search className="size-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-faint" aria-hidden />
          <Input aria-label="Search media" placeholder="Search" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-8 h-9" />
        </div>
        <Select aria-label="Type" value={kind} onChange={(e) => setKind(e.target.value as MediaKind | '')} className="h-9 w-24">
          <option value="">All</option>
          <option value="VIDEO">Video</option>
          <option value="IMAGE">Image</option>
          <option value="AUDIO">Audio</option>
        </Select>
      </div>
      {q.isPending && <Spinner label="Loading" />}
      <ul className="grid grid-cols-2 gap-2">
        {q.data?.items.map((m) => (
          <li key={m.id}>
            <div
              draggable
              onDragStart={(e) => {
                e.dataTransfer.setData(MEDIA_DRAG_TYPE, m.id)
                e.dataTransfer.setData('text/plain', m.filename)
                e.dataTransfer.effectAllowed = 'copy'
              }}
              className="group rounded-lg border border-line bg-surface overflow-hidden cursor-grab"
              title="Drag onto the timeline"
            >
              <MediaThumb asset={m} className="aspect-video" />
              <div className="flex items-center gap-1 px-1.5 py-1">
                <span className="truncate text-xs flex-1">{m.filename}</span>
                <button
                  type="button"
                  aria-label={`Add ${m.filename} at the playhead`}
                  onClick={() => onAdd(m)}
                  className="rounded p-0.5 text-faint hover:text-ink hover:bg-surface-2"
                >
                  <Plus className="size-4" />
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>
      {q.data?.items.length === 0 && <p className="text-sm text-muted">Nothing here yet. Upload below.</p>}
      <UploadZone compact />
    </div>
  )
}

function CaptionsTab({ videoMedia, onAdd }: { videoMedia: MediaAsset[]; onAdd: (t: SubtitleTrack) => void }) {
  const [mediaId, setMediaId] = useState(videoMedia[0]?.id ?? '')
  const tracks = useTracks(mediaId || undefined)
  const qc = useQueryClient()
  const [loading, setLoading] = useState<string>()
  if (videoMedia.length === 0) {
    return <p className="text-sm text-muted">Add a video or audio file to the timeline first. Then its subtitles (made in Subtitles) can be added as captions.</p>
  }
  return (
    <div className="flex flex-col gap-2">
      <Select aria-label="File" value={mediaId} onChange={(e) => setMediaId(e.target.value)} className="h-9">
        {videoMedia.map((m) => (
          <option key={m.id} value={m.id}>
            {m.filename}
          </option>
        ))}
      </Select>
      {tracks.isPending && <Spinner label="Loading" />}
      {tracks.data?.length === 0 && <p className="text-sm text-muted">No subtitles for this file yet. Make them in Subtitles.</p>}
      <ul className="flex flex-col gap-1">
        {tracks.data?.map((t) => (
          <li key={t.id}>
            <button
              type="button"
              disabled={!!loading}
              onClick={async () => {
                setLoading(t.id)
                try {
                  const full = await qc.fetchQuery({ queryKey: studioKeys.track(t.id), queryFn: () => api.get<SubtitleTrack>(`/subtitles/${t.id}`) })
                  onAdd(full)
                } finally {
                  setLoading(undefined)
                }
              }}
              className={cn('w-full flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-left hover:bg-surface-2 text-sm', loading === t.id && 'opacity-60')}
            >
              <Captions className="size-4 text-faint" aria-hidden />
              <span className="flex-1 truncate">{t.label}</span>
              <span className="text-xs text-faint">{t.cueCount}</span>
            </button>
          </li>
        ))}
      </ul>
      <p className="text-xs text-faint">Captions are placed where the file starts on the timeline, on a new text track you can restyle.</p>
    </div>
  )
}
