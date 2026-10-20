import { execFileSync } from 'node:child_process'
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createSceneCodec, SCENE_FILE_SCHEMA, SCENE_FILE_VERSION } from './portableScene'

// The codec is the trust boundary for imported scene files, so these tests assert that
// malformed input is REJECTED rather than coerced into a half-valid board. The translator is
// stubbed to echo its key, which keeps assertions about *which* rejection fired readable.
const t = (key: string, params?: Record<string, unknown>) =>
  params ? `${key}:${JSON.stringify(params)}` : key

const codec = createSceneCodec(t)

const minimalScene = () => ({
  schema: SCENE_FILE_SCHEMA,
  version: SCENE_FILE_VERSION,
  templates: [],
  devices: [],
  environmentVariables: [],
  rules: [],
  specs: []
})

describe('scene file envelope validation', () => {
  it('accepts an empty but well-formed scene', () => {
    const scene = codec.normalizeSceneFile(minimalScene())
    expect(scene.schema).toBe(SCENE_FILE_SCHEMA)
    expect(scene.version).toBe(SCENE_FILE_VERSION)
    expect(scene.devices).toEqual([])
  })

  it.each([
    ['a non-object payload', 'not-a-scene'],
    ['null', null],
    ['an array', []]
  ])('rejects %s instead of treating it as an empty scene', (_label, payload) => {
    expect(() => codec.normalizeSceneFile(payload)).toThrow(/sceneImportInvalidFile/)
  })

  it('rejects a foreign schema rather than guessing the format', () => {
    expect(() => codec.normalizeSceneFile({ ...minimalScene(), schema: 'some.other.tool' }))
      .toThrow(/sceneImportInvalidFile/)
  })

  it('reports the unsupported version instead of importing it best-effort', () => {
    expect(() => codec.normalizeSceneFile({ ...minimalScene(), version: SCENE_FILE_VERSION - 1 }))
      .toThrow(/sceneImportUnsupportedVersion/)
  })

  it('rejects an unknown top-level field so silent data loss cannot happen', () => {
    expect(() => codec.normalizeSceneFile({ ...minimalScene(), somethingElse: 1 }))
      .toThrow(/sceneImportUnknownField/)
  })

  it.each(['templates', 'devices', 'environmentVariables', 'rules', 'specs'])(
    'requires %s to be an array', field => {
      expect(() => codec.normalizeSceneFile({ ...minimalScene(), [field]: {} }))
        .toThrow(/sceneImportArrayRequired/)
    })
})

describe('scene specification variable conditions', () => {
  // A specification condition is reference-checked against the scene's devices, so the fixture
  // carries the device it names and the template that device instantiates.
  const sceneWithCondition = (condition: Record<string, unknown>) => ({
    ...minimalScene(),
    templates: [{
      name: 'Temperature Sensor',
      manifest: {
        Name: 'Temperature Sensor',
        Description: '',
        Modes: ['SensorState'],
        InitState: 'working',
        ImpactedVariables: [],
        InternalVariables: [
          { Name: 'temperature', IsInside: true, FalsifiableWhenCompromised: true, LowerBound: 0, UpperBound: 50, Trust: 'trusted', Privacy: 'public' }
        ],
        WorkingStates: [{ Name: 'working', Dynamics: [], Description: '', Trust: 'trusted', Privacy: 'public' }],
        APIs: []
      }
    }],
    devices: [{
      id: 'sensor-1',
      templateName: 'Temperature Sensor',
      label: 'Temperature Sensor',
      position: { x: 0, y: 0 },
      state: 'working',
      width: 120,
      height: 100
    }],
    specs: [{
      templateId: '1',
      aConditions: [condition],
      ifConditions: [],
      thenConditions: []
    }]
  })

  const variableCondition = (variableSource?: string) => ({
    deviceId: 'sensor-1',
    targetType: 'variable',
    key: 'temperature',
    ...(variableSource ? { variableSource } : {}),
    relation: '>',
    value: '28'
  })

  it('rejects a variable condition that does not say which value it means', () => {
    // Assigning a side would silently change what the imported specification asserts, and the two
    // sides differ exactly when a device is compromised.
    expect(() => codec.normalizeSceneFile(sceneWithCondition(variableCondition())))
      .toThrow(/sceneImportMissingField/)
    expect(() => codec.normalizeSceneFile(sceneWithCondition(variableCondition('whatever'))))
      .toThrow(/sceneImportInvalidEnum/)
  })

  it('keeps an explicit variable source through import', () => {
    const scene = codec.normalizeSceneFile(sceneWithCondition(variableCondition('reported')))
    expect(scene.specs[0].aConditions[0]).toMatchObject({ variableSource: 'reported' })
  })

  it('refuses the field on a condition type that has no such question', () => {
    expect(() => codec.normalizeSceneFile(sceneWithCondition({
      deviceId: 'sensor-1',
      targetType: 'state',
      key: 'state',
      variableSource: 'environment',
      relation: '=',
      value: 'open'
    }))).toThrow(/sceneImportUnexpectedField/)
  })
})

describe('scene device identity', () => {
  const sceneWithDeviceId = (id: string) => {
    const scene = JSON.parse(readFileSync(join(process.cwd(), '..', 'docs', 'examples', 'default-away-mode-unlock-scene.json'), 'utf8'))
    const previousId = scene.devices[0].id
    scene.devices[0].id = id
    for (const rule of scene.rules) {
      for (const source of rule.sources) if (source.fromId === previousId) source.fromId = id
      if (rule.toId === previousId) rule.toId = id
      if (rule.contentDevice === previousId) rule.contentDevice = id
    }
    for (const spec of scene.specs) {
      for (const condition of [...spec.aConditions, ...spec.ifConditions, ...spec.thenConditions]) {
        if (condition.deviceId === previousId) condition.deviceId = id
      }
    }
    return scene
  }

  it('preserves canonical device ids when normalizing a valid scene', () => {
    const scene = sceneWithDeviceId('canonical_id')
    const normalized = codec.normalizeSceneFile(scene)
    expect(normalized.devices.map(device => device.id)).toEqual(scene.devices.map((device: { id: string }) => device.id))
  })

  it.each(['canonical_id', 'internal space', 'internal\u0085space', 'internal\uFEFFspace'])('preserves legal id %j including internal whitespace', id => {
    expect(codec.normalizeSceneFile(sceneWithDeviceId(id)).devices[0].id).toBe(id)
  })

  it.each([[' ', ''], ['', ' '], [' ', ' '], ['\t', ''], ['', '\n'], ['\uFEFF', ''], ['', '\uFEFF'], ['\u0085', ''], ['', '\u0085']])('rejects surrounding whitespace %j/%j instead of renaming a valid device id', (prefix, suffix) => {
    const scene = sceneWithDeviceId(prefix + 'audit_node' + suffix)
    expect(() => codec.normalizeSceneFile(scene))
      .toThrow(/sceneImportIdentifierWhitespace/)
  })

  it('rejects duplicate device ids instead of silently keeping the last one', () => {
    const devices = [
      { id: 'dup', label: 'A' },
      { id: 'dup', label: 'B' }
    ] as any
    expect(() => codec.assertUniqueSceneDeviceIds(devices))
      .toThrow(/sceneImportDuplicateDevice/)
  })

  it('accepts distinct device ids', () => {
    const devices = [
      { id: 'a', label: 'A' },
      { id: 'b', label: 'B' }
    ] as any
    expect(() => codec.assertUniqueSceneDeviceIds(devices)).not.toThrow()
  })
})

describe('integer range validation', () => {
  it('accepts an in-range integer', () => {
    expect(codec.requireIntegerInRange(5, 'steps', 1, 10)).toBe(5)
  })

  it.each([
    ['a non-integer', 2.5],
    ['a string', '5'],
    ['NaN', Number.NaN],
    ['below the minimum', 0],
    ['above the maximum', 11]
  ])('rejects %s', (_label, value) => {
    expect(() => codec.requireIntegerInRange(value, 'steps', 1, 10)).toThrow(/integerBetween/)
  })

  it('falls back only for null/undefined, not for an invalid value', () => {
    expect(codec.optionalIntegerInRange(undefined, 'steps', 7, 1, 10)).toBe(7)
    expect(codec.optionalIntegerInRange(null, 'steps', 7, 1, 10)).toBe(7)
    expect(() => codec.optionalIntegerInRange(99, 'steps', 7, 1, 10)).toThrow(/integerBetween/)
  })
})

describe('canonicalization', () => {
  const authoredScene = () => {
    const raw = JSON.parse(readFileSync(join(__dirname, '../../../../docs/examples/dynamic-home-repair-scene.json'), 'utf8'))
    const device = raw.devices[0]
    const manifest = raw.templates.find((template: { name: string }) => template.name === device.templateName).manifest
    manifest.InternalVariables.push(...['setpoint', 'audit'].map(Name => ({
      Name, IsInside: true, FalsifiableWhenCompromised: false,
      LowerBound: 0, UpperBound: 30, Trust: 'trusted', Privacy: 'public'
    })))
    device.variables = [
      { name: 'setpoint', value: '20', trust: 'trusted' },
      { name: 'audit', value: '1', trust: 'untrusted' }
    ]
    device.privacies = [
      { name: 'setpoint', privacy: 'public' },
      { name: 'audit', privacy: 'private' }
    ]
    return codec.normalizeSceneFile(raw)
  }

  it('serializes imports without moving authored fields or conditions', () => {
    const scene = authoredScene()
    const portable = codec.serializeSceneFile(scene)
    expect(portable.templates.map(template => template.name)).toEqual(scene.templates.map(template => template.name))
    expect(portable.devices.map(device => device.id)).toEqual(scene.devices.map(device => device.id))
    expect(portable.environmentVariables).toEqual(scene.environmentVariables)
    expect(portable.devices[0].variables).toEqual(scene.devices[0].variables)
    expect(portable.devices[0].privacies).toEqual(scene.devices[0].privacies)
    const ruleOrder = (rules: typeof portable.rules | typeof scene.rules) => rules.map(rule => ({
      sources: rule.sources.map(source => [source.fromId, source.fromApi]), toId: rule.toId, toApi: rule.toApi
    }))
    const specificationOrder = (specs: typeof portable.specs | typeof scene.specs) => specs.map(spec => ({
      templateId: spec.templateId,
      conditions: [spec.aConditions, spec.ifConditions, spec.thenConditions]
        .map(conditions => conditions.map(condition => [condition.deviceId, condition.key, condition.value]))
    }))
    expect(ruleOrder(portable.rules)).toEqual(ruleOrder(scene.rules))
    expect(specificationOrder(portable.specs)).toEqual(specificationOrder(scene.specs))
  })

  it('keeps canonical exports stable when unordered collections change order', () => {
    const scene = authoredScene()
    const reordered = {
      ...scene,
      templates: [...scene.templates].reverse(),
      devices: [...scene.devices].reverse().map(device => ({
        ...device,
        ...(device.variables ? { variables: [...device.variables].reverse() } : {}),
        ...(device.privacies ? { privacies: [...device.privacies].reverse() } : {})
      })),
      environmentVariables: [...scene.environmentVariables].reverse()
    }
    const canonical = codec.canonicalizeSceneFile(scene)
    expect(JSON.stringify(canonical)).toBe(JSON.stringify(codec.canonicalizeSceneFile(reordered)))
    expect(canonical.devices.find(device => device.id === scene.devices[0].id)?.variables?.map(variable => variable.name))
      .toEqual(['audit', 'setpoint'])
  })

  it('is stable under key order so an exported scene round-trips byte-identically', () => {
    const a = codec.canonicalizeSceneFile(minimalScene() as any)
    const reordered = {
      specs: [], rules: [], environmentVariables: [], devices: [], templates: [],
      version: SCENE_FILE_VERSION, schema: SCENE_FILE_SCHEMA
    }
    const b = codec.canonicalizeSceneFile(reordered as any)
    expect(JSON.stringify(a)).toBe(JSON.stringify(b))
  })

  it('matches backend null omission while preserving non-null manifest values', () => {
    const explicitNull = {
      ...minimalScene(),
      templates: [{
        name: 'Alarm',
        manifest: {
          Name: 'Alarm',
          Description: '',
          InternalVariables: [{
            Name: 'level',
            IsInside: true,
            FalsifiableWhenCompromised: false,
            Trust: 'trusted',
            Privacy: 'public',
            LowerBound: 0,
            UpperBound: 1
          }],
          APIs: [{
            Name: 'off',
            StartState: 'on',
            EndState: 'off',
            Trigger: null,
            Signal: false,
            AcceptsContent: false
          }]
        }
      }]
    }
    const omitted = {
      ...minimalScene(),
      templates: [{
        name: 'Alarm',
        manifest: {
          APIs: [{
            AcceptsContent: false,
            Signal: false,
            EndState: 'off',
            Name: 'off',
            StartState: 'on'
          }],
          InternalVariables: [{
            UpperBound: 1,
            LowerBound: 0,
            Privacy: 'public',
            Trust: 'trusted',
            FalsifiableWhenCompromised: false,
            IsInside: true,
            Name: 'level'
          }],
          Description: '',
          Name: 'Alarm'
        }
      }]
    }

    const canonical = codec.canonicalizeSceneFile(explicitNull as any)
    expect(canonical).toEqual(codec.canonicalizeSceneFile(omitted as any))
    expect(canonical.templates[0].manifest).toMatchObject({
      Description: ''
    })
    expect(canonical.templates[0].manifest.APIs?.[0]).toMatchObject({
      AcceptsContent: false,
      Signal: false
    })
    expect(canonical.templates[0].manifest.APIs?.[0]).not.toHaveProperty('Trigger')
    expect(canonical.templates[0].manifest.InternalVariables?.[0]).toMatchObject({
      FalsifiableWhenCompromised: false,
      LowerBound: 0
    })
  })
})

/**
 * The scene files under `docs/examples/` are shipped fixtures: seven E2E specs and two backend
 * NuSMV tests read them, and a user following the docs imports them by hand. Nothing else asserted
 * they are still importable, so a template change that invalidated one was observable only as a
 * 15-minute E2E run failing on a confirmation dialog that never appeared.
 *
 * That happened: cd194bd gave Door RFID three WorkingStates, which turned a stateless device in
 * `default-rfid-access-scene.json` into a stateful one, and the codec rightly rejected it for
 * carrying no `state`. This test puts the failure a second away from the edit instead.
 */
describe('bundled example scenes', () => {
  it('every scene under docs/examples imports through the codec', () => {
    const directory = join(__dirname, '../../../../docs/examples')
    const files = readdirSync(directory).filter(file => file.endsWith('.json'))
    // Without this the whole test passes vacuously if the directory moves.
    expect(files.length, 'no example scenes were found').toBeGreaterThan(5)

    const rejected: string[] = []
    const empty: string[] = []
    for (const file of files) {
      const raw = JSON.parse(readFileSync(join(directory, file), 'utf8'))
      let scene: ReturnType<typeof codec.normalizeSceneFile>
      try {
        scene = codec.normalizeSceneFile(raw)
      } catch (error) {
        // Only a codec rejection belongs here. Asserting inside the try would let a failed
        // expectation be caught and reported as a rejection, describing the wrong defect.
        rejected.push(`${file}: ${(error as Error).message}`)
        continue
      }
      if (scene.devices.length === 0) empty.push(file)
    }

    expect(rejected, `example scenes the codec refuses:\n${rejected.join('\n')}`).toEqual([])
    expect(empty, `example scenes that imported no devices:\n${empty.join('\n')}`).toEqual([])
  })

  /**
   * The test above proves the committed fixtures import. It does not prove the generator still
   * produces them — and that is a separate failure mode, because `default-template-scenarios.md`
   * tells the reader to regenerate after a bundled template changes.
   *
   * It had already diverged: the generator's `rfid_1` definition carried no `state`, so regenerating
   * dropped the runtime a hand edit had added and produced a scene the codec rejects. The fixture was
   * right and the generator was stale, which is the direction no existing check could see.
   *
   * Runs the real script into a temp directory rather than re-deriving its output, since a
   * re-implementation would drift the same way.
   */
  it('the generator still reproduces every default-* scene it owns', () => {
    const repoRoot = join(__dirname, '../../../..')
    const script = join(repoRoot, 'scripts/generate-default-template-scenes.mjs')
    const outputDirectory = mkdtempSync(join(tmpdir(), 'iot-verify-scenes-'))
    try {
      execFileSync(process.execPath, [script, outputDirectory], { cwd: repoRoot, stdio: 'pipe' })

      const generated = readdirSync(outputDirectory).filter(file => file.endsWith('.json'))
      expect(generated.length, 'the generator wrote no scenes').toBeGreaterThan(3)

      const drifted: string[] = []
      for (const file of generated) {
        const fresh = readFileSync(join(outputDirectory, file), 'utf8')
        const committed = readFileSync(join(repoRoot, 'docs/examples', file), 'utf8')
        if (fresh !== committed) drifted.push(file)
      }
      expect(
        drifted,
        `regenerating these scenes would change the committed fixture, so one side is stale:\n${drifted.join('\n')}`
      ).toEqual([])
    } finally {
      rmSync(outputDirectory, { recursive: true, force: true })
    }
  })
})
