// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import * as Vue from 'vue'
import { compile, type ElementNode, type TemplateChildNode } from '@vue/compiler-dom'
import { parse } from '@vue/compiler-sfc'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import actualI18n from '@/assets/i18n'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const descriptor = parse(board).descriptor
const source = ts.createSourceFile('Board.ts', descriptor.scriptSetup!.content, ts.ScriptTarget.Latest, true)
const elements: ElementNode[] = []
const visit = (node: TemplateChildNode) => {
  if (node.type !== 1) return
  elements.push(node)
  node.children.forEach(visit)
}
descriptor.template!.ast!.children.forEach(visit)
const attribute = (node: ElementNode, name: string, value: string) => node.props.some(prop =>
  prop.type === 6 && prop.name === name && prop.value?.content === value)

const setup = (kind: 'verification' | 'simulation', mode: string, locale: string) => {
  const i18n = createI18n({ legacy: false, locale, messages: {
    en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
  } })
  const message = i18n.global.t(mode === 'ANY_UP_TO_BUDGET'
    ? 'app.attackBudgetSelectionInvalid' : 'app.attackExplicitPointsRequired', { selected: 0, limit: 2 })
  const ports: Record<string, any> = {
    computed: Vue.computed, t: i18n.global.t, hasModeledAttackEffect: Vue.ref(true),
    boardRunBlockedReason: Vue.ref(''), boardAttackSurface: { points: [] },
    isVerifying: false, isSimulating: false, synchronousSimulationRunning: Vue.ref(false),
    synchronousVerificationRunning: Vue.ref(false), isBoardDataReady: true, failedBoardDataKeys: [],
    traceAnimationState: Vue.ref({ visible: false }), simulationAnimationState: Vue.ref({ visible: false }),
    formalRunIssueMessage: () => '', attackBudgetMax: 2, attackSurfacePointCount: 2, attackBudgetIsCapped: false,
    setAttackMode: vi.fn(), toggleAttackPoint: vi.fn(), runVerification: vi.fn(), runSimulation: vi.fn(),
    [`${kind}Form`]: Vue.reactive({ isAttack: true, attackMode: mode, attackBudget: 0,
      selectedAttackPointKeys: [], isAsync: false }),
    [`${kind}ReadinessIssue`]: Vue.ref(null), [`${kind}AttackConfigurationError`]: Vue.ref(message)
  }
  for (const statement of source.statements) {
    if (!ts.isVariableStatement(statement)) continue
    for (const declaration of statement.declarationList.declarations) {
      const name = declaration.name.getText(source)
      if (!name.startsWith(`${kind}RunBlocked`)) continue
      const javascript = ts.transpileModule(statement.getText(source), {
        compilerOptions: { target: ts.ScriptTarget.ES2022 }
      }).outputText
      ports[name] = new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
    }
  }
  const unavailable = elements.find(node => attribute(node, 'data-testid', `${kind}-attack-unavailable`))!
  const attackOptions = elements.find(node => node.props.some(prop => prop.type === 7 && prop.name === 'if'
    && prop.exp?.type === 4 && prop.exp.content === `${kind}Form.isAttack && hasModeledAttackEffect`))!
  const panel = elements.find(node => attribute(node, 'data-testid', `${kind}-panel`))!
  const footer = panel.children.find(node => node.type === 1
    && attribute(node, 'class', 'board-run-panel__footer'))!
  const render = new Function('Vue', compile(`<section>${unavailable.loc.source}${attackOptions.loc.source}${footer.loc.source}</section>`,
    { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const wrapper = mount({ setup: () => ports, render }, { global: { stubs: { InfoTooltip: true } } })
  return { wrapper, ports, i18n, message }
}

describe('formal run parameter feedback', () => {
  for (const locale of ['en', 'zh-CN']) {
    it.each([
      ['verification', 'ANY_UP_TO_BUDGET'], ['verification', 'EXACT_POINTS'], ['simulation', 'EXACT_POINTS']
    ] as const)(`${locale}: shows the %s %s error once and describes the disabled submit`, (kind, mode) => {
      const { wrapper, message } = setup(kind, mode, locale)
      try {
        expect(wrapper.text().split(message)).toHaveLength(2)
        const button = wrapper.get(`[data-testid="run-${kind}"]`)
        expect(button.attributes('disabled')).toBeDefined()
        expect(wrapper.get(`[id="${button.attributes('aria-describedby')}"]`).text()).toBe(message)
        expect(wrapper.find(`[data-testid="${kind}-run-blocked-reason"]`).exists()).toBe(false)
      } finally { wrapper.unmount() }
    })

    it.each(['verification', 'simulation'] as const)(`${locale}: preserves a separate %s board-read failure`, async kind => {
      const { wrapper, ports, i18n, message } = setup(kind, 'EXACT_POINTS', locale)
      try {
        const boardError = i18n.global.t('app.boardDataLoadFailed')
        ports.boardRunBlockedReason.value = boardError
        await Vue.nextTick()
        expect(wrapper.text().split(message)).toHaveLength(2)
        expect(wrapper.get(`[data-testid="${kind}-run-blocked-reason"]`).text()).toBe(boardError)
        expect(wrapper.get(`[data-testid="run-${kind}"]`).attributes('aria-describedby'))
          .toBe(`${kind}-run-blocked-reason`)
        ports.boardRunBlockedReason.value = ''
        ports.hasModeledAttackEffect.value = false
        ports[`${kind}AttackConfigurationError`].value = i18n.global.t('app.attackNoModeledEffect')
        await Vue.nextTick()
        expect(wrapper.text().split(i18n.global.t('app.attackNoModeledEffect'))).toHaveLength(2)
        expect(wrapper.get(`[data-testid="run-${kind}"]`).attributes('aria-describedby'))
          .toBe(`${kind}-attack-unavailable`)
      } finally { wrapper.unmount() }
    })
  }
})
