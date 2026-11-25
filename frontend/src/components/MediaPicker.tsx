import { Search } from 'lucide-react'
import { useState } from 'react'
import { useDebounced } from '../lib/hooks'
import { useMedia } from '../lib/queries'
import type { MediaAsset, MediaKind } from '../lib/types'
import { MediaThumb, UploadZone } from './media'
import { Dialog, EmptyState, Input, Spinner } from './ui'

const EMPTY_NOUN: Record<MediaKind, string> = { IMAGE: 'image', VIDEO: 'video', AUDIO: 'audio' }

export function MediaPicker({
  open,
  kind,
  title,
  onPick,
  onClose,
  projectId,
}: {
  open: boolean
  /** One kind, or several (e.g. audio or video for subtitles). */
  kind: MediaKind | MediaKind[]
  title: string
  onPick: (m: MediaAsset) => void
  onClose: () => void
  /** Only this project's files, and uploads go into it. */
  projectId?: string
}) {
  const [search, setSearch] = useState('')
  const debounced = useDebounced(search)
  const kinds = Array.isArray(kind) ? kind : [kind]
  const media = useMedia({ kind: kinds.length === 1 ? kinds[0] : undefined, search: debounced, projectId })
  const items = (media.data?.items ?? []).filter((m) => kinds.includes(m.kind))
  const noun = kinds.map((k) => EMPTY_NOUN[k]).join(' or ')

  return (
    <Dialog open={open} title={title} onClose={onClose}>
      <div className="relative mb-4">
        <Search className="size-4 absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden />
        <Input aria-label="Search by name" placeholder="Search by name" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
      </div>
      <div className="max-h-[50vh] overflow-y-auto -mx-1 px-1">
        {media.isPending && <Spinner label="Loading" />}
        {media.data &&
          (items.length === 0 ? (
            <EmptyState title={`No ${noun} files yet`} body={kinds.length === 1 && kinds[0] === 'IMAGE' ? 'Upload one below. JPG, PNG and WebP work best.' : 'Upload one below.'} />
          ) : (
            <ul className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {items.map((m) => (
                <li key={m.id}>
                  <button onClick={() => onPick(m)} className="group block w-full text-left rounded-lg">
                    <MediaThumb asset={m} className="aspect-video rounded-lg group-hover:ring-2 group-hover:ring-marigold-500" />
                    <p className="mt-1 truncate text-xs text-muted">{m.filename}</p>
                  </button>
                </li>
              ))}
            </ul>
          ))}
      </div>
      <div className="mt-4">
        <UploadZone compact projectId={projectId} />
      </div>
    </Dialog>
  )
}
