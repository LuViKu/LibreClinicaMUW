/**
 * Multi-role per (user, study) — M2 (2026-06-08).
 *
 * RoleDots is the visual primitive that communicates the set of role
 * bindings a user carries in the active study. The tests pin the
 * three properties the rest of the SPA depends on:
 *
 *  - empty arrays render nothing (no DOM noise),
 *  - one role → one dot, no negative margin (it's the leading dot),
 *  - two roles → two dots, the second carries the -6px overlap so
 *    the colour boundary reads as a stack rather than two siblings,
 *  - aria-label concatenates the roles with " + " so the wrapper is
 *    legible to screen readers without exposing per-dot decorations.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleDots from '../RoleDots.vue'

describe('RoleDots', () => {
  it('renders nothing when the roles array is empty', () => {
    const w = mount(RoleDots, { props: { roles: [] } })
    expect(w.find('span[role="img"]').exists()).toBe(false)
  })

  it('renders a single dot with no left offset for one role', () => {
    const w = mount(RoleDots, { props: { roles: ['Investigator'] } })
    const dots = w.findAll('span[aria-hidden="true"]')
    expect(dots.length).toBe(1)
    const style = dots[0].attributes('style') ?? ''
    expect(style).toContain('margin-left: 0')
    expect(dots[0].classes()).toContain('bg-muw-teal-500')
  })

  it('stacks two dots with -6px margin on the second', () => {
    const w = mount(RoleDots, {
      props: { roles: ['Investigator', 'Data Manager'] },
    })
    const dots = w.findAll('span[aria-hidden="true"]')
    expect(dots.length).toBe(2)
    const firstStyle = dots[0].attributes('style') ?? ''
    const secondStyle = dots[1].attributes('style') ?? ''
    expect(firstStyle).toContain('margin-left: 0')
    expect(secondStyle).toContain('margin-left: -6px')
    expect(dots[0].classes()).toContain('bg-muw-teal-500')
    expect(dots[1].classes()).toContain('bg-muw-coral-500')
  })

  it('joins the aria-label with " + "', () => {
    const w = mount(RoleDots, {
      props: { roles: ['Investigator', 'Data Manager'] },
    })
    const wrapper = w.find('span[role="img"]')
    expect(wrapper.attributes('aria-label')).toBe('Investigator + Data Manager')
  })

  it('maps CRC to the Investigator (teal) colour', () => {
    const w = mount(RoleDots, { props: { roles: ['CRC'] } })
    const dot = w.find('span[aria-hidden="true"]')
    expect(dot.classes()).toContain('bg-muw-teal-500')
  })

  it('maps Monitor to sky and Administrator to MUW blue', () => {
    const w = mount(RoleDots, {
      props: { roles: ['Monitor', 'Administrator'] },
    })
    const dots = w.findAll('span[aria-hidden="true"]')
    expect(dots[0].classes()).toContain('bg-muw-sky-500')
    expect(dots[1].classes()).toContain('bg-muw-blue')
  })
})
