import { BookOpen, ChevronLeft, FileText, Search } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { DocumentDrop, Marked, Warning } from '../components/documents'
import { EmptyState, ErrorNotice, Input, PageHeader, Spinner, StatusPill } from '../components/ui'
import {
  isReading,
  LANGUAGE_NAME,
  statusText,
  subjectNote,
  uploadDocument,
  useAfterUpload,
  useDocumentSearch,
  useDocuments,
  type CourseDocument,
  type SearchResponse,
} from '../lib/documents'
import { formatRelative } from '../lib/format'
import { useProject, useProjects } from '../lib/queries'

/** /projects/:id/documents — a subject's course documents, with search across all of them. */
export function DocumentsPage() {
  const { id = '' } = useParams()
  const project = useProject(id)
  const docs = useDocuments(id)
  const [query, setQuery] = useState('')
  const debounced = useDebounced(query, 300)
  const search = useDocumentSearch(id, debounced)
  const refresh = useAfterUpload()
  const send = useCallback(
    (file: File, onProgress: (f: number) => void, signal: AbortSignal) => uploadDocument(id, file, onProgress, signal),
    [id],
  )

  if (project.isPending || docs.isPending) return <Spinner label="Loading documents" />
  if (project.error || docs.error) return <ErrorNotice error={project.error ?? docs.error} onRetry={() => docs.refetch()} />
  const p = project.data
  const list = docs.data
  const searching = debounced.trim().length >= 2

  return (
    <>
      <Link to={`/projects/${p.id}`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-ink mb-4">
        <ChevronLeft className="size-4" aria-hidden />
        {p.name}
      </Link>
      <PageHeader
        title="Course documents"
        description={[p.subject?.moduleCode, p.subject?.subjectName].filter(Boolean).join(', ') || p.name}
      />

      {!list.serviceReady && (
        <div className="mb-6 rounded-xl border border-line bg-surface-2 px-4 py-3 text-sm">
          The document reader isn't running on this server, so new files are stored but not read yet. Start the{' '}
          <code>docservice</code> container (see the README), and waiting files are read automatically.
        </div>
      )}

      {list.canEdit && (
        <section className="mb-8" aria-label="Add documents">
          <DocumentDrop send={send} onDone={refresh} compact={list.documents.length > 0} />
        </section>
      )}

      {list.documents.length > 0 && (
        <div className="relative mb-6 max-w-xl">
          <Search className="size-4 text-faint absolute left-3 top-1/2 -translate-y-1/2" aria-hidden />
          <Input
            type="search"
            aria-label="Search these documents"
            placeholder="Search in English or Tamil"
            className="pl-9"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      )}

      {searching ? (
        <SearchResults loading={search.isPending} error={search.error} data={search.data} />
      ) : list.documents.length === 0 ? (
        <EmptyState
          title="No documents yet"
          body={
            list.canEdit
              ? 'Add lecture notes, slides, past papers or scanned pages. Each one is read page by page, in English and Tamil, so you can search it.'
              : "No one has added documents to this subject yet. Owners and editors can add them."
          }
        />
      ) : (
        <ul className="divide-y divide-line border-y border-line">
          {list.documents.map((d) => (
            <DocumentRow key={d.id} doc={d} serviceReady={list.serviceReady} />
          ))}
        </ul>
      )}
    </>
  )
}

function DocumentRow({ doc, serviceReady }: { doc: CourseDocument; serviceReady: boolean }) {
  const v = doc.latest
  const note = subjectNote(doc.subject)
  return (
    <li className="py-4 flex items-start gap-4">
      <FileText className="size-5 text-faint mt-0.5 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <Link to={`/documents/${doc.id}`} className="font-medium hover:underline break-words">
            {doc.title}
          </Link>
          {v?.status === 'FAILED' && <StatusPill tone="muted">Not read</StatusPill>}
          {isReading(v) && <StatusPill tone="progress">{v?.status === 'PROCESSING' ? 'Reading' : 'Waiting'}</StatusPill>}
          {doc.versionCount > 1 && <span className="text-sm text-faint">Version {v?.versionNo}</span>}
        </div>
        <p className={v?.status === 'FAILED' ? 'text-sm text-rose-signal mt-0.5' : 'text-sm text-muted mt-0.5'}>
          {statusText(v, serviceReady)}
          {v?.status === 'READY' && v.language && v.language !== 'unknown' && `, ${LANGUAGE_NAME[v.language]}`}
        </p>
        {note && (
          <div className="mt-1">
            <Warning>{note}</Warning>
          </div>
        )}
      </div>
      <p className="text-sm text-faint shrink-0 text-right hidden sm:block">
        {formatRelative(doc.updatedAt)}
        {doc.createdBy && <span className="block">{doc.createdBy}</span>}
      </p>
    </li>
  )
}

function SearchResults({
  loading,
  error,
  data,
}: {
  loading: boolean
  error: unknown
  data?: SearchResponse
}) {
  if (loading) return <Spinner label="Searching" />
  if (error) return <ErrorNotice error={error} />
  if (!data || data.hits.length === 0) {
    return (
      <EmptyState
        title="No matches"
        body="Search matches the beginning of words, so “normal” finds “normalisation” and “கணினி” finds “கணினியின்”. Every word you type must appear in the same passage."
      />
    )
  }
  return (
    <section aria-label="Search results">
      <p className="text-sm text-muted mb-3">
        {data.more ? `The best ${data.hits.length} passages` : `${data.hits.length} ${data.hits.length === 1 ? 'passage' : 'passages'}`}
      </p>
      <ol className="space-y-3">
        {data.hits.map((h, i) => (
          <li key={`${h.versionId}-${h.pageNo}-${i}`}>
            <Link
              to={`/documents/${h.documentId}?version=${h.versionId}&page=${h.pageNo}`}
              className="block rounded-xl border border-line px-4 py-3 hover:bg-surface-2"
            >
              <p className="text-sm">
                <span className="font-medium">{h.title}</span>
                <span className="text-muted">, page {h.pageNo}</span>
              </p>
              <p className="mt-1 text-[0.95rem] leading-relaxed">
                <Marked {...h.snippet} />
              </p>
            </Link>
          </li>
        ))}
      </ol>
    </section>
  )
}

/** /documents — pick a subject. University subjects come first. */
export function DocumentsHomePage() {
  const projects = useProjects({})
  if (projects.isPending) return <Spinner label="Loading subjects" />
  if (projects.error) return <ErrorNotice error={projects.error} onRetry={() => projects.refetch()} />
  const items = [...projects.data.items].sort((a, b) => Number(b.kind === 'EDUCATION') - Number(a.kind === 'EDUCATION'))
  return (
    <>
      <PageHeader title="Course documents" description="Lecture notes, slides and past papers, kept per subject and searchable in English and Tamil." />
      {items.length === 0 ? (
        <EmptyState title="No projects yet" body="Create a university subject in My projects first; its documents live there." />
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((p) => (
            <li key={p.id}>
              <Link to={`/projects/${p.id}/documents`} className="flex items-start gap-3 rounded-xl border border-line px-4 py-3 hover:bg-surface-2 h-full">
                <BookOpen className="size-5 text-faint mt-0.5 shrink-0" aria-hidden />
                <span className="min-w-0">
                  <span className="block font-medium break-words">{p.name}</span>
                  <span className="block text-sm text-muted">
                    {p.kind === 'EDUCATION'
                      ? [p.subject?.moduleCode, p.subject?.academicYear].filter(Boolean).join(', ') || 'University subject'
                      : 'Creator project'}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}
