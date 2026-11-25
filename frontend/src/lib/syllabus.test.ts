import { describe, expect, it } from 'vitest'
import { nextStep, type VersionDetail } from './reviews'
import {
  addChild, canIndent, dropMissingPrerequisites, flatten, indent, mergeWithNext, move, outdent, prerequisiteLoop, remove, rename,
  sourceLabel, split, type Topic, type TreeItem,
} from './syllabus'

const tree = (): TreeItem[] => [
  { id: 'u1', title: 'Unit 1', children: [{ id: 't1', title: 'Keys', children: [{ id: 's1', title: 'Primary keys' }] }, { id: 't2', title: '3NF' }] },
  { id: 'u2', title: 'Unit 2' },
]
const titles = (items: TreeItem[]) => flatten(items).map((f) => `${'-'.repeat(f.depth - 1)}${f.item.title}`)

describe('tree editing', () => {
  it('renames, reorders and removes without touching the original', () => {
    const t = tree()
    expect(titles(move(rename(t, 't2', 'Third normal form'), 't2', -1))).toEqual(['Unit 1', '-Third normal form', '-Keys', '--Primary keys', 'Unit 2'])
    expect(titles(remove(t, 't1'))).toEqual(['Unit 1', '-3NF', 'Unit 2'])
    expect(titles(t)).toEqual(['Unit 1', '-Keys', '--Primary keys', '-3NF', 'Unit 2'])
    expect(move(t, 'u1', -1)).toBe(t)
  })

  it('indents and outdents within three levels', () => {
    expect(titles(indent(tree(), 't2'))).toEqual(['Unit 1', '-Keys', '--Primary keys', '--3NF', 'Unit 2'])
    expect(canIndent(tree(), 'u1')).toBe(false) // nothing above it
    // Keys has a subtopic: putting it under 3NF would push that subtopic to a fourth level
    expect(canIndent(move(tree(), 't1', 1), 't1')).toBe(false)
    expect(canIndent(tree(), 't2')).toBe(true)
    expect(titles(outdent(tree(), 's1'))).toEqual(['Unit 1', '-Keys', '-Primary keys', '-3NF', 'Unit 2'])
    expect(titles(addChild(tree(), 's1', { id: 'x', title: 'Too deep' }))).toEqual(titles(tree()))
  })

  it('merges topics, carrying children, syllabus links and prerequisites', () => {
    const topics: Topic[] = [
      { id: 'a', title: 'Keys', syllabusItemIds: ['t1'], selected: true, prerequisites: [], children: [{ id: 'a1', title: 'Primary keys' }] },
      { id: 'b', title: 'Foreign keys', syllabusItemIds: ['t3'], selected: false, prerequisites: ['c'], children: [{ id: 'b1', title: 'Cascades' }] },
      { id: 'c', title: 'Tables', selected: true, prerequisites: ['b'] },
    ]
    const merged = mergeWithNext(topics, 'a')
    expect(merged.map((t) => t.title)).toEqual(['Keys', 'Tables'])
    expect(merged[0].children?.map((t) => t.title)).toEqual(['Primary keys', 'Cascades'])
    expect(merged[0].syllabusItemIds).toEqual(['t1', 't3'])
    expect(merged[0].prerequisites).toEqual(['c'])
    expect(merged[1].prerequisites).toEqual(['a'])
  })

  it('splits a topic into an unlinked copy after it', () => {
    const topics: Topic[] = [{ id: 'a', title: 'Keys', syllabusItemIds: ['t1'], difficulty: 'INTERMEDIATE', children: [{ id: 'a1', title: 'Primary' }] }]
    const out = split(topics, 'a', 'Foreign keys')
    expect(out.map((t) => t.title)).toEqual(['Keys', 'Foreign keys'])
    expect(out[1].syllabusItemIds).toEqual([])
    expect(out[1].children).toEqual([])
    expect(out[1].difficulty).toBe('INTERMEDIATE')
    expect(out[1].id).not.toBe('a')
  })

  it('finds prerequisite loops among selected topics, and drops dangling ones', () => {
    const loop: Topic[] = [
      { id: 'a', title: 'A', prerequisites: ['c'] },
      { id: 'b', title: 'B', prerequisites: ['a'] },
      { id: 'c', title: 'C', prerequisites: ['b'] },
    ]
    expect(prerequisiteLoop(loop)).toEqual(['A', 'C', 'B', 'A'])
    expect(prerequisiteLoop([{ ...loop[0], selected: false }, loop[1], loop[2]])).toBeNull()
    expect(dropMissingPrerequisites([{ id: 'a', title: 'A', prerequisites: ['gone', 'b'] }, { id: 'b', title: 'B' }])[0].prerequisites).toEqual(['b'])
  })

  it('names sources by document label and page', () => {
    expect(sourceLabel({ documentId: 'd', versionId: 'v', page: 3 }, [{ documentId: 'd', versionId: 'v', label: 'D1' }])).toBe('D1 p.3')
  })
})

describe('next step', () => {
  const d = (status: VersionDetail['version']['status'], you: Partial<VersionDetail['you']>, checks: VersionDetail['checks'] = []) =>
    ({ version: { status }, checks, you: { canEdit: false, canReview: false, isAuthor: false, mayDecide: false, reviewerMustDiffer: true, ...you } }) as VersionDetail
  it('tells each person what they can do', () => {
    expect(nextStep(d('DRAFT', { canEdit: true }, [{ code: 'x', ok: false, blocking: true, message: '' }]), 'Syllabus')).toBe('Fix the item marked below, then send it for review.')
    expect(nextStep(d('IN_REVIEW', { canReview: true, mayDecide: true }), 'Syllabus')).toBe('Check the syllabus and decide.')
    expect(nextStep(d('IN_REVIEW', { canReview: true, isAuthor: true }), 'Syllabus')).toBe('You wrote this version, so another reviewer must decide.')
    expect(nextStep(d('IN_REVIEW', {}), 'Topics')).toBe('Waiting for a reviewer.')
  })
})
