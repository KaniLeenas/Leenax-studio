import { describe, expect, it } from 'vitest'
import { describePricing, durationChoices, groupJobs, prettyRatio, ratiosFor, statusLine } from './generation'
import { formatAmount } from './format'
import type { AiModelInfo, GenerationJob } from './types'

const model = (options: AiModelInfo['options']): AiModelInfo => ({
  id: 'm', provider: 'RUNWAY', modelCode: 'x', displayName: 'X', capability: 'VIDEO', options, pricing: {}, enabled: true,
})

const job = (over: Partial<GenerationJob>): GenerationJob => ({
  id: 'j', groupId: 'g', variationIndex: 0, accountName: 'A', provider: 'RUNWAY', providerName: 'Runway', modelCode: 'x',
  modelName: 'X', mode: 'TEXT_TO_VIDEO', prompt: 'p', status: 'QUEUED', attempts: 0, createdAt: '2026-11-19T00:00:00Z', ...over,
})

describe('generation helpers', () => {
  it('expands duration ranges and keeps fixed values', () => {
    expect(durationChoices(model({ durations: { min: 2, max: 5 } }))).toEqual([2, 3, 4, 5])
    expect(durationChoices(model({ durations: { values: [4, 6, 8] } }))).toEqual([4, 6, 8])
    expect(durationChoices(model({}))).toEqual([])
  })

  it('picks per-mode ratios, falling back to ALL', () => {
    const m = model({ aspectRatios: { TEXT_TO_VIDEO: [{ value: '16:9', label: '16:9' }], ALL: [{ value: '1:1', label: '1:1' }] } })
    expect(ratiosFor(m, 'TEXT_TO_VIDEO').map((r) => r.value)).toEqual(['16:9'])
    expect(ratiosFor(m, 'IMAGE_TO_VIDEO').map((r) => r.value)).toEqual(['1:1'])
  })

  it('groups variations and orders them', () => {
    const groups = groupJobs([
      job({ id: 'b', groupId: 'g1', variationIndex: 1 }),
      job({ id: 'c', groupId: 'g2' }),
      job({ id: 'a', groupId: 'g1', variationIndex: 0 }),
    ])
    expect(groups.map((g) => g.map((j) => j.id))).toEqual([['a', 'b'], ['c']])
  })

  it('describes job status in plain words', () => {
    expect(statusLine(job({ status: 'PROCESSING', progress: 0.42 }))).toBe('Generating, 42%')
    expect(statusLine(job({ status: 'QUEUED', attempts: 2 }))).toBe('Waiting to retry')
    expect(statusLine(job({ status: 'COMPLETED' }))).toBe('Ready')
  })

  it('describes prices', () => {
    expect(describePricing({})).toBe('No price set')
    expect(describePricing({ unit: 'CREDITS', rates: [{ perSecond: 20, audio: false }, { perSecond: 40, audio: true }] }))
      .toBe('20 credits/s (silent), 40 credits/s (with sound)')
    expect(describePricing({ unit: 'USD', minimum: 0.5, rates: [{ perSecond: 0.1, resolution: '720p' }] })).toBe('$0.10/s (720p), minimum $0.50')
  })

  it('names pixel ratios', () => {
    expect(prettyRatio('1920:1080')).toBe('16:9')
    expect(prettyRatio('720:1280')).toBe('9:16')
    expect(prettyRatio('960:960')).toBe('1:1')
    expect(prettyRatio('1584:672')).toBe('21:9')
    expect(prettyRatio('16:9')).toBe('16:9')
    expect(prettyRatio('auto_720p')).toBe('auto_720p')
  })

  it('formats amounts by unit', () => {
    expect(formatAmount(1250, 'CREDITS')).toBe('1,250 credits')
    expect(formatAmount(12.4, 'USD')).toBe('$12.40')
    expect(formatAmount(undefined, 'USD')).toBe('—')
  })
})
