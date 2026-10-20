// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { nextTick } from 'vue'
import { transpileModule, ScriptTarget } from 'typescript'
import { afterEach, expect, it } from 'vitest'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = board.indexOf('const closeSceneActionsMenu =')
const end = board.indexOf('const handleSceneActionsMenuToggle =', start)
if (start < 0 || end < 0) throw new Error('Scene menu close handler is missing')
const closeMenu = new Function('sceneActionsMenuRef', 'sceneActionsMenuOpen', 'nextTick',
  transpileModule(`${board.slice(start, end)}\nreturn closeSceneActionsMenu`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)
const accountStart = board.indexOf('data-testid="nav-overflow-account"')
const accountAction = /@click="([^"]+)"/.exec(board.slice(accountStart, accountStart + 500))?.[1]
if (!accountAction) throw new Error('Overflow account action is missing')

afterEach(() => { document.body.innerHTML = '' })

it('closes the overflow before navigating to the account page', async () => {
  const menu = document.createElement('details')
  menu.innerHTML = '<summary>Scene actions</summary><button>Account center</button>'
  menu.open = true
  document.body.append(menu)
  const summary = menu.querySelector('summary')!
  const account = menu.querySelector('button')!
  account.focus()
  const menuOpen = { value: true }
  const close = closeMenu({ value: menu }, menuOpen, nextTick)
  let navigated = false
  new Function('closeSceneActionsMenu', 'openAccount', accountAction)(close, () => {
    expect(menu.open).toBe(false)
    navigated = true
  })
  expect(menu.open).toBe(false)
  expect(menuOpen.value).toBe(false)
  await nextTick()
  expect(navigated).toBe(true)
  expect(document.activeElement).toBe(summary)
})
