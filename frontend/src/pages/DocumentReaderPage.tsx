import { ChevronLeft, Download, Pencil, RotateCcw, ScanText, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { DocumentDrop, Warning } from '../components/documents'
import { Button, Dialog, EmptyState, ErrorNotice, Field, Input, Spinner, StatusPill } from '../components/ui'
import {
  fetchDownloadUrl,
  isReading,
  langAttr,
  LANGUAGE_NAME,
  statusText,
  subjectNote,
  uploadVersion,
  useAfterUpload,
  useDeleteDocument,
  useDocument,
  useDocumentPages,
  useRenameDocument,
  useReprocess,
  type DocumentVersion,
} from '../lib/documents'
import { formatBytes, formatRelative } from '../lib/format'
import { toast } from '../stores/ui'

const PAGE_STEP = 20

/** /documents/:id?version=&page= — one document, read page by page, with its versions. */
export function DocumentReaderPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const detail = useDocument(id)
  const navigate = useNavigate()
  const [renameOpen, setRenameOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const remove = useDeleteDocument()
  const refresh = useAfterUpload()
  const send = useCallback(
    (file: File, onProgress: (f: number) => void, signal: AbortSignal) => uploadVersion(id, file, onProgress, signal),
    [id],
  )

  if (detail.isPending) return <Spinner label="Opening document" />
  if (detail.error) return <ErrorNotice error={detail.error} onRetry={() => detail.refetch()} />
  const { document: doc, versions, canEdit } = detail.data
  const readable = versions.filter((v) => v.status === 'READY')
  const shown = versions.find((v) => v.id === params.get('version')) ?? readable[0] ?? versions[0]
  const page = Math.max(1, Number(params.get('page')) || 1)
  const note = subjectNote(doc.subject)

  const show = (v: DocumentVersion, p = 1) => setParams({ version: v.id, page: String(p) }, { replace: false })

  return (
    <>
      <Link to={`/projects/${doc.projectId}/documents`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        Course documents
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div className="min-w-0 max-w-3xl">
          <h1 className="text-3xl sm:text-[2.15rem] font-semibold break-words">{doc.title}</h1>
          <p className="text-muted mt-1">
            {shown ? statusText(shown, true) : 'No file'}
            {shown?.language && shown.status === 'READY' && shown.language !== 'unknown' && `, ${LANGUAGE_NAME[shown.language]}`}
            {versions.length > 1 && `, version ${shown?.versionNo} of ${versions.length}`}
          </p>
          {note && (
            <div className="mt-2">
              <Warning>{note}</Warning>
            </div>
          )}
        </div>
        {canEdit && (
          <div className="flex gap-2">
            <Button icon={<Pencil className="size-4" />} onClick={() => setRenameOpen(true)}>
              Rename
            </Button>
            <Button variant="ghost" icon={<Trash2 className="size-4" />} onClick={() => setDeleteOpen(true)}>
              Delete
            </Button>
          </div>
        )}
      </header>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_20rem] items-start">
        <section aria-label="Pages" className="min-w-0">
          {shown ? <Pages version={shown} from={page} onPage={(p) => show(shown, p)} /> : <EmptyState title="No file" body="Upload a version to read it here." />}
        </section>

        <aside aria-labelledby="versions-heading" className="lg:sticky lg:top-6">
          <h2 id="versions-heading" className="text-lg font-semibold mb-3">
            Versions
          </h2>
          <ol className="space-y-3">
            {versions.map((v) => (
              <VersionItem key={v.id} v={v} current={v.id === shown?.id} canEdit={canEdit} onShow={() => show(v)} />
            ))}
          </ol>
          {canEdit && (
            <div className="mt-4">
              <DocumentDrop send={send} onDone={refresh} narrow multiple={false} label="Upload new version" />
            </div>
          )}
        </aside>
      </div>

      {renameOpen && <RenameDialog onClose={() => setRenameOpen(false)} id={doc.id} title={doc.title} />}
      <Dialog
        open={deleteOpen}
        title="Delete this document?"
        onClose={() => setDeleteOpen(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeleteOpen(false)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                remove.mutate(doc.id, {
                  onSuccess: () => {
                    toast.success(`Deleted ${doc.title}`)
                    navigate(`/projects/${doc.projectId}/documents`)
                  },
                  onError: (e) => toast.error(e.message),
                })
              }
            >
              Delete document
            </Button>
          </>
        }
      >
        <p className="text-muted">
          “{doc.title}” and all {versions.length} {versions.length === 1 ? 'version' : 'versions'} will be deleted, including the stored files. This can't be undone.
        </p>
      </Dialog>
    </>
  )
}

function Pages({ version, from, onPage }: { version: DocumentVersion; from: number; onPage: (p: number) => void }) {
  const ready = version.status === 'READY'
  const pages = useDocumentPages(ready ? version.id : undefined, from)

  useEffect(() => {
    if (pages.data && from > 1) document.getElementById(`page-${from}`)?.scrollIntoView({ block: 'start' })
  }, [pages.data, from])

  if (!ready) {
    return (
      <EmptyState
        title={isReading(version) ? 'Being read' : "This version couldn't be read"}
        body={statusText(version, true)}
      />
    )
  }
  if (pages.isPending) return <Spinner label="Loading pages" />
  if (pages.error) return <ErrorNotice error={pages.error} onRetry={() => pages.refetch()} />
  const data = pages.data
  const total = data.pageCount ?? version.pagesRead ?? 0
  if (data.pages.length === 0) return <EmptyState title="No text found" body="This version has no readable text. Try reading it again from the page images." />

  return (
    <div>
      <div className="space-y-6">
        {data.pages.map((p) => (
          <article key={p.pageNo} id={`page-${p.pageNo}`} className="rounded-2xl border border-line px-5 py-4 scroll-mt-6">
            <header className="flex flex-wrap items-baseline justify-between gap-2 mb-2">
              <h3 className="font-semibold">
                Page {p.pageNo}
                {total > 0 && <span className="text-faint font-normal"> of {total}</span>}
              </h3>
              {p.method === 'OCR' && (
                <span className="text-sm text-muted">
                  Read by OCR{p.ocrConfidence != null && `, ${Math.round(p.ocrConfidence)}% confident`}
                </span>
              )}
            </header>
            {p.text.trim() ? (
              <p lang={langAttr(version.language)} className="whitespace-pre-wrap leading-relaxed break-words">
                {p.text}
              </p>
            ) : (
              <p className="text-faint">No text on this page.</p>
            )}
          </article>
        ))}
      </div>
      <nav className="flex justify-between gap-3 mt-6" aria-label="More pages">
        <Button variant="secondary" disabled={from <= 1} onClick={() => onPage(Math.max(1, from - PAGE_STEP))}>
          Earlier pages
        </Button>
        <Button variant="secondary" disabled={!data.more} onClick={() => onPage(from + PAGE_STEP)}>
          Later pages
        </Button>
      </nav>
    </div>
  )
}

function VersionItem({ v, current, canEdit, onShow }: { v: DocumentVersion; current: boolean; canEdit: boolean; onShow: () => void }) {
  const reprocess = useReprocess()
  const [downloading, setDownloading] = useState(false)
  const again = (forceOcr: boolean) =>
    reprocess.mutate(
      { versionId: v.id, forceOcr },
      { onSuccess: () => toast.success('Reading it again'), onError: (e) => toast.error(e.message) },
    )

  return (
    <li className={current ? 'rounded-xl border border-marigold-500/60 px-4 py-3' : 'rounded-xl border border-line px-4 py-3'}>
      <div className="flex items-center justify-between gap-2">
        <button type="button" onClick={onShow} className="font-medium hover:underline text-left" aria-current={current || undefined}>
          Version {v.versionNo}
        </button>
        {v.status === 'FAILED' && <StatusPill tone="muted">Not read</StatusPill>}
        {isReading(v) && <StatusPill tone="progress">{v.status === 'PROCESSING' ? 'Reading' : 'Waiting'}</StatusPill>}
      </div>
      <p className="text-sm text-muted break-all">{v.originalFilename}</p>
      <p className="text-sm text-faint">
        {formatBytes(v.sizeBytes)}, {formatRelative(v.createdAt)}
        {v.uploadedBy && `, ${v.uploadedBy}`}
      </p>
      {v.status === 'FAILED' && <p className="text-sm text-rose-signal mt-1">{statusText(v, true)}</p>}
      <div className="flex flex-wrap gap-1 mt-2 -ml-2">
        <Button
          size="sm"
          variant="ghost"
          loading={downloading}
          icon={<Download className="size-4" />}
          onClick={async () => {
            setDownloading(true)
            try {
              const { url } = await fetchDownloadUrl(v.id)
              window.location.assign(url)
            } catch (e) {
              toast.error(e instanceof Error ? e.message : 'Download failed')
            } finally {
              setDownloading(false)
            }
          }}
        >
          Download
        </Button>
        {canEdit && !isReading(v) && (
          <>
            <Button size="sm" variant="ghost" icon={<RotateCcw className="size-4" />} loading={reprocess.isPending} onClick={() => again(false)}>
              Read again
            </Button>
            <Button
              size="sm"
              variant="ghost"
              icon={<ScanText className="size-4" />}
              title="For PDFs whose text comes out garbled, such as Tamil typed in older fonts like Bamini"
              loading={reprocess.isPending}
              onClick={() => again(true)}
            >
              Read with OCR
            </Button>
          </>
        )}
      </div>
    </li>
  )
}

/** Mounted only while open, so it starts from the current name each time. */
function RenameDialog({ onClose, id, title }: { onClose: () => void; id: string; title: string }) {
  const [value, setValue] = useState(title)
  const rename = useRenameDocument()
  const save = () =>
    rename.mutate(
      { id, title: value.trim() },
      {
        onSuccess: () => {
          toast.success('Renamed')
          onClose()
        },
        onError: (e) => toast.error(e.message),
      },
    )
  return (
    <Dialog
      open
      title="Rename document"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" loading={rename.isPending} disabled={!value.trim() || value.trim() === title} onClick={save}>
            Save name
          </Button>
        </>
      }
    >
      <Field label="Name">
        {(fid) => <Input id={fid} value={value} maxLength={200} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && value.trim() && save()} />}
      </Field>
    </Dialog>
  )
}
