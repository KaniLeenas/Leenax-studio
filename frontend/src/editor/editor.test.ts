import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { outputSize } from './ExportPanel'
import { useHistory } from './history'
import { createPlayhead, formatTime, rulerStep } from './playhead'

describe('editor helpers', () => {
  it('records one undo step for a whole drag', () => {
    const { result } = renderHook(() => useHistory(0))
    act(() => result.current.set(1))
    act(() => {
      result.current.preview(2)
      result.current.preview(3)
      result.current.preview(4)
    })
    act(() => result.current.commit())
    expect(result.current.present).toBe(4)
    act(() => result.current.undo())
    expect(result.current.present).toBe(1)
    act(() => result.current.undo())
    expect(result.current.present).toBe(0)
    act(() => result.current.redo())
    act(() => result.current.redo())
    expect(result.current.present).toBe(4)
    expect(result.current.canRedo).toBe(false)
  })

  it('export sizes keep the shape and are even', () => {
    expect(outputSize({ width: 1920, height: 1080 }, 720)).toEqual({ width: 1280, height: 720 })
    expect(outputSize({ width: 1080, height: 1920 }, 2160)).toEqual({ width: 2160, height: 3840 })
    expect(outputSize({ width: 1080, height: 1350 }, 720)).toEqual({ width: 720, height: 900 })
  })

  it('formats times and picks ruler steps', () => {
    expect(formatTime(75.44)).toBe('1:15.4')
    expect(formatTime(3725, false)).toBe('1:02:05')
    expect(rulerStep(100)).toBe(1)
    expect(rulerStep(10)).toBe(10)
    expect(rulerStep(1000)).toBe(0.1)
  })

  it('notifies playhead listeners only on change', () => {
    const p = createPlayhead()
    let calls = 0
    p.subscribe(() => calls++)
    p.set(1)
    p.set(1)
    p.setPlaying(true)
    expect(calls).toBe(2)
    expect(p.get()).toBe(1)
  })
})
