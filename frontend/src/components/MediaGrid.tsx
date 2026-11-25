import { Download, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { api } from '../lib/api'
import { formatBytes, formatRelative, formatResolution } from '../lib/format'
import { useDeleteMedia, useProjects, useUpdateMedia } from '../lib/queries'
import type { MediaAsset } from '../lib/types'
import { toast } from '../stores/ui'
import { KIND_LABEL, MediaThumb } from './media'
import { Button, Dialog, Field, Input, Select } from './ui'

export function MediaGrid({ items }: { items: MediaAsset[] }) {
  const [selected, setSelected] = useState<MediaAsset>()
  return (
    <>
      <ul className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-4 gap-x-4 gap-y-6">
        {items.map((m) => (
          <li key={m.id}>
            <button onClick={() => setSelected(m)} className="group block w-full text-left rounded-xl">
              <MediaThumb asset={m} className="aspect-video rounded-xl ring-1 ring-inset ring-black/5 group-hover:ring-2 group-hover:ring-marigold-500 transition-shadow" />
              <p className="mt-2 truncate text-sm font-medium">{m.filename}</p>
              <p className="text-xs text-faint">
                {KIND_LABEL[m.kind]}
                {formatResolution(m.width, m.height) && `, ${formatResolution(m.width, m.height)}`}, {formatBytes(m.sizeBytes)}
              </p>
            </button>
          </li>
        ))}
      </ul>
      {selected && <MediaDetailDialog asset={selected} onClose={() => setSelected(undefined)} />}
    </>
  )
}

function MediaDetailDialog({ asset, onClose }: { asset: MediaAsset; onClose: () => void }) {
  const [filename, setFilename] = useState(asset.filename)
  const [projectId, setProjectId] = useState(asset.projectId ?? '')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const projects = useProjects({ page: 0 })
  const update = useUpdateMedia()
  const remove = useDeleteMedia()
  const dirty = filename.trim() !== asset.filename || projectId !== (asset.projectId ?? '')

  async function download() {
    try {
      const { url } = await api.get<{ url: string }>(`/media/${asset.id}/download-url`)
      window.location.assign(url)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not start the download')
    }
  }

  function save() {
    update.mutate(
      {
        id: asset.id,
        ...(filename.trim() !== asset.filename ? { filename: filename.trim() } : {}),
        ...(projectId ? { projectId } : { unassign: true }),
      },
      {
        onSuccess: () => {
          toast.success('Changes saved')
          onClose()
        },
        onError: (e) => toast.error(e.message),
      },
    )
  }

  function doDelete() {
    remove.mutate(asset.id, {
      onSuccess: () => {
        toast.success(`Deleted ${asset.filename}`)
        onClose()
      },
      onError: (e) => toast.error(e.message),
    })
  }

  return (
    <Dialog open title={asset.filename} onClose={onClose}>
      <div className="-mx-6 mb-5 bg-[#0d1020]">
        {asset.kind === 'VIDEO' && (
          <video src={asset.url} poster={asset.posterUrl} controls preload="metadata" className="w-full max-h-[50vh]" />
        )}
        {asset.kind === 'IMAGE' && <img src={asset.url} alt={asset.filename} className="w-full max-h-[50vh] object-contain" />}
        {asset.kind === 'AUDIO' && (
          <div className="p-6">
            <audio src={asset.url} controls preload="metadata" className="w-full" />
          </div>
        )}
      </div>

      <dl className="grid grid-cols-2 gap-y-1 text-sm mb-5">
        <dt className="text-faint">Size</dt>
        <dd className="tabular">{formatBytes(asset.sizeBytes)}</dd>
        {asset.width && (
          <>
            <dt className="text-faint">Dimensions</dt>
            <dd className="tabular">
              {asset.width} × {asset.height}
            </dd>
          </>
        )}
        <dt className="text-faint">Added</dt>
        <dd>{formatRelative(asset.createdAt)}</dd>
      </dl>

      <div className="flex flex-col gap-4">
        <Field label="Name">{(id) => <Input id={id} value={filename} onChange={(e) => setFilename(e.target.value)} maxLength={255} />}</Field>
        <Field label="Project">
          {(id) => (
            <Select id={id} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">Not in a project</option>
              {projects.data?.items.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>

      <div className="mt-6 flex flex-wrap items-center gap-2">
        {confirmDelete ? (
          <>
            <span className="text-sm text-rose-signal mr-auto">Delete this file permanently?</span>
            <Button size="sm" onClick={() => setConfirmDelete(false)}>
              Keep
            </Button>
            <Button size="sm" variant="danger" loading={remove.isPending} onClick={doDelete}>
              Delete file
            </Button>
          </>
        ) : (
          <>
            <Button variant="ghost" size="sm" icon={<Trash2 className="size-4" />} onClick={() => setConfirmDelete(true)} className="mr-auto">
              Delete
            </Button>
            <Button size="sm" icon={<Download className="size-4" />} onClick={download}>
              Download
            </Button>
            <Button size="sm" variant="primary" disabled={!dirty || !filename.trim()} loading={update.isPending} onClick={save}>
              Save changes
            </Button>
          </>
        )}
      </div>
    </Dialog>
  )
}
