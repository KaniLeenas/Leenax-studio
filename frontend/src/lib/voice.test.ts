import { describe, expect, it } from 'vitest'
import { detectSpeakers, previewSlice, scriptStats } from './voice'
import { recorderMimeType } from '../components/MicRecorder'

describe('voice helpers', () => {
  it('counts spoken characters without pause markers', () => {
    const s = scriptStats('Hello [pause 2s] world [pause]')
    expect(s.characters).toBe('Hello world'.length)
    expect(s.minutes).toBe('<1')
  })

  it('previews up to a sentence end', () => {
    const text = 'First sentence is here. ' + 'word '.repeat(80)
    expect(previewSlice(text, 60)).toBe('First sentence is here.')
  })

  it('finds speakers once, case-insensitively, in order', () => {
    expect(detectSpeakers('Kani: hi\nGuest: hello\nkani： again\n[pause 1s]: no')).toEqual(['Kani', 'Guest'])
  })

  it('prefers Opus WebM and falls back to MP4 for Safari', () => {
    expect(recorderMimeType((t) => t.startsWith('audio/webm'))).toBe('audio/webm;codecs=opus')
    expect(recorderMimeType((t) => t === 'audio/mp4')).toBe('audio/mp4')
    expect(recorderMimeType(() => false)).toBeUndefined()
  })
})
