import { describe, it, expect } from 'vitest'
import {
  assertRuleHasTrigger,
  duplicateRuleReasonKey,
  getNodeCenter,
  getLinkPoints,
  getSelfLoopPath,
  ruleSimilarityReasonKey,
  updateRulesForNodeRename
} from '../rule'
import { i18n } from '@/assets/i18n'
import type { DuplicateRuleReasonCode, RuleSimilarityReasonCode } from '@/types/rule'
import type { DeviceNode } from '@/types/node'

const expectNodeBoundary = (point: DeviceNode['position'], node: DeviceNode) => {
  const { x, y } = node.position
  expect(point.x).toBeGreaterThanOrEqual(x - 1e-6)
  expect(point.x).toBeLessThanOrEqual(x + node.width + 1e-6)
  expect(point.y).toBeGreaterThanOrEqual(y - 1e-6)
  expect(point.y).toBeLessThanOrEqual(y + node.height + 1e-6)
  expect(Math.min(Math.abs(point.x - x), Math.abs(point.x - x - node.width),
    Math.abs(point.y - y), Math.abs(point.y - y - node.height))).toBeLessThan(1e-6)
}

describe('rule utils', () => {
  it('getNodeCenter should compute center correctly', () => {
    const node = { position: { x: 10, y: 20 }, width: 100, height: 50 }
    const center = getNodeCenter(node as any)
    expect(center.x).toBe(10 + 100 / 2)
    expect(center.y).toBe(20 + 50 / 2)
  })

  it('getLinkPoints should produce endpoints between two nodes', () => {
    const from = { position: { x: 0, y: 0 }, width: 100, height: 50 }
    const to = { position: { x: 300, y: 100 }, width: 100, height: 50 }
    const { fromPoint, toPoint } = getLinkPoints(from as any, to as any)
    // fromPoint should be on boundary of from node and toPoint on boundary of to node
    expect(fromPoint.x).toBeGreaterThanOrEqual(from.position.x)
    expect(fromPoint.x).toBeLessThanOrEqual(from.position.x + from.width)
    expect(toPoint.x).toBeGreaterThanOrEqual(to.position.x)
    expect(toPoint.x).toBeLessThanOrEqual(to.position.x + to.width)
  })

  it.each([
    ['horizontal', { x: 420, y: 30 }],
    ['vertical', { x: 10, y: 340 }],
    ['downward diagonal', { x: 420, y: 340 }],
    ['upward diagonal', { x: 420, y: -240 }],
    ['leftward diagonal', { x: -320, y: 340 }]
  ] as const)('anchors both directions on differently sized nodes along a %s line', (_name, position) => {
    const from: DeviceNode = { id: 'from', templateName: 'Light', label: 'Source',
      position: { x: 20, y: 40 }, state: 'off', width: 100, height: 80 }
    const to: DeviceNode = { ...from, id: 'to', position: { ...position }, width: 120, height: 100 }
    const forward = getLinkPoints(from, to, 25)
    const reverse = getLinkPoints(to, from, 25)
    expectNodeBoundary(forward.fromPoint, from)
    expectNodeBoundary(forward.toPoint, to)
    expectNodeBoundary(reverse.fromPoint, to)
    expectNodeBoundary(reverse.toPoint, from)

    const origin = getNodeCenter(from)
    const target = getNodeCenter(to)
    const side = (point: DeviceNode['position']) =>
      (target.x - origin.x) * (point.y - origin.y) - (target.y - origin.y) * (point.x - origin.x)
    expect(side(forward.fromPoint)).toBeGreaterThan(0)
    expect(side(reverse.toPoint)).toBeLessThan(0)
    expect(side(forward.fromPoint)).toBeCloseTo(side(forward.toPoint))
    expect(side(reverse.fromPoint)).toBeCloseTo(side(reverse.toPoint))
  })

  it('bounds lane separation for small nodes and preserves a coincident center', () => {
    const from: DeviceNode = { id: 'from', templateName: 'Light', label: 'Source',
      position: { x: 0, y: 0 }, state: 'off', width: 80, height: 60 }
    const to: DeviceNode = { ...from, id: 'to', position: { x: 300, y: 300 } }
    const { fromPoint, toPoint } = getLinkPoints(from, to, 100)
    expectNodeBoundary(fromPoint, from)
    expectNodeBoundary(toPoint, to)
    expect(getLinkPoints(from, { ...from, id: 'overlapping' }, 25)).toEqual({
      fromPoint: getNodeCenter(from), toPoint: getNodeCenter(from)
    })
  })

  it('getSelfLoopPath should return a valid path string', () => {
    const node = { position: { x: 50, y: 50 }, width: 90, height: 60 }
    const path = getSelfLoopPath(node as any)
    expect(typeof path).toBe('string')
    expect(path.startsWith('M')).toBe(true)
    expect(path.includes('C')).toBe(true)
  })

  it('updateRulesForNodeRename updates labels and returns true when changed', () => {
    const rules: any[] = [
      { id: 'e1', from: 'n1', to: 'n2', fromLabel: 'old', toLabel: 't' },
      { id: 'e2', from: 'n3', to: 'n1', fromLabel: 'f', toLabel: 'old' }
    ]
    const changed = updateRulesForNodeRename(rules as any, 'n1', 'newLabel')
    expect(changed).toBe(true)
    expect(rules[0].fromLabel).toBe('newLabel')
    expect(rules[1].toLabel).toBe('newLabel')
  })

  it('allows full-state triggers without a UI attribute but still requires a value', () => {
    expect(() => assertRuleHasTrigger({
      sources: [
        { fromId: 'door-1', fromApi: '', itemType: 'state', relation: '=', value: 'closed;locked' }
      ],
      toId: 'alarm-1',
      toApi: 'siren'
    })).not.toThrow()

    expect(() => assertRuleHasTrigger({
      sources: [
        { fromId: 'door-1', fromApi: '', itemType: 'state', relation: '=', value: '   ' }
      ],
      toId: 'alarm-1',
      toApi: 'siren'
    })).toThrow('state trigger requires relation and value')
  })

  it('uses a one-based display position instead of exposing a persisted rule id', () => {
    expect(() => assertRuleHasTrigger({
      id: '4821',
      sources: [],
      toId: 'alarm-1',
      toApi: 'siren'
    }, 2)).toThrow('Rule 3: at least one trigger source is required')
  })

  it('maps deterministic and AI similarity reasons without displaying backend prose', () => {
    expect(duplicateRuleReasonKey('SAME_TRIGGER_SHAPE_DIFFERENT_VALUES'))
      .toBe('app.ruleCheckSameShape')
    expect(ruleSimilarityReasonKey('AI_HIGH_SCORE_REVIEW'))
      .toBe('app.ruleSimilarityHighScoreReview')
  })

  // Spot-checking one code per table would let a new backend code ship with no message behind its
  // key, which renders the raw key to the user in place of the reason.
  const duplicateCodes: DuplicateRuleReasonCode[] = [
    'NO_EXISTING_RULES',
    'EXACT_MATCH',
    'TRIGGER_SET_CONTAINS_OTHER',
    'SAME_TRIGGER_SHAPE_DIFFERENT_VALUES',
    'PARTIAL_TRIGGER_OVERLAP',
    'NO_MATCHING_SIGNATURE'
  ]
  const similarityCodes: RuleSimilarityReasonCode[] = [
    'NO_EXISTING_RULES',
    'AI_DUPLICATE',
    'AI_SIMILAR',
    'AI_HIGH_SCORE_REVIEW',
    'AI_NO_SIGNIFICANT_SIMILARITY'
  ]

  it.each(['zh-CN', 'en'] as const)('has a %s message for every rule-check reason code', locale => {
    const missing = [
      ...duplicateCodes.map(duplicateRuleReasonKey),
      ...similarityCodes.map(ruleSimilarityReasonKey)
    ].filter(key => !i18n.global.te(key, locale))
    expect(missing).toEqual([])
  })
})


