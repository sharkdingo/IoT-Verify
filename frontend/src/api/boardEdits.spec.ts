import { beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('./http', () => ({ default: { put: vi.fn() } }))
import http from './http'
import boardApi, { BOARD_RESPONSE_INCOMPLETE_CODE } from './board'
import type { RuleForm } from '@/types/rule'
import type { Specification } from '@/types/spec'

const rule: RuleForm = { id: '7', name: 'Hall light', sources: [{ fromId: 'sensor', itemType: 'variable', fromApi: 'motion', relation: '=', value: 'idle' }], toId: 'lamp', toApi: 'on' }
const ruleWire = { id: 7, ruleString: 'Hall light', conditions: [{ deviceName: 'sensor', targetType: 'variable', attribute: 'motion', relation: '=', value: 'idle' }], command: { deviceName: 'lamp', action: 'on', contentDevice: null, content: null } }
const spec: Specification = { id: 'spec-7', templateId: '1', templateLabel: 'Always', aConditions: [{ id: 'c', side: 'a', deviceId: 'lamp', deviceLabel: 'Hall light', targetType: 'state', key: 'state', relation: '=', value: 'off' }], ifConditions: [], thenConditions: [] }
beforeEach(() => vi.clearAllMocks())

describe('targeted authoring updates', () => {
  for (const kind of ['rule', 'spec'] as const) {
    const expected = kind === 'rule' ? rule : spec
    const affected = kind === 'rule' ? ruleWire : spec
    const update = () => kind === 'rule' ? boardApi.updateRule(rule, { ...rule, name: 'Edited' }) : boardApi.updateSpec(spec, { ...spec, templateId: '3' })
    const returned = () => kind === 'rule' ? { ...ruleWire, ruleString: 'Edited' } : { ...spec, templateId: '3', formula: '', devices: [] }
    const envelope = (overrides: Record<string, unknown> = {}) => ({ data: { data: { operation: 'updated', affectedItem: returned(), currentItems: [returned()], currentCount: 1, canUndo: true, canRedo: false, ...overrides } } })

    it(`${kind}: sends the original baseline and desired edit with the same identity`, async () => {
      vi.mocked(http.put).mockResolvedValue(envelope())
      const result = await update()
      expect(result.affectedItem.id).toBe(expected.id)
      const [url, body] = vi.mocked(http.put).mock.calls[0]
      expect(url).toBe(kind === 'rule' ? '/board/rules/7' : '/board/specs/spec-7')
      expect(body).toMatchObject({ expected: { id: affected.id }, desired: { id: affected.id } })
      if (kind === 'rule') expect(body).toMatchObject({ expected: { ruleString: 'Hall light' }, desired: { ruleString: 'Edited' } })
      else expect(body).toMatchObject({ expected: { templateId: '1' }, desired: { templateId: '3' } })
    })

    it.each([
      { affectedItem: { ...affected, id: kind === 'rule' ? 8 : 'other' } },
      { currentItems: [], currentCount: 0 },
      { currentItems: [{ ...affected, id: kind === 'rule' ? 8 : 'other' }] },
      { canUndo: false },
      { operation: 'created' }
    ])(`${kind}: rejects contradictory update evidence %j`, async corruption => {
      vi.mocked(http.put).mockResolvedValue(envelope(corruption))
      await expect(update()).rejects.toMatchObject({ code: BOARD_RESPONSE_INCOMPLETE_CODE })
    })

    it(`${kind}: preserves unchanged undo/redo availability`, async () => {
      vi.mocked(http.put).mockResolvedValue(envelope({ operation: 'unchanged', canUndo: false, canRedo: true }))
      await expect(update()).resolves.toMatchObject({ operation: 'unchanged', canUndo: false, canRedo: true })
    })

    it(`${kind}: rejects disagreement between affected item and the same current item`, async () => {
      vi.mocked(http.put).mockResolvedValue(envelope({ currentItems: [{ ...returned(), ...(kind === 'rule' ? { ruleString: 'Other' } : { templateId: '1' }) }] }))
      await expect(update()).rejects.toMatchObject({ code: BOARD_RESPONSE_INCOMPLETE_CODE })
    })
  }
})
