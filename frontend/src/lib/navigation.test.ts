import { describe, expect, it } from 'vitest'
import { ALL_NAV_ITEMS, isLive, NAV } from './navigation'

describe('navigation', () => {
  it('lists all 23 sections with unique paths', () => {
    expect(ALL_NAV_ITEMS).toHaveLength(23)
    expect(new Set(ALL_NAV_ITEMS.map((i) => i.path)).size).toBe(23)
  })

  it('marks every section as live after Phase 10', () => {
    const live = ALL_NAV_ITEMS.filter(isLive).map((i) => i.label)
    expect(live).toEqual([
      'Dashboard', 'My projects', 'Media library', 'Course documents',
      'Scripts & ideas', 'Character studio', 'Storyboard',
      'AI video generator', 'AI image generator', 'Thumbnail studio', 'Voice & audio',
      'Video editor', 'AI auto editor', 'Subtitles',
      'Social accounts', 'Publisher', 'Calendar', 'Analytics',
      'Users',
      'AI accounts & keys', 'Credits & usage', 'AI Agents', 'Settings',
    ])
  })

  it('groups by workflow stage', () => {
    expect(NAV.map((g) => g.label)).toEqual(['Studio', 'Create', 'Edit', 'Publish', 'Manage'])
  })
})

describe('menu by role', () => {
  it('hides administrator tools from members', async () => {
    const { navFor } = await import('./navigation')
    const member = navFor(false).flatMap((g) => g.items.map((i) => i.label))
    expect(member).not.toContain('Users')
    expect(member).not.toContain('AI accounts & keys')
    expect(member).not.toContain('Credits & usage')
    expect(member).toContain('Settings')
    expect(member).toContain('Course documents')
    expect(navFor(true).flatMap((g) => g.items)).toHaveLength(23)
  })
})
