import { describe, expect, it } from 'vitest'
import { verdictVariableSourceKeys } from './verdictVariableSource'
import type { SpecCondition, Specification } from '@/types/spec'

const condition = (overrides: Partial<SpecCondition>): SpecCondition => ({
    id: 'c1',
    side: 'a',
    deviceId: 'sensor_1',
    deviceLabel: 'Hall sensor',
    targetType: 'variable',
    key: 'temperature',
    relation: '>',
    value: '30',
    ...overrides
} as SpecCondition)

const spec = (conditions: SpecCondition[]): Specification => ({ name: 'Safety requirement',
    id: 'spec-1',
    templateId: '3',
    templateLabel: 'Never',
    formula: '',
    devices: [],
    aConditions: conditions,
    ifConditions: [],
    thenConditions: []
} as unknown as Specification)

describe('verdictVariableSourceKeys', () => {
    it('names the reading a verdict answered about', () => {
        // Two specs asking different questions of one key share a template label, so the verdict rows are
        // otherwise identical while carrying opposite verdicts — the case the distinction exists for.
        expect(verdictVariableSourceKeys(spec([condition({ variableSource: 'environment' })])))
            .toEqual(['app.specVariableSourceEnvironmentShort'])
        expect(verdictVariableSourceKeys(spec([condition({ variableSource: 'reported' })])))
            .toEqual(['app.specVariableSourceReportedShort'])
    })

    it('shows both readings when one specification mixes them', () => {
        // Not collapsed to one: a spec asserting the home and the report disagree is exactly the
        // falsified-reading case, and that is when naming both matters most.
        expect(verdictVariableSourceKeys(spec([
            condition({ variableSource: 'environment' }),
            condition({ id: 'c2', variableSource: 'reported' })
        ]))).toEqual([
            'app.specVariableSourceEnvironmentShort',
            'app.specVariableSourceReportedShort'
        ])
    })

    it('de-duplicates a reading used by several conditions', () => {
        expect(verdictVariableSourceKeys(spec([
            condition({ variableSource: 'reported' }),
            condition({ id: 'c2', key: 'humidity', variableSource: 'reported' })
        ]))).toEqual(['app.specVariableSourceReportedShort'])
    })

    it('reports an unanswered question rather than dropping it', () => {
        // A verdict about a condition that never chose is exactly what the user must not mistake for a
        // decided one, so it is surfaced rather than filtered out.
        expect(verdictVariableSourceKeys(spec([condition({ variableSource: undefined })])))
            .toEqual(['app.specVariableSourceUnresolvedShort'])
    })

    it('ignores non-variable conditions and a missing specification', () => {
        expect(verdictVariableSourceKeys(spec([
            condition({ targetType: 'state', key: 'state', value: 'on', variableSource: undefined })
        ]))).toEqual([])
        expect(verdictVariableSourceKeys(undefined)).toEqual([])
        expect(verdictVariableSourceKeys(null)).toEqual([])
    })

    it('reads every condition group, not only the A side', () => {
        const implication = spec([])
        implication.ifConditions = [condition({ side: 'if', variableSource: 'environment' })]
        implication.thenConditions = [condition({ id: 'c3', side: 'then', variableSource: 'reported' })]

        expect(verdictVariableSourceKeys(implication)).toEqual([
            'app.specVariableSourceEnvironmentShort',
            'app.specVariableSourceReportedShort'
        ])
    })
})
