import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, uploadFile } from './api'

// ---- types (match DocumentDtos.java) ------------------------------------------------------------

export type DocumentStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED'
export type DocumentLanguage = 'en' | 'ta' | 'mixed' | 'unknown'

export interface DocumentVersion {
  id: string
  versionNo: number
  originalFilename: string
  contentType: string
  sizeBytes: number
  status: DocumentStatus
  error?: string | null
  pageCount?: number | null
  pagesRead?: number | null
  ocrPages?: number | null
  ocrConfidence?: number | null
  language?: DocumentLanguage | null
  moduleCodes: string[]
  detectedTitle?: string | null
  forceOcr: boolean
  createdAt: string
  processedAt?: string | null
  uploadedBy?: string | null
}

export interface SubjectCheck {
  result: 'MATCH' | 'DIFFERENT' | 'UNKNOWN'
  found: string[]
  projectCode?: string | null
}

export interface CourseDocument {
  id: string
  projectId: string
  title: string
  createdAt: string
  updatedAt: string
  createdBy?: string | null
  versionCount: number
  latest?: DocumentVersion | null
  subject: SubjectCheck
}

export interface DocumentList {
  serviceReady: boolean
  canEdit: boolean
  documents: CourseDocument[]
}

export interface DocumentDetail {
  document: CourseDocument
  versions: DocumentVersion[]
  canEdit: boolean
}

export interface DocumentPageText {
  pageNo: number
  method: 'TEXT' | 'OCR'
  ocrConfidence?: number | null
  text: string
}

export interface PagesResponse {
  versionId: string
  versionNo: number
  pageCount?: number | null
  from: number
  pages: DocumentPageText[]
  more: boolean
}

export interface Highlight {
  start: number
  end: number
}

export interface SearchHit {
  documentId: string
  title: string
  versionId: string
  versionNo: number
  pageNo: number
  snippet: { text: string; cutBefore: boolean; cutAfter: boolean; highlights: Highlight[] }
}

export interface SearchResponse {
  query: string
  words: string[]
  hits: SearchHit[]
  more: boolean
}

// ---- helpers ------------------------------------------------------------------------------------

/** What the file picker offers; the server checks the contents anyway. */
export const DOCUMENT_ACCEPT =
  '.pdf,.docx,.doc,.pptx,.ppt,.odt,.odp,.txt,.md,.jpg,.jpeg,.png,.tif,.tiff,.webp,application/pdf,image/*'

export const LANGUAGE_NAME: Record<DocumentLanguage, string> = {
  en: 'English',
  ta: 'Tamil',
  mixed: 'English and Tamil',
  unknown: 'Language not detected',
}

export const isReading = (v?: DocumentVersion | null) => v?.status === 'QUEUED' || v?.status === 'PROCESSING'

/** One line on where a version stands. */
export function statusText(v: DocumentVersion | null | undefined, serviceReady: boolean): string {
  if (!v) return 'No file'
  switch (v.status) {
    case 'QUEUED':
      if (v.error) return v.error
      return serviceReady ? 'Waiting to be read' : "Waiting: the document reader isn't running on this server"
    case 'PROCESSING':
      return 'Reading pages…'
    case 'FAILED':
      return v.error ? `Couldn't read it: ${v.error}` : "Couldn't read it"
    case 'READY': {
      const pages = v.pageCount ?? v.pagesRead ?? 0
      const parts = [`${pages} ${pages === 1 ? 'page' : 'pages'}`]
      if (v.ocrPages) parts.push(v.ocrPages === pages ? 'all read by OCR' : `${v.ocrPages} read by OCR`)
      if (v.pageCount && v.pagesRead != null && v.pagesRead < v.pageCount) parts.push(`only the first ${v.pagesRead} read`)
      return parts.join(', ')
    }
  }
}

/** A warning when the document seems to be for a different module, else null. */
export function subjectNote(s: SubjectCheck): string | null {
  if (s.result !== 'DIFFERENT') return null
  return `Mentions ${s.found.join(', ')}, but this project is ${s.projectCode}. Check it's the right subject.`
}

/** Splits a snippet into plain and marked parts, for rendering without HTML. */
export function highlightParts(text: string, highlights: Highlight[]): { text: string; mark: boolean }[] {
  const out: { text: string; mark: boolean }[] = []
  let at = 0
  for (const h of [...highlights].sort((a, b) => a.start - b.start)) {
    const start = Math.max(h.start, at)
    const end = Math.min(h.end, text.length)
    if (end <= start) continue
    if (start > at) out.push({ text: text.slice(at, start), mark: false })
    out.push({ text: text.slice(start, end), mark: true })
    at = end
  }
  if (at < text.length) out.push({ text: text.slice(at), mark: false })
  return out
}

/** Text direction and language for a page, so screen readers and line breaking get Tamil right. */
export const langAttr = (l?: DocumentLanguage | null) => (l === 'ta' ? 'ta' : l === 'en' ? 'en' : undefined)

// ---- queries ------------------------------------------------------------------------------------

const keys = {
  list: (projectId: string) => ['documents', 'project', projectId] as const,
  one: (id: string) => ['documents', 'one', id] as const,
  pages: (versionId: string, from: number) => ['documents', 'pages', versionId, from] as const,
  search: (projectId: string, q: string) => ['documents', 'search', projectId, q] as const,
}

export function useDocuments(projectId: string) {
  return useQuery({
    queryKey: keys.list(projectId),
    queryFn: () => api.get<DocumentList>(`/projects/${projectId}/documents`),
    enabled: !!projectId,
    // Keep checking while something is being read.
    refetchInterval: (q) => (q.state.data?.documents.some((d) => isReading(d.latest)) ? 4000 : false),
  })
}

export function useDocument(id: string) {
  return useQuery({
    queryKey: keys.one(id),
    queryFn: () => api.get<DocumentDetail>(`/documents/${id}`),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data?.versions.some(isReading) ? 4000 : false),
  })
}

export function useDocumentPages(versionId: string | undefined, from: number) {
  return useQuery({
    queryKey: keys.pages(versionId ?? '', from),
    queryFn: () => api.get<PagesResponse>(`/documents/versions/${versionId}/pages?from=${from}`),
    enabled: !!versionId,
  })
}

export function useDocumentSearch(projectId: string, q: string) {
  const query = q.trim()
  return useQuery({
    queryKey: keys.search(projectId, query),
    queryFn: () => api.get<SearchResponse>(`/projects/${projectId}/documents/search?q=${encodeURIComponent(query)}`),
    enabled: !!projectId && query.length >= 2,
    staleTime: 30_000,
  })
}

function useInvalidateDocuments() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['documents'] })
}

export function uploadDocument(projectId: string, file: File, onProgress: (f: number) => void, signal?: AbortSignal) {
  return uploadFile<DocumentDetail>(`/projects/${projectId}/documents`, file, onProgress, signal)
}

export function uploadVersion(documentId: string, file: File, onProgress: (f: number) => void, signal?: AbortSignal) {
  return uploadFile<DocumentDetail>(`/documents/${documentId}/versions`, file, onProgress, signal)
}

export function useAfterUpload() {
  return useInvalidateDocuments()
}

export function useRenameDocument() {
  const done = useInvalidateDocuments()
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => api.patch<DocumentDetail>(`/documents/${id}`, { title }),
    onSuccess: done,
  })
}

export function useDeleteDocument() {
  const done = useInvalidateDocuments()
  return useMutation({
    mutationFn: (id: string) => api.delete(`/documents/${id}`),
    onSuccess: done,
  })
}

export function useReprocess() {
  const done = useInvalidateDocuments()
  return useMutation({
    mutationFn: ({ versionId, forceOcr }: { versionId: string; forceOcr: boolean }) =>
      api.post<DocumentDetail>(`/documents/versions/${versionId}/reprocess`, { forceOcr }),
    onSuccess: done,
  })
}

export function fetchDownloadUrl(versionId: string) {
  return api.get<{ url: string; expiresAt: string }>(`/documents/versions/${versionId}/download-url`)
}
