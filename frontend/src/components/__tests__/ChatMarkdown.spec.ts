import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'

import ChatMarkdown from '@/components/ChatMarkdown.vue'
import { i18n } from '@/assets/i18n'

describe('ChatMarkdown', () => {
  const wrappers: ReturnType<typeof mount>[] = []
  const render = (source: string) => {
    const wrapper = mount(ChatMarkdown, { props: { source }, global: { plugins: [i18n] } })
    wrappers.push(wrapper)
    return wrapper
  }

  afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
  beforeAll(() => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: () => ({
        matches: false,
        addEventListener: () => undefined,
        removeEventListener: () => undefined
      })
    })
  })

  it.each([
    ['场景**“家庭空气质量与舒适度联动”已成功原子替换并应用**。当前画布已确认包含：', 'strong', '“家庭空气质量与舒适度联动”已成功原子替换并应用'],
    ['**已应用。**请检查。', 'strong', '已应用。'],
    ['采用**（离家模式）**即可。', 'strong', '（离家模式）'],
    ['注意*“尚未验证”*这一点。', 'em', '“尚未验证”'],
    ['删除~~“旧规则”~~后再检查。', 'del', '“旧规则”'],
    ['使用**`cool`**状态。', 'strong code', 'cool'],
    ['参考**[说明](https://example.com)**继续。', 'strong a', '说明'],
    ['采用***“离家模式”***即可。', 'em strong', '“离家模式”'],
    ['| 结果 |\n| --- |\n| 场景**“已应用”**成功 |', 'td strong', '“已应用”'],
  ])('renders CJK emphasis in %s', async (source, selector, text) => {
    const wrapper = render(source)
    await flushPromises()
    expect(wrapper.get(selector).text()).toBe(text)
  })

  it.each([
    ['`场景**“测试”**`', '场景**“测试”**'],
    [String.raw`场景\*\*“测试”\*\*`, '场景**“测试”**'],
    ['场景&#42;&#42;“测试”&#42;&#42;', '场景**“测试”**'],
    ['场景**“正在接收', '场景**“正在接收'],
    ['场景** 已应用 **。', '场景** 已应用 **。'],
    ['a**"foo"**', 'a**"foo"**'],
  ])('preserves literal or incomplete markers in %s', async (source, text) => {
    const wrapper = render(source)
    await flushPromises()
    expect(wrapper.text()).toBe(text)
    expect(wrapper.find('strong, em, del').exists()).toBe(false)
  })

  it('renders streamed delimiters only once complete and agrees with restored history', async () => {
    const source = '场景**“家庭空气质量”**已应用。'
    const wrapper = render('')
    for (let end = 1; end <= source.indexOf('已应用'); end++) {
      await wrapper.setProps({ source: source.slice(0, end) })
    }
    await wrapper.setProps({ source })
    const restored = render(source)
    await flushPromises()
    expect(wrapper.get('strong').text()).toBe('“家庭空气质量”')
    expect(wrapper.html()).toBe(restored.html())
    await wrapper.setProps({ source: '下一条消息' })
    expect(wrapper.text()).toBe('下一条消息')
    expect(wrapper.find('strong').exists()).toBe(false)
  })

  it.each([
    [String.raw`温度为\(x_1 + 2\)。`, 'x_1 + 2', false],
    [String.raw`\[x^2 + y^2\]`, 'x^2 + y^2', true],
    ['\\[\nx^2 + y^2\n\\]', '\nx^2 + y^2\n', true],
    [String.raw`**温度\(x_1\)已更新**`, 'x_1', false],
    [String.raw`温度为 $x_1$。`, 'x_1', false],
    [String.raw`\(\frac{x}{2}\)`, String.raw`\frac{x}{2}`, false],
    ['\\[\r\nx^2 +\ty^2\r\n\\]', 'x^2 +\ty^2', true],
    ['\\[\nx^2\n\n+ y^2\n\\]\n\n继续检查。', 'x^2\n\n+ y^2', true],
    ['> \\[\n> x^2\n> \\]', 'x^2', true],
    ['- 温度为 \\(x_1\\)。', 'x_1', false],
  ])('renders math without source rewriting in %s', async (source, formula, display) => {
    const wrapper = render(source)
    await flushPromises()
    expect(wrapper.get('annotation[encoding="application/x-tex"]').text()).toBe(formula.trim())
    expect(wrapper.find('.katex-display').exists()).toBe(display)
  })

  it.each([
    String.raw`\(x_1 + 2\)`,
    String.raw`场景**“测试”**`,
  ])('keeps inline and fenced code literal: %s', async (code) => {
    const wrapper = render('`' + code + '`\n\n~~~text\n' + code + '\n~~~')
    await flushPromises()
    await expect.poll(() => wrapper.find('.code-content').exists()).toBe(true)
    expect(wrapper.get('p code').text()).toBe(code)
    expect(wrapper.get('.code-content').text()).toBe(code)
    expect(wrapper.find('strong, .katex').exists()).toBe(false)
  })

  it('keeps an unfinished streamed fence literal until and after closing', async () => {
    const code = String.raw`\(x_1\) 场景**“测试”**`
    const source = '```text\n' + code
    const wrapper = render(source)
    await flushPromises()
    await expect.poll(() => wrapper.find('.code-content').exists()).toBe(true)
    expect(wrapper.get('.code-content').text()).toBe(code)
    await wrapper.setProps({ source: source + '\n```' })
    await flushPromises()
    expect(wrapper.get('.code-content').text()).toBe(code)
    expect(wrapper.find('.katex, strong').exists()).toBe(false)
  })

  it('accepts every streamed prefix of multiline math without losing the following paragraph', async () => {
    const source = '\\[\nx^2\n\n+ y^2\n\\]  \n\n**继续检查。**'
    const wrapper = render('')
    for (let end = 1; end <= source.length; end++) {
      await wrapper.setProps({ source: source.slice(0, end) })
    }
    expect(wrapper.get('.katex-display annotation').text()).toBe('x^2\n\n+ y^2')
    expect(wrapper.get('strong').text()).toBe('继续检查。')
  })

  it('keeps indented code literal and renders malformed math without crashing', async () => {
    const code = String.raw`\(x_1\)`
    const wrapper = render('    ' + code + '\n\n' + String.raw`\(\unknowncommand{x}\) 后续内容。`)
    await flushPromises()
    await expect.poll(() => wrapper.find('.code-content').exists()).toBe(true)
    expect(wrapper.get('.code-content').text()).toBe(code)
    expect(wrapper.get('annotation').text()).toBe(String.raw`\unknowncommand{x}`)
    expect(wrapper.text()).toContain('后续内容。')
  })

  it('does not enable executable URLs through math commands', async () => {
    const wrapper = render(String.raw`\(\href{javascript:alert(1)}{x}\)`)
    await flushPromises()
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
  })

  it('does not turn escaped delimiters or link destinations into math', async () => {
    const wrapper = render(String.raw`\\(x\\) [文档](https://example.com/\(x\))`)
    await flushPromises()
    expect(wrapper.find('.katex').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBe('https://example.com/(x)')
    expect(wrapper.text()).toContain(String.raw`\(x\)`)
  })

  it.each(['component-json', 'echarts'])('shows %s fences as literal code without activating renderer plugins', async language => {
    const code = '{"title":"场景**“测试”**"}'
    const wrapper = render('```' + language + ' {}\n' + code + '\n```')
    await flushPromises()
    await expect.poll(() => wrapper.find('.code-content').exists()).toBe(true)
    expect(wrapper.get('.code-content').text()).toBe(code)
  })

  it('does not render executable links or raw HTML', async () => {
    const wrapper = render('参考**[unsafe](javascript:alert(document.domain))**。\n\n<img src=x onerror=alert(1)>')
    await flushPromises()

    const link = wrapper.get('a')
    expect(link.attributes('href')).toBeUndefined()
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('onerror')
  })

  it('blocks automatic remote images and hardens external links', async () => {
    const wrapper = render('参考**[docs](https://example.com/docs)**。\n\n![diagram](https://tracker.example/pixel)')
    await flushPromises()

    expect(wrapper.get('a').attributes()).toMatchObject({
      href: 'https://example.com/docs',
      target: '_blank',
      referrerpolicy: 'no-referrer'
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('diagram')
  })
})
