import { describe, expect, it } from 'vitest'
import { activeCueIndex, cueProblems, formatCueTime, insertCueAfter, parseCueTime, shiftCues, toVtt } from './subtitles'

describe('subtitle helpers', () => {
  it('formats and parses times', () => {
    expect(formatCueTime(75.5)).toBe('1:15.500')
    expect(formatCueTime(3725.25)).toBe('1:02:05.250')
    expect(parseCueTime('1:15.5')).toBe(75.5)
    expect(parseCueTime('01:01:15,250')).toBe(3675.25)
    expect(parseCueTime('75')).toBe(75)
    expect(parseCueTime('abc')).toBeUndefined()
  })

  it('builds VTT for the preview', () => {
    expect(toVtt([{ start: 0.5, end: 2, text: 'வணக்கம்\nfriends' }, { start: 3, end: 4, text: '  ' }])).toBe(
      'WEBVTT\n\n00:00:00.500 --> 00:00:02.000\nவணக்கம்\nfriends\n',
    )
  })

  it('shifts without going negative', () => {
    expect(shiftCues([{ start: 0.2, end: 1, text: 'a' }], -0.5)).toEqual([{ start: 0, end: 0.5, text: 'a' }])
  })

  it('finds the cue on screen and inserts new ones', () => {
    const cues = [{ start: 0, end: 2, text: 'a' }, { start: 3, end: 5, text: 'b' }]
    expect(activeCueIndex(cues, 3.5)).toBe(1)
    expect(activeCueIndex(cues, 2.5)).toBe(-1)
    expect(insertCueAfter(cues, 0)[1]).toEqual({ start: 2, end: 3, text: '' })
  })

  it('flags overlaps and bad timing', () => {
    const p = cueProblems([{ start: 0, end: 2, text: 'a' }, { start: 1.5, end: 3, text: 'b' }, { start: 4, end: 3, text: 'c' }])
    expect(p.get(1)).toMatch(/Overlaps/)
    expect(p.get(2)).toMatch(/Ends before/)
  })
})
