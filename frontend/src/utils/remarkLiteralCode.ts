import type { Plugin } from 'unified'
import type { Root } from 'mdast'

export const remarkLiteralCode: Plugin = function () {
  // vue-mdr activates these fences before user remark transforms run. The chat
  // supplies no interactive component/chart renderers: these are literal code.
  const parse = this.parser!
  this.parser = (source, file) => {
    const tree = parse(source, file)
    const visit = (node: Root | Root['children'][number]): void => {
      if (node.type === 'code' && (node.lang === 'component-json' || node.lang === 'echarts')) {
        node.lang = 'json'
      }
      if ('children' in node) node.children.forEach(visit)
    }
    visit(tree as Root)
    return tree
  }
}
