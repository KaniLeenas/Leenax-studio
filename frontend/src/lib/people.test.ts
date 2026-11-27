import { describe, expect, it } from 'vitest'
import { canEditProject, canOwnProject, device, linkUrl, roleLabel } from './people'

describe('people', () => {
  it('says who may edit and who may manage a project', () => {
    expect(canEditProject('EDITOR')).toBe(true)
    expect(canEditProject('REVIEWER')).toBe(false)
    expect(canEditProject('ADMIN')).toBe(true)
    expect(canOwnProject('EDITOR')).toBe(false)
    expect(canOwnProject('OWNER')).toBe(true)
    expect(canOwnProject(undefined)).toBe(false)
    expect(roleLabel('ADMIN')).toBe('Administrator')
    expect(roleLabel('VIEWER')).toBe('Viewer')
  })

  it('builds full one-time links and names devices', () => {
    expect(linkUrl('/activate?token=abc', 'https://studio.leenax.lk')).toBe('https://studio.leenax.lk/activate?token=abc')
    expect(device('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36')).toBe('Chrome on Windows')
    expect(device('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 Version/18.1 Mobile/15E148 Safari/604.1')).toBe('Safari on iOS')
    expect(device('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0')).toBe('Edge on Windows')
    expect(device(undefined)).toBe('')
  })
})
