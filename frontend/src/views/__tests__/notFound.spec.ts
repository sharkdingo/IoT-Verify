import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The 404 route's contract.
 *
 * This was the last view built from bare Element Plus defaults (`el-main` / `el-result` / `el-button` —
 * the only such usage anywhere in the codebase), which meant it could not follow the theme: the page
 * stayed light while the rest of the product went dark. It also had no header, so a single button was
 * the only exit for someone who had just followed a broken link.
 */

const readView = (name: string) =>
  readFileSync(join(__dirname, '..', name), 'utf8')

const routerSource = () =>
  readFileSync(join(__dirname, '../../router/index.ts'), 'utf8')

describe('the not-found route', () => {
  it('is built from the product surfaces, not framework defaults', () => {
    const source = readView('NotFound.vue')

    // Element Plus components carry their own palette and ignore the theme tokens entirely.
    expect(source).not.toMatch(/<el-(main|result|button)\b/)
    expect(source).not.toMatch(/<El(Main|Result|Button)\b/)
    // Colour comes from tokens, so the page follows light and dark.
    expect(source).toContain('var(--surface)')
    expect(source).toContain('var(--text)')
  })

  it('keeps a shared header so the page is not a dead end', () => {
    const source = readView('NotFound.vue')
    expect(source).toContain('PublicHeader')
  })

  it('preserves the address that was requested', () => {
    // A bare `redirect: '/404'` discarded it, leaving the page unable to say what "this" was — and a
    // truncated shared link is the most common way to arrive here.
    expect(routerSource()).toMatch(/redirect:\s*to\s*=>\s*\(\{\s*path:\s*'\/404',\s*query:\s*\{\s*from:\s*to\.fullPath\s*\}/)
  })

  it('renders only a same-origin path from the query', () => {
    const source = readView('NotFound.vue')
    const guard = source.slice(
      source.indexOf('const attemptedPath'),
      source.indexOf('</script>')
    )

    // `from` is user-controlled and echoed into the page, so an absolute URL or a protocol-relative
    // `//host` must never render as if it were an address inside this product.
    expect(guard).toContain("value.startsWith('/')")
    expect(guard).toContain("!value.startsWith('//')")
    // And bounded, so a pathological query cannot stretch the layout.
    expect(guard).toMatch(/slice\(0,\s*\d+\)/)
  })

  it('offers the workspace only to someone who has a session', () => {
    const source = readView('NotFound.vue')

    // Offering "go to the Board" to a visitor without a token would bounce them through the auth
    // redirect, which is a worse dead end than the one this page replaces.
    expect(source).toMatch(/isSignedIn\s*=\s*computed\(\(\)\s*=>\s*Boolean\(getToken\(\)\)\)/)
    const boardLink = source.slice(
      source.indexOf('not-found-board') - 400,
      source.indexOf('not-found-board')
    )
    expect(boardLink).toContain('v-if="isSignedIn"')
    // The way back to the start is always available.
    expect(source).toContain('data-testid="not-found-home"')
  })

  it('states what happened rather than only that something is wrong', () => {
    const i18n = readFileSync(join(__dirname, '../../assets/i18n.ts'), 'utf8')

    // The title names the missing page; the address and exits support recovery without a second
    // paragraph repeating the title or speculating about how the user arrived here.
    expect(readView('NotFound.vue')).not.toContain("t('app.notFound.subtitle')")
    expect(i18n).not.toMatch(/notFound:\s*\{\s*title:\s*'(Error|错误)'/)
    expect(i18n).toMatch(/notFound:[\s\S]{0,400}?attempted:/)
    expect(i18n).toMatch(/notFound:[\s\S]{0,400}?workspace:/)
  })
})
