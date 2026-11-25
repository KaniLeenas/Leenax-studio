import { describe, expect, it } from 'vitest'
import { compact, dayRange, describeAction, formatMoney, formatUptime, niceTicks, pivot } from './analytics'

describe('analytics helpers', () => {
  it('lists every day of the period in the owner time zone', () => {
    const days = dayRange('2026-11-12T18:30:00Z', '2026-11-19T15:00:00Z', 'Asia/Colombo')
    expect(days).toHaveLength(7)
    expect(days[0]).toBe('2026-11-13')
    expect(days[6]).toBe('2026-11-19')
  })

  it('fills missing days with zeros for each series', () => {
    const rows = pivot(['2026-11-18', '2026-11-19'], [{ date: '2026-11-19', key: 'YOUTUBE' as const, value: 2 }, { date: '2026-11-19', key: 'YOUTUBE' as const, value: 1 }], ['YOUTUBE', 'TIKTOK'])
    expect(rows).toEqual([
      { date: '2026-11-18', values: { YOUTUBE: 0, TIKTOK: 0 } },
      { date: '2026-11-19', values: { YOUTUBE: 3, TIKTOK: 0 } },
    ])
  })

  it('makes clean axis ticks that reach the maximum', () => {
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8])
    expect(niceTicks(380)).toEqual([0, 100, 200, 300, 400])
    expect(niceTicks(0)).toEqual([0, 1])
    expect(niceTicks(1.52)[0]).toBe(0)
    expect(niceTicks(1.52).at(-1)!).toBeGreaterThanOrEqual(1.52)
  })

  it('formats numbers, money and durations', () => {
    expect(compact(9500)).toBe('9,500')
    expect(compact(12500)).toMatch(/12\.5K/)
    expect(formatMoney(1.5, 'USD')).toBe('$1.50')
    expect(formatMoney(380, 'CREDITS')).toBe('380 credits')
    expect(formatUptime('PT51H12M30S')).toBe('2 d 3 h')
    expect(formatUptime('PT3M')).toBe('3 min')
  })

  it('describes audit actions in plain words', () => {
    expect(describeAction('MFA_ENABLED')).toBe('Two-step sign-in turned on')
    expect(describeAction('SOMETHING_NEW')).toBe('Something new')
  })
})
