import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from '@vue/compiler-sfc'
import type { ElementNode } from '@vue/compiler-dom'
import { describe, expect, it } from 'vitest'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const template = parse(board).descriptor.template!
const find = (testId: string): { node: ElementNode; ancestors: ElementNode[] } | undefined => {
  const visit = (node: any, ancestors: ElementNode[]): ReturnType<typeof find> => {
    if (node.type !== 1) return
    if (node.props.some((prop: any) => prop.type === 6 && prop.name === 'data-testid' && prop.value?.content === testId)) {
      return { node, ancestors }
    }
    for (const child of node.children) {
      const result = visit(child, [...ancestors, node])
      if (result) return result
    }
  }
  for (const child of template.ast!.children) {
    const result = visit(child, [])
    if (result) return result
  }
}

describe('recommendation task placement', () => {
  it.each(['device', 'rule', 'spec'])('keeps %s suggestions in the Control Center without a floating shell', kind => {
    const result = find(`${kind}-recommendation-panel`)
    expect(result).toBeDefined()
    expect(result!.ancestors.some(parent => parent.tag === 'ControlCenter')).toBe(true)
    expect(result!.node.loc.source.split('>')[0]).not.toContain('board-floating-panel')
  })

  it('has one complete-scene entry through the assistant, without a second recommendation workspace', () => {
    expect(Boolean(find('ai-tool-group'))).toBe(false)
    expect(Boolean(find('scenario-recommendation-panel'))).toBe(false)
    expect(find('empty-state-open-assistant')).toBeDefined()
  })
})
