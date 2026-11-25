import { Search } from 'lucide-react'
import { useState } from 'react'
import { MediaGrid } from '../components/MediaGrid'
import { UploadZone } from '../components/media'
import { Button, EmptyState, ErrorNotice, Input, PageHeader, Spinner } from '../components/ui'
import { cn } from '../lib/cn'
import { useMedia } from '../lib/queries'
import type { MediaKind } from '../lib/types'
import { useDebounced } from '../lib/hooks'

const KINDS: { label: string; value?: MediaKind }[] = [
  { label: 'All' },
  { label: 'Video', value: 'VIDEO' },
  { label: 'Images', value: 'IMAGE' },
  { label: 'Audio', value: 'AUDIO' },
]

export function MediaLibraryPage() {
  const [kind, setKind] = useState<MediaKind>()
  const [unassigned, setUnassigned] = useState(false)
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const debounced = useDebounced(search)
  const media = useMedia({ kind, unassigned: unassigned || undefined, search: debounced, page })


  return (
    <>
      <PageHeader title="Media library" description="Everything you've uploaded, stored privately. Links to files expire after 30 minutes." />

      <section className="mb-10">
        <UploadZone />
      </section>

      <div className="flex flex-wrap items-center gap-3 mb-6">
        <div role="tablist" aria-label="Filter by type" className="flex gap-1 rounded-xl bg-surface-2 p-1">
          {KINDS.map((k) => (
            <button
              key={k.label}
              role="tab"
              aria-selected={kind === k.value}
              onClick={() => {
                setKind(k.value)
                setPage(0)
              }}
              className={cn('px-3 h-8 rounded-lg text-sm', kind === k.value ? 'bg-surface text-ink font-medium shadow-sm' : 'text-muted hover:text-ink')}
            >
              {k.label}
            </button>
          ))}
        </div>
        <label className="flex items-center gap-2 text-sm text-muted cursor-pointer">
          <input type="checkbox" checked={unassigned} onChange={(e) => {
              setUnassigned(e.target.checked)
              setPage(0)
            }} className="accent-[var(--color-marigold-500)] size-4" />
          Only files not in a project
        </label>
        <div className="relative ml-auto w-full sm:w-64">
          <Search className="size-4 absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden />
          <Input aria-label="Search files" placeholder="Search by name" value={search} onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }} className="pl-9" />
        </div>
      </div>

      {media.isPending && <Spinner label="Loading media" />}
      {media.error && <ErrorNotice error={media.error} onRetry={() => media.refetch()} />}
      {media.data &&
        (media.data.items.length === 0 ? (
          kind || unassigned || debounced ? (
            <EmptyState title="No matching files" body="Try another type or search." />
          ) : (
            <EmptyState title="Your library is empty" body="Drop files into the box above to upload them." />
          )
        ) : (
          <>
            <MediaGrid items={media.data.items} />
            {media.data.totalPages > 1 && (
              <div className="flex items-center justify-center gap-3 mt-8">
                <Button size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <span className="text-sm text-muted tabular">
                  Page {page + 1} of {media.data.totalPages}
                </span>
                <Button size="sm" disabled={page + 1 >= media.data.totalPages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            )}
          </>
        ))}
    </>
  )
}
