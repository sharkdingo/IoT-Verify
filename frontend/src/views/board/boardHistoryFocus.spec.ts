// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { nextTick } from 'vue'
import { transpileModule, ScriptTarget } from 'typescript'
import { afterEach, expect, it, vi } from 'vitest'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = board.indexOf('const applyBoardHistoryFromControl =')
const end = board.indexOf('/** Name the reversed edit;', start)
if (start < 0 || end < 0) throw new Error('Board history control handler is missing')
const handler = new Function('undoBoardEdit', 'redoBoardEdit', 'nextTick',
  transpileModule(`${board.slice(start, end)}\nreturn applyBoardHistoryFromControl`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

afterEach(() => { document.body.innerHTML = '' })

it.each(['undo', 'redo'] as const)('keeps %s keyboard focus on an available history control', async direction => {
  document.body.innerHTML = '<button data-testid="board-undo">Undo</button><button data-testid="board-redo">Redo</button>'
  const source = document.querySelector<HTMLButtonElement>(`[data-testid="board-${direction}"]`)!
  const opposite = document.querySelector<HTMLButtonElement>(`[data-testid="board-${direction === 'undo' ? 'redo' : 'undo'}"]`)!
  source.focus()
  const apply = vi.fn(async () => { source.disabled = true; source.blur() })
  await handler(apply, apply, nextTick)(direction, { currentTarget: source })
  expect(apply).toHaveBeenCalledOnce()
  expect(document.activeElement).toBe(opposite)
})

it('restores the initiating control when more history remains', async () => {
  document.body.innerHTML = '<button data-testid="board-undo">Undo</button>'
  const source = document.querySelector('button')!
  source.focus()
  const apply = vi.fn(async () => { source.blur() })
  await handler(apply, vi.fn(), nextTick)('undo', { currentTarget: source })
  expect(document.activeElement).toBe(source)
})

it('does not steal focus from a new interaction while undo settles', async () => {
  document.body.innerHTML = '<button data-testid="board-undo">Undo</button><input>'
  const source = document.querySelector('button')!
  const input = document.querySelector('input')!
  source.focus()
  const apply = vi.fn(async () => { source.disabled = true; input.focus() })
  await handler(apply, vi.fn(), nextTick)('undo', { currentTarget: source })
  expect(document.activeElement).toBe(input)
})
