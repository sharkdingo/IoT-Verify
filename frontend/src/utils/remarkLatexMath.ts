import type { Plugin } from 'unified'
import type { Root } from 'mdast'
import type { Extension, State, Tokenizer } from 'micromark-util-types'
import type {} from 'micromark-extension-math'

// Parse LaTeX delimiters in Markdown text, where code, escapes and URL destinations
// have already been distinguished. Rewriting the raw message corrupts those literals.
const tokenizeLatexMath = (flow: boolean): Tokenizer => function (effects, ok, nok) {
  let closing: number
  const start: State = code => {
    effects.enter('mathText')
    effects.enter('mathTextSequence')
    effects.consume(code)
    return open
  }
  const open: State = code => {
    if (code !== 91 && (flow || code !== 40)) return nok(code)
    closing = code === 40 ? 41 : 93
    effects.consume(code)
    effects.exit('mathTextSequence')
    return body
  }
  const body: State = code => {
    if (code === null) return nok(code)
    if (code <= -3) {
      effects.enter('lineEnding')
      effects.consume(code)
      effects.exit('lineEnding')
      return body
    }
    if (code === 92) {
      effects.enter('mathTextSequence')
      effects.consume(code)
      return close
    }
    effects.enter('mathTextData')
    return data(code)
  }
  const data: State = code => {
    if (code === null || code <= -3 || code === 92) {
      effects.exit('mathTextData')
      return body(code)
    }
    effects.consume(code)
    return data
  }
  const close: State = code => {
    if (code === closing) {
      effects.consume(code)
      effects.exit('mathTextSequence')
      effects.exit('mathText')
      return flow ? after : ok
    }
    // A non-closing backslash belongs to the formula (e.g. \frac or \\).
    effects.exit('mathTextSequence').type = 'mathTextData'
    if (code === 92) {
      effects.enter('mathTextData')
      effects.consume(code)
      effects.exit('mathTextData')
      return body
    }
    return body(code)
  }
  const after: State = code => {
    if (code === null || code <= -3) return ok(code)
    if (code === 32 || code === -2 || code === -1) {
      effects.enter('whitespace')
      effects.consume(code)
      effects.exit('whitespace')
      return after
    }
    return nok(code)
  }
  return start
}

export const remarkLatexMath: Plugin = function () {
  const data = this.data() as { micromarkExtensions?: Extension[] }
  ;(data.micromarkExtensions ??= []).push({
    text: { 92: { name: 'latexMath', tokenize: tokenizeLatexMath(false) } },
    flow: { 92: { name: 'latexMathDisplay', tokenize: tokenizeLatexMath(true), concrete: true } }
  })

  return (tree, file) => {
    const source = String(file.value)
    const visit = (node: Root | Root['children'][number]): void => {
      if (node.type === 'inlineMath'
          && source.slice(node.position?.start.offset, (node.position?.start.offset ?? 0) + 2) === '\\[') {
        node.data = { ...node.data, hProperties: { className: ['language-math', 'math-display'] } }
      }
      if ('children' in node) node.children.forEach(visit)
    }
    visit(tree as Root)
  }
}
