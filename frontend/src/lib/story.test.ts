import { describe, expect, it } from 'vitest'
import { boardProgress, dialogueText, nearestDuration, parseDialogue, postText, spokenMinutes, spokenText, workflowSteps, type Storyboard } from './story'
import type { AiModelInfo } from './types'

describe('scripts', () => {
  it('counts only what the narrator says and paces Tamil slower', () => {
    const body = '[INTRO]\nHello and welcome\n\n[SCENE 1: Market]\n(Visual: market at dawn)\none two three'
    expect(spokenText(body)).toBe('Hello and welcome one two three')
    const words = 'word '.repeat(300)
    expect(spokenMinutes(words, 'EN')).toBe(2)
    expect(spokenMinutes(words, 'TA')).toBeGreaterThan(2.7)
  })

  it('builds post text with hashtags', () => {
    expect(postText({ caption: 'Night food!', hashtags: ['madurai', 'food'] })).toBe('Night food!\n\n#madurai #food')
    expect(postText({ description: 'Long description' })).toBe('Long description')
    expect(postText(undefined)).toBe('')
  })
})

describe('storyboards', () => {
  it('reads dialogue as Name: line and writes it back', () => {
    const d = parseDialogue('Meena: வணக்கம்!\nno colon here\n : empty name\nKani:  Two glasses, please ')
    expect(d).toEqual([
      { character: 'Meena', line: 'வணக்கம்!' },
      { character: 'Kani', line: 'Two glasses, please' },
    ])
    expect(dialogueText(d)).toBe('Meena: வணக்கம்!\nKani: Two glasses, please')
  })

  it('rounds a scene to the nearest length the video model accepts', () => {
    const fixed = { options: { durations: { values: [4, 6, 8] } } } as unknown as AiModelInfo
    expect(nearestDuration(fixed, 7.5)).toBe(8)
    expect(nearestDuration(fixed, 2)).toBe(4)
    const range = { options: { durations: { min: 5, max: 10 } } } as unknown as AiModelInfo
    expect(nearestDuration(range, 12)).toBe(10)
    expect(nearestDuration(undefined, 5)).toBeUndefined()
  })

  it('summarises progress, counting failed videos only when no video exists', () => {
    const img = { id: 'i' }
    const b = {
      narration: undefined,
      scenes: [
        { image: img, video: { id: 'v' } },
        { image: img, videoJob: { status: 'PROCESSING' } },
        { videoJob: { status: 'FAILED' } },
      ],
    } as unknown as Storyboard
    expect(boardProgress(b)).toEqual({ scenes: 3, images: 2, videos: 1, running: 1, failed: 1, voiced: false })
  })
})

describe('project workflow', () => {
  const empty = { scripts: 0, storyboards: [], characters: 0, timelines: 0, renders: 0, thumbnails: 0, posts: 0, published: 0 }

  it('starts at the script and links each step to the project', () => {
    const steps = workflowSteps('p1', empty)
    expect(steps.map((s) => s.id)).toEqual(['script', 'characters', 'storyboard', 'visuals', 'edit', 'render', 'thumbnail', 'publish'])
    expect(steps.every((s) => !s.done)).toBe(true)
    expect(steps[0].to).toBe('/create/scripts?project=p1')
  })

  it('marks steps done from what exists', () => {
    const board = { id: 'b', title: 'B', aspectRatio: 'PORTRAIT', language: 'TA', sceneCount: 4, imagesReady: 3, videosReady: 1, totalSeconds: 30, timelineId: 't', updatedAt: '' } as const
    const steps = workflowSteps('p1', { ...empty, scripts: 2, storyboards: [board], timelines: 1, renders: 1, posts: 2, published: 1 })
    const byId = Object.fromEntries(steps.map((s) => [s.id, s]))
    expect(byId.script.done && byId.storyboard.done && byId.visuals.done && byId.edit.done && byId.render.done && byId.publish.done).toBe(true)
    expect(byId.visuals.detail).toBe('3 of 4 images · 1 video')
    expect(byId.publish.detail).toBe('1 of 2 posts published')
    expect(byId.thumbnail.done).toBe(false)
  })
})
