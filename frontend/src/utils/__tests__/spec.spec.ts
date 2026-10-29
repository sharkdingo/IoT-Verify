import { describe, expect, it } from 'vitest'
import { buildSpecFormula, isSameSpecification, specFormulaKindFromTemplate } from '../spec'
import type { DeviceNode } from '@/types/node'
import type { DeviceTemplate } from '@/types/device'
import type { Specification } from '@/types/spec'

describe('spec formula preview', () => {
  const nodes: DeviceNode[] = [
    {
      id: 'ac-1',
      label: 'Living Room AC',
      templateName: 'Air Conditioner',
      position: { x: 0, y: 0 },
      state: 'on;cool',
      width: 160,
      height: 120
    },
    {
      id: 'sensor-1',
      label: 'Temperature Sensor',
      templateName: 'Temperature Sensor',
      position: { x: 0, y: 0 },
      state: 'working',
      width: 120,
      height: 100
    }
  ]

  const deviceTemplates: DeviceTemplate[] = [
    {
      name: 'Air Conditioner',
      manifest: {
        Name: 'Air Conditioner',
        Description: '',
        Modes: ['Power', 'Mode'],
        InitState: 'off;idle',
        ImpactedVariables: [],
        InternalVariables: [],
        WorkingStates: [
          { Name: 'off;idle', Dynamics: [], Description: '', Trust: 'trusted', Privacy: 'public' },
          { Name: 'on;cool', Dynamics: [], Description: '', Trust: 'trusted', Privacy: 'public' }
        ],
        APIs: []
      }
    },
    {
      name: 'Temperature Sensor',
      manifest: {
        Name: 'Temperature Sensor',
        Description: '',
        Modes: ['SensorState'],
        InitState: 'working',
        ImpactedVariables: ['temperature'],
        InternalVariables: [
          { Name: 'temperature', IsInside: false, FalsifiableWhenCompromised: true, LowerBound: 0, UpperBound: 50, Trust: 'trusted', Privacy: 'public' },
          { Name: 'humidity', IsInside: false, FalsifiableWhenCompromised: true, LowerBound: 0, UpperBound: 100, Trust: 'trusted', Privacy: 'public' }
        ],
        WorkingStates: [
          { Name: 'working', Dynamics: [], Description: '', Trust: 'trusted', Privacy: 'public' }
        ],
        APIs: []
      }
    }
  ]

  const context = { nodes, deviceTemplates }

  it('expands full-state conditions using the selected device template modes', () => {
    const formula = buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'c1',
        side: 'a',
        deviceId: 'ac-1',
        deviceLabel: 'Living Room AC',
        targetType: 'state',
        key: 'state',
        relation: '=',
        value: 'on;cool'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG("Living Room AC".state = "on;cool")')
  })

  it('previews an environment-source variable as the value in the home', () => {
    const formula = buildSpecFormula({
      templateId: '5',
      templateLabel: 'Response',
      aConditions: [],
      ifConditions: [{
        id: 'if1',
        side: 'if',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource: 'environment',
        key: 'temperature',
        relation: '>',
        value: '28'
      }],
      thenConditions: [{
        id: 'then1',
        side: 'then',
        deviceId: 'ac-1',
        deviceLabel: 'Living Room AC',
        targetType: 'mode',
        key: 'Mode',
        relation: '=',
        value: 'cool'
      }]
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG((Environment."temperature" > 28) -> AF("Living Room AC"."Mode" = "cool"))')
  })

  it('renders the two variable questions distinctly, from the condition rather than the manifest', () => {
    // `temperature` is declared shared here, so the manifest alone cannot tell the two apart. This
    // is the defect the field fixes: the preview used to read as the pool value either way, so a
    // condition the author pinned to one device's reading looked like a claim about the home.
    const preview = (variableSource: 'environment' | 'reported') => buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource,
        key: 'temperature',
        relation: '>',
        value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(preview('environment')).toBe('CTL AG(Environment."temperature" > 28)')
    // `<device>."<key>"`, matching the emitted identifier and the backend's own formula preview. The
    // reading is conveyed by the badge and the plain-language sentence, not by a `.reported.` segment
    // that exists in no model and leaks internal vocabulary into a formula the user reads.
    expect(preview('reported')).toBe('CTL AG("Temperature Sensor"."temperature" > 28)')
    expect(preview('environment')).not.toBe(preview('reported'))
  })

  it('renders a condition with no recorded variable source as unresolved', () => {
    // Defaulting either way would present a formula the author never authorised, so the preview
    // must not look valid. The run is blocked separately.
    const formula = buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        key: 'temperature',
        relation: '>',
        value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG(<unresolved>."temperature" > 28)')
  })

  it('previews relation aliases with the canonical NuSMV operators', () => {
    const formula = buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource: 'environment',
        key: 'temperature',
        relation: 'GTE',
        value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG(Environment."temperature" >= 28)')
  })

  it('names the device in a template-7 trust predicate even for an environment reading', () => {
    /*
     * A trust label is emitted per device: the generator emits `<device>.trust_<key>` whatever the reading,
     * and no pool-level `trust_a_<key>` exists in the model. Reusing the VALUE target rendered this as
     * `controlSource(Environment."temperature")` — a label NuSMV never declares — so the preview claimed a
     * property about the home's own provenance while the check was against one named device's label. The
     * preview must name that device. It is not a free choice of subject, though: for a shared value the
     * pool writes every declaring device the same label, so which one is named changes what is proved only
     * under attack modelling. The value half stays `Environment."..."`, because that IS the pool value.
     */
    const formula = buildSpecFormula({
      templateId: '7',
      templateLabel: 'Safety',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource: 'environment',
        key: 'temperature',
        relation: '>',
        value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    // Mixed subjects on purpose, matching the emitted formula: the home's value, that device's label.
    expect(formula).toContain('Environment."temperature" > 28')
    expect(formula).toContain('controlSource("Temperature Sensor"."temperature") = untrusted')
    expect(formula).not.toContain('controlSource(Environment.')
  })

  it('previews template 7 safety specs with a concrete trust predicate', () => {
    const formula = buildSpecFormula({
      templateId: '7',
      templateLabel: 'Safety',
      aConditions: [{
        id: 'a1',
        side: 'a',
          deviceId: 'sensor-1',
          deviceLabel: 'Temperature Sensor',
          targetType: 'variable',
          variableSource: 'environment',
          key: 'temperature',
          relation: '>',
          value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG NOT (Environment."temperature" > 28 AND controlSource("Temperature Sensor"."temperature") = untrusted)')
  })

  it('previews every reliability label that contributes to a multi-mode safety state', () => {
    const formula = buildSpecFormula({
      templateId: '7',
      templateLabel: 'Untrusted-source safety',
      aConditions: [{
        id: 'a-multi-mode',
        side: 'a',
        deviceId: 'ac-1',
        deviceLabel: 'Living Room AC',
        targetType: 'state',
        key: 'state',
        relation: '=',
        value: 'on;cool'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG NOT ("Living Room AC".state = "on;cool" AND controlSource("Living Room AC".state) = untrusted)')
    expect(formula).not.toContain('ac_1')
  })

  it('taints a multi-condition safety event when any source is untrusted', () => {
    const formula = buildSpecFormula({
      templateId: '7',
      templateLabel: 'Untrusted-source safety',
      aConditions: [
        {
          id: 'hot',
          side: 'a',
          deviceId: 'sensor-1',
          deviceLabel: 'Temperature Sensor',
          targetType: 'variable',
          variableSource: 'environment',
          key: 'temperature',
          relation: '>',
          value: '28'
        },
        {
          id: 'humid',
          side: 'a',
          deviceId: 'sensor-1',
          deviceLabel: 'Temperature Sensor',
          targetType: 'variable',
          variableSource: 'environment',
          key: 'humidity',
          relation: '>',
          value: '70'
        }
      ],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG NOT (Environment."temperature" > 28 AND Environment."humidity" > 70 AND (controlSource("Temperature Sensor"."temperature") = untrusted OR controlSource("Temperature Sensor"."humidity") = untrusted))')
  })

  it('treats a_ as part of a real environment variable name in formula previews', () => {
    // No manifest fixture: the preview reads the condition's own `variableSource`, so a key that merely
    // *looks* generated (`a_temperature`) is treated as the literal name the author declared. This used to
    // need a template whose InternalVariables said the key was shared — that lookup is gone.
    const formula = buildSpecFormula({
      templateId: '7',
      templateLabel: 'Safety',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource: 'environment',
        key: 'a_temperature',
        relation: '>',
        value: '28'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, {
      nodes
    })

    expect(formula).toBe('CTL AG NOT (Environment."a_temperature" > 28 AND controlSource("Temperature Sensor"."a_temperature") = untrusted)')
    expect(formula).not.toContain('a_a_temperature')
  })

  it('treats variableSource as part of specification condition identity', () => {
    // The same key with the other source is a different claim, so the two specifications must not
    // be deduplicated into one.
    const spec = (variableSource: 'environment' | 'reported'): Specification => ({ name: 'Safety requirement',
      id: 'spec-1',
      templateId: '1',
      templateLabel: 'Always',
      devices: [],
      formula: '',
      ifConditions: [],
      thenConditions: [],
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'variable',
        variableSource,
        key: 'temperature',
        relation: '>',
        value: '28'
      }]
    })

    expect(isSameSpecification(spec('environment'), spec('environment'))).toBe(true)
    expect(isSameSpecification(spec('environment'), spec('reported'))).toBe(false)
  })

  it('treats targetType as part of specification condition identity', () => {
    const base = { name: 'Safety requirement',
      id: 'spec-1',
      templateId: '1',
      templateLabel: 'Always',
      devices: [],
      formula: '',
      ifConditions: [],
      thenConditions: []
    } satisfies Omit<Specification, 'aConditions'>

    const trustSpec: Specification = {
      ...base,
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'trust',
        propertyScope: 'variable',
        key: 'temperature',
        relation: '=',
        value: 'trusted'
      }]
    }
    const privacySpec: Specification = {
      ...base,
      aConditions: [{
        ...trustSpec.aConditions[0],
        targetType: 'privacy',
        value: 'public'
      }]
    }

    expect(isSameSpecification(trustSpec, privacySpec)).toBe(false)
  })

  it('checks the label of the currently active mode state without exposing a generated state key', () => {
    const formula = buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'a-state-trust',
        side: 'a',
        deviceId: 'ac-1',
        deviceLabel: 'Living Room AC',
        targetType: 'trust',
        propertyScope: 'state',
        key: 'Power',
        relation: '=',
        value: 'trusted'
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(formula).toBe('CTL AG(controlSource("Living Room AC".current "Power" state) = trusted)')
    expect(formula).not.toContain('trust_Power_')
  })

  it('treats condition order, display caches, and relation aliases as the same specification', () => {
    const first: Specification = { name: 'Safety requirement',
      id: 'spec-1',
      templateId: '1',
      templateLabel: 'Always',
      formula: 'display cache one',
      devices: [],
      aConditions: [
        {
          id: 'a1', side: 'a', deviceId: 'sensor-1', deviceLabel: 'Old label',
          targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: 'GTE', value: '30'
        },
        {
          id: 'a2', side: 'a', deviceId: 'sensor-1', deviceLabel: 'Old label',
          targetType: 'mode', key: 'Mode', relation: 'in', value: 'away, home'
        }
      ],
      ifConditions: [],
      thenConditions: []
    }
    const second: Specification = {
      ...first,
      id: 'spec-2',
      formula: 'different preview cache',
      aConditions: [
        { ...first.aConditions[1], id: 'other-2', deviceLabel: 'New label', value: 'home|away' },
        { ...first.aConditions[0], id: 'other-1', deviceLabel: 'New label', relation: '>=' }
      ]
    }

    expect(isSameSpecification(first, second)).toBe(true)
  })

  it.each(['in', 'not in'])('compares API %s sets independently of case, order and separators', relation => {
    const spec = (value: string): Specification => ({
      id: 'event-spec', name: 'Event requirement', templateId: '1', templateLabel: 'Always',
      aConditions: [{ id: 'event', side: 'a', deviceId: 'light', deviceLabel: 'Light',
        targetType: 'api', key: 'on', relation, value }], ifConditions: [], thenConditions: []
    })
    for (const equivalent of ['false | true', ' TRUE; FALSE ', 'FALSE,TRUE']) {
      expect(isSameSpecification(spec('TRUE, FALSE'), spec(equivalent)), equivalent).toBe(true)
    }
    expect(isSameSpecification(spec('TRUE, FALSE'), spec('FALSE'))).toBe(false)
    const opposite = spec('TRUE, FALSE')
    opposite.aConditions[0].relation = relation === 'in' ? 'not in' : 'in'
    expect(isSameSpecification(spec('TRUE, FALSE'), opposite)).toBe(false)
  })

  it('spells boolean and label literals the way the backend preview does', () => {
    /*
     * The backend renders NuSMV booleans uppercase and trust/privacy labels lowercase
     * (`SpecificationFormulaPreview.value`). Folding all six to lowercase here made the same condition
     * display as `true` in the client and `TRUE` in a verdict — one formula, two spellings, from the two
     * halves of one feature. These strings are the contract; if the backend's change, this reddens.
     */
    const preview = (value: string) => buildSpecFormula({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        id: 'a1',
        side: 'a',
        deviceId: 'sensor-1',
        deviceLabel: 'Temperature Sensor',
        targetType: 'mode',
        key: 'Enabled',
        relation: '=',
        value
      }],
      ifConditions: [],
      thenConditions: []
    } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context)

    expect(preview('true')).toBe('CTL AG("Temperature Sensor"."Enabled" = TRUE)')
    expect(preview('TRUE')).toBe('CTL AG("Temperature Sensor"."Enabled" = TRUE)')
    expect(preview('Untrusted')).toBe('CTL AG("Temperature Sensor"."Enabled" = untrusted)')
  })
})

/**
 * The chip beside the spec builder's formula preview read "Model" for every template.
 *
 * `ControlCenter` derived it by looking for a `CTLSPEC`/`LTLSPEC` prefix, which `buildSpecFormula`
 * does not emit — it writes `CTL AG(...)` / `LTL G(...)`, and the keyword form appears only in a
 * trace's `checkedExpression`. So the label contradicted the formula printed immediately next to it,
 * in the one surface where this distinction is being taught. `DeviceDialog` had a second, correct
 * copy keyed on the template; the backend's `formulaKind` is a third. One derivation now.
 */
describe('specification formula kind', () => {
  it('names the logic each template is checked in', () => {
    // Template 6 (persistence, G(IF -> F G(THEN))) is the only LTL one, matching the backend's
    // `"6".equals(templateId) ? "LTL" : "CTL"` fallback and the generator that emits it.
    expect(specFormulaKindFromTemplate('6')).toBe('LTL')
    for (const templateId of ['1', '2', '3', '4', '5', '7']) {
      expect(specFormulaKindFromTemplate(templateId), `template ${templateId}`).toBe('CTL')
    }
  })

  it('agrees with the prefix of the formula the builder emits for the same template', () => {
    // The regression this replaces: reading the formula text and reading the template must not
    // disagree. Asserting against `buildSpecFormula` output means a change to either one reddens.
    for (const templateId of ['1', '2', '3', '4', '5', '6', '7']) {
      const formula = buildSpecFormula({
        templateId: templateId as Specification['templateId'],
        templateLabel: '',
        aConditions: [],
        ifConditions: [],
        thenConditions: []
      } satisfies Pick<Specification, 'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>)
      const kind = specFormulaKindFromTemplate(templateId)
      expect(formula.startsWith(`${kind} `), `template ${templateId}: ${kind} vs ${formula}`).toBe(true)
    }
  })

  it('returns null for an unknown template, so a caller can fall back rather than guess', () => {
    // Null rather than a default 'CTL': claiming a logic for a spec whose template is unrecorded
    // would be a statement about formal evidence the data does not support.
    expect(specFormulaKindFromTemplate(undefined)).toBeNull()
    expect(specFormulaKindFromTemplate('')).toBeNull()
    expect(specFormulaKindFromTemplate('99')).toBeNull()
  })
})
