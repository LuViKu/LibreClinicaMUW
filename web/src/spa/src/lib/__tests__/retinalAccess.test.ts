import { describe, expect, it } from 'vitest'

import { canViewRetinalMetrics, isBlindingOff } from '../retinalAccess'

describe('canViewRetinalMetrics', () => {
  it('never blinds the roles that make no treatment decision', () => {
    for (const role of ['Monitor', 'Data Manager', 'Administrator'] as const) {
      expect(canViewRetinalMetrics([role], undefined)).toBe(true)
      expect(canViewRetinalMetrics([role], { 'ai.blinding.enabled': 'true' })).toBe(true)
    }
  })

  it('blinds the treating clinician by default — absent setting means blinded', () => {
    expect(canViewRetinalMetrics(['Investigator'], undefined)).toBe(false)
    expect(canViewRetinalMetrics(['Investigator'], {})).toBe(false)
    expect(canViewRetinalMetrics(['CRC'], { 'ai.blinding.enabled': 'true' })).toBe(false)
  })

  it('lets the treating clinician through when the study switched blinding off', () => {
    expect(canViewRetinalMetrics(['Investigator'], { 'ai.blinding.enabled': 'false' })).toBe(true)
    expect(canViewRetinalMetrics(['CRC'], { 'ai.blinding.enabled': 'FALSE ' })).toBe(true)
  })

  it('a user holding an unblinded role alongside a treating one is not blinded', () => {
    expect(canViewRetinalMetrics(['Investigator', 'Data Manager'], undefined)).toBe(true)
  })

  it('refuses when no role is held at all', () => {
    expect(canViewRetinalMetrics([], { 'ai.blinding.enabled': 'false' })).toBe(false)
  })

  it('isBlindingOff accepts only an explicit false', () => {
    expect(isBlindingOff({ 'ai.blinding.enabled': 'false' })).toBe(true)
    expect(isBlindingOff({ 'ai.blinding.enabled': 'off' })).toBe(false)
    expect(isBlindingOff({ 'ai.blinding.enabled': '' })).toBe(false)
    expect(isBlindingOff(null)).toBe(false)
  })
})
