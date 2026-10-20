// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CodeBlock from '../CodeBlock.vue'
import { i18n } from '@/assets/i18n'

describe('CodeBlock copy control', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('preserves the highlighter background paired with its syntax colors', () => {
    const source = readFileSync(join(__dirname, '../CodeBlock.vue'), 'utf8')
    const style = /<style scoped>([\s\S]*?)<\/style>/.exec(source)?.[1]
    expect(style).toBeDefined()
    const overrides: string[] = []
    postcss.parse(style!).walkRules(rule => {
      if (rule.selector.includes('pre.shiki')) {
        rule.walkDecls(decl => {
          if (decl.prop === 'background' || decl.prop === 'background-color') overrides.push(decl.toString())
        })
      }
    })
    expect(overrides).toEqual([])
  })

  it('announces a successful copy', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    const wrapper = mount(CodeBlock, {
      props: { highlightVnode: h('pre', 'const answer = 42'), language: 'ts' },
      global: { plugins: [i18n] }
    })

    await wrapper.get('button[aria-label="Copy"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('const answer = 42')
    expect(wrapper.text()).toContain('Copied')
  })

  it('shows a recoverable state when clipboard access fails', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }
    })
    const wrapper = mount(CodeBlock, {
      props: { highlightVnode: h('pre', 'example'), language: 'text' },
      global: { plugins: [i18n] }
    })

    await wrapper.get('button[aria-label="Copy"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Copy failed')
  })
})
