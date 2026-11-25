import { describe, expect, it } from 'vitest'
import { highlightParts, isReading, statusText, subjectNote, type DocumentVersion } from './documents'

const v = (p: Partial<DocumentVersion>): DocumentVersion => ({
  id: 'v1', versionNo: 1, originalFilename: 'l3.pdf', contentType: 'application/pdf', sizeBytes: 1000, status: 'READY',
  moduleCodes: [], forceOcr: false, createdAt: '2026-11-23T10:00:00Z', ...p,
})

describe('document status', () => {
  it('says how a read document was read', () => {
    expect(statusText(v({ pageCount: 12, pagesRead: 12, ocrPages: 0 }), true)).toBe('12 pages')
    expect(statusText(v({ pageCount: 1, pagesRead: 1, ocrPages: 1 }), true)).toBe('1 page, all read by OCR')
    expect(statusText(v({ pageCount: 600, pagesRead: 500, ocrPages: 3 }), true)).toBe('600 pages, 3 read by OCR, only the first 500 read')
  })

  it('explains waiting, retrying and failure', () => {
    expect(statusText(v({ status: 'QUEUED' }), true)).toBe('Waiting to be read')
    expect(statusText(v({ status: 'QUEUED' }), false)).toMatch(/isn't running/)
    expect(statusText(v({ status: 'QUEUED', error: 'The document service is not reachable — trying again shortly' }), true)).toMatch(/trying again/)
    expect(statusText(v({ status: 'FAILED', error: 'This PDF can\'t be opened' }), true)).toBe("Couldn't read it: This PDF can't be opened")
    expect(isReading(v({ status: 'PROCESSING' }))).toBe(true)
    expect(isReading(v({ status: 'FAILED' }))).toBe(false)
  })

  it('warns only when the module code differs', () => {
    expect(subjectNote({ result: 'DIFFERENT', found: ['IT1050'], projectCode: 'CSC2041' })).toBe(
      "Mentions IT1050, but this project is CSC2041. Check it's the right subject.",
    )
    expect(subjectNote({ result: 'MATCH', found: ['CSC2041'], projectCode: 'CSC2041' })).toBeNull()
    expect(subjectNote({ result: 'UNKNOWN', found: [] })).toBeNull()
  })
})

describe('highlightParts', () => {
  it('splits text around marks, Tamil included', () => {
    const text = 'இது கணினியின் நினைவகம்'
    const start = text.indexOf('கணினியின்')
    expect(highlightParts(text, [{ start, end: start + 'கணினியின்'.length }])).toEqual([
      { text: 'இது ', mark: false },
      { text: 'கணினியின்', mark: true },
      { text: ' நினைவகம்', mark: false },
    ])
  })

  it('ignores overlapping or out-of-range marks', () => {
    expect(highlightParts('abcdef', [{ start: 4, end: 99 }, { start: 0, end: 2 }, { start: 1, end: 3 }])).toEqual([
      { text: 'ab', mark: true },
      { text: 'c', mark: true },
      { text: 'd', mark: false },
      { text: 'ef', mark: true },
    ])
    expect(highlightParts('abc', [])).toEqual([{ text: 'abc', mark: false }])
  })
})
