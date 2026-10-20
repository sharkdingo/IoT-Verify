import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import {
  canonicalNaturalChangeRate,
  getDeviceIconUrl,
  indexDeviceTemplates,
  normalizeAssetFolder,
  MANIFEST_VALIDATION_MESSAGE_KEYS,
  naturalChangeCandidateValues,
  naturalChangeDeltas,
  parseNaturalChangeRate,
  resolveImpactEnvironmentDefinition,
  validateManifest
} from '../device'
import type { DeviceManifest, DeviceTemplate } from '@/types/device'

describe('template lookup index', () => {
  it('normalizes both names, ignores blank aliases and retains the first matching template', () => {
    const first: DeviceTemplate = { name: ' Thermostat ', manifest: { Name: 'Thermostat alias' } }
    const second: DeviceTemplate = { name: 'THERMOSTAT ALIAS', manifest: { Name: 'Other' } }
    const blank: DeviceTemplate = { name: ' ', manifest: { Name: '' } }
    const index = indexDeviceTemplates([first, second, blank])
    expect(index.get('thermostat')).toBe(first)
    expect(index.get('thermostat alias')).toBe(first)
    expect(index.get('other')).toBe(second)
    expect(index.has('')).toBe(false)
    expect(index.get('missing')).toBeUndefined()
  })

  it('reads aliases once when indexing rather than for every rendered node', () => {
    let reads = 0
    const templates = Array.from({ length: 100 }, (_, index) => ({
      get name() { reads++; return `Template ${index}` },
      manifest: { get Name() { reads++; return `Alias ${index}` } }
    }))
    const indexed = indexDeviceTemplates(templates)
    for (let iteration = 0; iteration < 20; iteration++) {
      templates.forEach((template, index) => expect(indexed.get(`alias ${index}`)).toBe(template))
    }
    expect(reads).toBe(templates.length * 2)
  })
})

describe('device icon resolution', () => {
  const decodeDataSvg = (uri: string) =>
    decodeURIComponent(uri.slice(uri.indexOf(',') + 1))

  it('does not enumerate bundled artwork for each generated-icon fallback', () => {
    const expected = getDeviceIconUrl('Custom sensor', 'unlisted')
    const enumerate = vi.spyOn(Object, 'keys')
    let results: string[]
    let enumerations: number
    try {
      results = Array.from({ length: 50 }, () => getDeviceIconUrl('Custom sensor', 'unlisted'))
      enumerations = enumerate.mock.calls.length
    } finally {
      enumerate.mockRestore()
    }
    expect(results.every(icon => icon === expected)).toBe(true)
    expect(enumerations).toBe(0)
  })

  it('uses bundled assets for default templates without manifest icons', () => {
    const icon = getDeviceIconUrl('Window Shade', 'closed')
    const svg = decodeDataSvg(icon)

    expect(icon).toMatch(/^data:image\/svg\+xml/)
    expect(svg).toContain("viewBox='0 0 40 40'")
    expect(svg).toContain("M10 16H30")
  })

  // Every bundled working state must render the artwork drawn for THAT state. Asserting identity
  // against the file on disk, rather than "did not fall through", is what makes this falsifiable:
  // `getDeviceIconUrl` serves the alphabetically first file in the folder before it ever reaches the
  // generated placeholder, so a state with no artwork looks exactly like a state with artwork unless
  // the content is compared. A weaker version of this test that only checked for a generated icon and
  // for two states colliding passed with `Home_Mode/sleep;idle.svg` deleted, because that state then
  // matched `Working.svg`, which no sibling uses.
  //
  // A state name may contain a space and a filename may not, so the assets encode it as "_"
  // ("taking photo" -> taking_photo.svg). Accept either spelling of the state's own file, and
  // nothing else.
  //
  // The identity checks below are each other's blind spot when resolution dies completely: the
  // generated placeholder embeds the state name, so every state gets a DISTINCT data URI and
  // neither the sibling-collision check nor the generic-fallback comparison fires. Assert that
  // real artwork was reached first — a positive claim that no dead pipeline can satisfy.
  it('renders every bundled working state with the artwork drawn for that state', () => {
    const directory = resolve(process.cwd(), '../backend/src/main/resources/deviceTemplate')
    const assetsRoot = resolve(process.cwd(), 'src/assets')
    const templates = readdirSync(directory).filter(file => file.endsWith('.json'))
    // A scan that matches nothing asserts nothing.
    expect(templates.length, 'no bundled templates were scanned').toBeGreaterThan(40)

    // The icon URL cannot be compared against the file on disk: Vite inlines some of these SVGs as
    // minified percent-encoded text (rewriting " to ') and others as raw base64, so equal artwork
    // does not imply equal bytes. Every check below is therefore independent of that encoding — the
    // fall-through test keys on the generated placeholder being the only artwork on a 72x72 canvas
    // (every bundled asset is 40x40 or 48x48), which is true under both inlining forms.
    const isGenerated = (uri: string) =>
      decodeDataSvg(uri.startsWith('data:image/svg+xml;base64,')
        ? `,${Buffer.from(uri.slice('data:image/svg+xml;base64,'.length), 'base64').toString('utf8')}`
        : uri).includes('0 0 72 72')

    const missingArtwork: string[] = []
    const wrongArtwork: string[] = []
    const fellThrough: string[] = []
    let checked = 0

    for (const file of templates) {
      const manifest = JSON.parse(readFileSync(join(directory, file), 'utf8')) as {
        WorkingStates?: Array<{ Name?: string }>
      }
      const template = file.replace(/\.json$/, '')
      const folder = normalizeAssetFolder(template)
      const declared = (manifest.WorkingStates ?? [])
        .map(state => state?.Name)
        .filter((name): name is string => typeof name === 'string' && name.trim() !== '')
      // A stateless template renders through the first-in-folder fallback with whatever state the
      // caller passes — the very mechanism this test polices — so it must not be skipped. Its icon
      // is reached under the default `getDeviceIconUrl` state rather than a declared name.
      const states = declared.length > 0 ? declared : ['Working']

      // 1. Every state owns a file. A filesystem check, so the first-in-folder fallback cannot
      //    disguise a missing asset as a working one.
      for (const state of states) {
        checked++
        const owned = [state.trim(), state.trim().replace(/\s+/g, '_')]
          .some(name => existsSync(join(assetsRoot, folder, `${name}.svg`)))
        if (!owned) missingArtwork.push(`${template}: "${state}"`)
        // 1b. Real artwork was reached at all. Without this the two identity checks below are
        //     mutually blind: a dead resolver returns a distinct generated icon per state.
        if (isGenerated(getDeviceIconUrl(template, state))) {
          fellThrough.push(`${template}: "${state}" rendered the generated placeholder`)
        }
      }
      if (declared.length === 0) continue

      // 2. The resolver actually reaches that file. Both ways it can fail land the state on some
      //    other icon in the same folder, so compare renders: against the sibling states, and
      //    against the generic names the variant chain falls back to. Comparing only siblings let a
      //    deleted `sleep;idle.svg` pass, because `Working.svg` is used by no Home Mode state.
      const rendered = new Map<string, string>()
      for (const state of states) rendered.set(state, getDeviceIconUrl(template, state))
      for (const [state, icon] of rendered) {
        for (const [other, otherIcon] of rendered) {
          if (other !== state && otherIcon === icon) {
            wrongArtwork.push(`${template}: "${state}" and "${other}" render the same icon`)
          }
        }
        for (const generic of ['Working', 'working', 'On', 'on', 'Off', 'off']) {
          // A state literally named "off" reaching Off.svg is the case-insensitive probe finding its
          // own artwork, not a fallback. Only a state with a different name landing there is a bug.
          if (state.trim().toLowerCase() === generic.toLowerCase()) continue
          if (states.some(other => other.trim().toLowerCase() === generic.toLowerCase())) continue
          if (!existsSync(join(assetsRoot, folder, `${generic}.svg`))) continue
          if (getDeviceIconUrl(template, generic) === icon) {
            wrongArtwork.push(`${template}: "${state}" fell back to ${generic}.svg`)
          }
        }
      }
    }

    expect(checked, 'no working states were checked').toBeGreaterThan(100)
    expect(fellThrough, `bundled states served by the generated placeholder:\n${fellThrough.join('\n')}`).toEqual([])
    expect(missingArtwork, `bundled states with no icon file of their own:\n${missingArtwork.join('\n')}`).toEqual([])
    expect(wrongArtwork, `bundled states rendering another state's icon:\n${wrongArtwork.join('\n')}`).toEqual([])
  })

  it('generates a stable fallback icon for custom templates without assets', () => {
    const icon = getDeviceIconUrl('Custom Privacy Beacon', 'Working')
    const svg = decodeDataSvg(icon)

    expect(icon).toMatch(/^data:image\/svg\+xml/)
    expect(svg).toContain('viewBox="0 0 72 72"')
    expect(svg).toContain('>CP<')
  })

  it('keeps generated artwork self-contained and neutral across custom template names', () => {
    const paints = ['Custom Privacy Beacon', 'Custom Door Sensor', 'Custom Motion Detector'].map(name => {
      const svg = decodeDataSvg(getDeviceIconUrl(name, 'Working'))
      expect(svg).not.toContain('var(')
      const document = new DOMParser().parseFromString(svg, 'image/svg+xml')
      expect(document.querySelector('parsererror')).toBeNull()
      return Array.from(document.querySelectorAll('[fill], [stroke]')).map(element =>
        [element.getAttribute('fill'), element.getAttribute('stroke')])
    })
    expect(paints[1]).toEqual(paints[0])
    expect(paints[2]).toEqual(paints[0])
  })

  it('prefers safe manifest icons and ignores unsafe URLs', () => {
    const safeManifest = {
      Icon: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22/%3E'
    } as DeviceManifest
    const unsafeManifest = {
      Icon: 'javascript:alert(1)'
    } as DeviceManifest
    const remoteManifest = {
      Icon: 'https://tracker.example/device.png'
    } as DeviceManifest

    expect(getDeviceIconUrl('Any Device', 'Working', safeManifest)).toBe(safeManifest.Icon)
    expect(getDeviceIconUrl('Any Device', 'Working', unsafeManifest)).toMatch(/^data:image\/svg\+xml/)
    expect(getDeviceIconUrl('Any Device', 'Working', remoteManifest)).toMatch(/^data:image\/svg\+xml/)
  })
})

describe('device environment-domain semantics', () => {
  it('exposes structured validation reasons that resolve in every supported locale', () => {
    for (const key of Object.values(MANIFEST_VALIDATION_MESSAGE_KEYS)) {
      expect(i18n.global.te(key, 'zh-CN'), `missing zh-CN translation for ${key}`).toBe(true)
      expect(i18n.global.te(key, 'en'), `missing en translation for ${key}`).toBe(true)
    }

    expect(validateManifest({ Name: 'Lamp', Modes: 'Power' })).toMatchObject({
      valid: false,
      code: 'fieldMustBeArray',
      params: { field: 'Modes' },
      msg: expect.stringContaining('must be an array')
    })
  })

  it('resolves an impact-only shared declaration without granting read capability', () => {
    const manifest: DeviceManifest = {
      Name: 'Light',
      InternalVariables: [{
        Name: 'illuminance',
        IsInside: false,
        Reads: false,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: 100,
        NaturalChangeRate: '[-1, 1]',
        Trust: 'untrusted',
        Privacy: 'public'
      }],
      ImpactedVariables: ['illuminance']
    }

    // One array holds every shared declaration; Reads=false is what withholds read capability,
    // rather than the declaration living in a separate array.
    expect(resolveImpactEnvironmentDefinition(manifest, 'illuminance')).toMatchObject({
      Name: 'illuminance',
      LowerBound: 0,
      UpperBound: 100,
      IsInside: false,
      Reads: false,
      FalsifiableWhenCompromised: false
    })
    expect(validateManifest(manifest)).toEqual({ valid: true })
  })

  it('requires an explicit natural rate only for shared numeric variables', () => {
    const shared = {
      Name: 'Temperature Sensor',
      InternalVariables: [{
        Name: 'temperature',
        IsInside: false,
        FalsifiableWhenCompromised: true,
        Trust: 'untrusted',
        Privacy: 'public',
        LowerBound: 0,
        UpperBound: 100
      }]
    }
    expect(validateManifest(shared)).toMatchObject({
      valid: false,
      code: 'sharedNumericNaturalChangeRateRequired'
    })

    expect(validateManifest({
      ...shared,
      InternalVariables: [{ ...shared.InternalVariables[0], IsInside: true }]
    })).toEqual({ valid: true })
  })

  it('matches backend numeric-domain and natural-rate admission before upload', () => {
    const numericVariable = {
      Name: 'temperature',
      IsInside: false,
      FalsifiableWhenCompromised: true,
      Trust: 'untrusted',
      Privacy: 'public',
      LowerBound: 0,
      UpperBound: 100,
      NaturalChangeRate: '[-1, 1]'
    }

    expect(validateManifest({
      Name: 'Descending domain',
      InternalVariables: [{ ...numericVariable, LowerBound: 101 }]
    })).toMatchObject({ valid: false, code: 'numericBoundsOrderInvalid' })

    expect(validateManifest({
      Name: 'Out-of-range integer domain',
      InternalVariables: [{ ...numericVariable, UpperBound: 2147483648 }]
    })).toMatchObject({ valid: false, code: 'numericBoundsInvalid' })

    expect(validateManifest({
      Name: 'Descending rate',
      InternalVariables: [{ ...numericVariable, NaturalChangeRate: '[2, 1]' }]
    })).toMatchObject({ valid: false, code: 'naturalChangeRateInvalid' })

    expect(validateManifest({
      Name: 'Whitespace outside rate',
      InternalVariables: [{ ...numericVariable, NaturalChangeRate: ' [-1, 1]' }]
    })).toMatchObject({ valid: false, code: 'naturalChangeRateInvalid' })

    expect(validateManifest({
      Name: 'Descending impact domain',
      InternalVariables: [{
        Name: 'temperature',
        IsInside: false,
        Reads: false,
        FalsifiableWhenCompromised: false,
        Trust: 'untrusted',
        Privacy: 'public',
        LowerBound: 100,
        UpperBound: 0,
        NaturalChangeRate: '[-1, 1]'
      }],
      ImpactedVariables: ['temperature']
    })).toMatchObject({ valid: false, code: 'numericBoundsOrderInvalid' })

    expect(validateManifest({
      Name: 'Discrete weather',
      InternalVariables: [{
        Name: 'weather',
        IsInside: false,
        FalsifiableWhenCompromised: true,
        Trust: 'untrusted',
        Privacy: 'public',
        Values: ['dry', 'wet'],
        NaturalChangeRate: '1'
      }]
    })).toMatchObject({ valid: false, code: 'naturalChangeRateNumericOnly' })
  })

  it('parses a rate declaration and shows the whole interval it admits', () => {
    expect(parseNaturalChangeRate('1')).toEqual({ lower: 0, upper: 1 })
    expect(parseNaturalChangeRate('[2, 3]')).toEqual({ lower: 2, upper: 3 })
    expect(parseNaturalChangeRate('[3, 2]')).toBeNull()
    expect(parseNaturalChangeRate('2147483648')).toBeNull()
    expect(canonicalNaturalChangeRate(null)).toBe('0..0')
    expect(canonicalNaturalChangeRate('')).toBe('')
  })

  // The interval is the meaning, so the panel shows exactly what the engines explore -- nothing
  // added. An interval excluding 0 says the value always changes; including 0 says it may hold.
  it('lists exactly the per-step changes the declared interval admits', () => {
    expect(naturalChangeDeltas('[-1, 1]')).toEqual([-1, 0, 1])
    expect(naturalChangeDeltas('[-3, 3]')).toEqual([-3, -2, -1, 0, 1, 2, 3])
    expect(naturalChangeDeltas('0')).toEqual([0])
    expect(naturalChangeCandidateValues('[-1, 1]')).toBe('-1, 0, +1')
  })

  it('distinguishes a mandatory change from one that may not happen', () => {
    // "[2, 4]" always rises; "[0, 4]" may hold. The user picks by writing the interval.
    expect(naturalChangeDeltas('[2, 4]')).toEqual([2, 3, 4])
    expect(naturalChangeCandidateValues('[2, 4]')).toBe('+2, +3, +4')
    expect(naturalChangeDeltas('[0, 4]')).toEqual([0, 1, 2, 3, 4])
    expect(naturalChangeDeltas('[-4, -2]')).toEqual([-4, -3, -2])
    expect(naturalChangeDeltas('[-4, 0]')).toEqual([-4, -3, -2, -1, 0])
  })

  it('rejects a rate interval too wide to model exhaustively', () => {
    const manifest = (rate: string) => ({
      Name: 'Wide Drift Sensor',
      InternalVariables: [{
        Name: 'temperature',
        IsInside: false,
        LowerBound: -1000,
        UpperBound: 1000,
        NaturalChangeRate: rate,
        Trust: 'trusted',
        Privacy: 'public',
        FalsifiableWhenCompromised: false
      }]
    })
    expect(validateManifest(manifest('[-500, 500]')).code).toBe('naturalChangeRateSpanTooWide')
    expect(validateManifest(manifest('[-1, 1]')).code).not.toBe('naturalChangeRateSpanTooWide')
  })

  it('rejects an impacted value whose domain exists only outside its own manifest', () => {
    expect(validateManifest({
      Name: 'Incomplete Light',
      InternalVariables: [],
      ImpactedVariables: ['illuminance']
    })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('needs a domain in this manifest')
    })
  })

  it('accepts a shared declaration that is read but not affected', () => {
    // With one array, a shared declaration that is absent from ImpactedVariables is just a sensor
    // reading. The old "unused impact-domain metadata" error existed only because a second array
    // could carry a domain with no purpose; that shape no longer exists.
    expect(validateManifest({
      Name: 'Illuminance Sensor',
      InternalVariables: [{
        Name: 'illuminance',
        IsInside: false,
        Reads: true,
        FalsifiableWhenCompromised: true,
        LowerBound: 0,
        UpperBound: 100,
        NaturalChangeRate: '[-1, 1]',
        Trust: 'untrusted',
        Privacy: 'public'
      }],
      ImpactedVariables: []
    })).toEqual({ valid: true })
  })
  it('rejects incomplete multi-mode working-state tuples', () => {
    expect(validateManifest({
      Name: 'Washer',
      Modes: ['Program', 'MachineState'],
      InitState: 'regular;idle',
      WorkingStates: [
        { Name: 'regular;idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'running', Trust: 'trusted', Privacy: 'public' }
      ]
    })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('one semicolon-separated value for each mode')
    })
  })

  it('rejects a wildcard or undefined initial state', () => {
    const base = {
      Name: 'Dual Mode Device',
      Modes: ['Power', 'Profile'],
      WorkingStates: [
        { Name: 'on;normal', Trust: 'trusted', Privacy: 'public' },
        { Name: 'off;normal', Trust: 'trusted', Privacy: 'public' }
      ]
    }
    expect(validateManifest({ ...base, InitState: 'on;_' })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('is not defined in WorkingStates')
    })
    expect(validateManifest({ ...base, InitState: 'on;eco' })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('is not defined in WorkingStates')
    })
    expect(validateManifest({ ...base, InitState: 'On;normal' })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('is not defined in WorkingStates')
    })
  })

  it('rejects conflicting labels for a reused mode-state component', () => {
    expect(validateManifest({
      Name: 'Home Profile',
      Modes: ['Occupancy', 'MachineState'],
      InitState: 'home;idle',
      WorkingStates: [
        { Name: 'home;idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'away;idle', Trust: 'untrusted', Privacy: 'public' }
      ]
    })).toMatchObject({
      valid: false,
      msg: expect.stringContaining('conflicting Trust/Privacy labels')
    })
  })

  it('rejects missing security labels instead of defaulting to trusted or public', () => {
    expect(validateManifest({
      Name: 'State Sensor',
      Modes: ['Detection'],
      InitState: 'clear',
      WorkingStates: [{ Name: 'clear' }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must define Trust') })

    expect(validateManifest({
      Name: 'Local Sensor',
      InternalVariables: [{
        Name: 'reading',
        IsInside: true,
        FalsifiableWhenCompromised: true,
        LowerBound: 0,
        UpperBound: 100
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must define Trust') })

    expect(validateManifest({
      Name: 'Ambiguous Sensor',
      InternalVariables: [{
        Name: 'reading',
        FalsifiableWhenCompromised: true,
        Trust: 'trusted',
        Privacy: 'public',
        LowerBound: 0,
        UpperBound: 100
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must explicitly define IsInside') })

    expect(validateManifest({
      Name: 'Implicit Boolean Sensor',
      InternalVariables: [{
        Name: 'detected',
        IsInside: true,
        FalsifiableWhenCompromised: true,
        Trust: 'trusted',
        Privacy: 'public'
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must explicitly define Values') })

    expect(validateManifest({
      Name: 'Camera',
      Contents: [{ Name: 'photo' }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must define Privacy') })
  })

  it('rejects a raw WorkingState invariant that the model cannot preserve', () => {
    expect(validateManifest({
      Name: 'Lamp',
      Modes: ['Power'],
      InitState: 'off',
      WorkingStates: [{
        Name: 'off',
        Trust: 'trusted',
        Privacy: 'public',
        Invariant: 'level < 50'
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('unsupported Invariant') })
  })

  it('rejects transition signals that cannot be referenced by rules or specifications', () => {
    expect(validateManifest({
      Name: 'Motion Sensor',
      InternalVariables: [{
        Name: 'motion',
        IsInside: true,
        FalsifiableWhenCompromised: true,
        Trust: 'untrusted',
        Privacy: 'public',
        Values: ['clear', 'detected']
      }],
      Transitions: [{
        Name: 'motion detected',
        Signal: true,
        Trigger: { Attribute: 'motion', Relation: '=', Value: 'detected' },
        Assignments: [{ Attribute: 'motion', Value: 'clear' }]
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('unsupported Signal') })
  })

  it('requires API trigger observability to be an explicit template choice', () => {
    expect(validateManifest({
      Name: 'Lamp',
      Modes: ['Power'],
      InitState: 'off',
      WorkingStates: [
        { Name: 'off', Trust: 'trusted', Privacy: 'public' },
        { Name: 'on', Trust: 'trusted', Privacy: 'public' }
      ],
      APIs: [{ Name: 'turn_on', StartState: 'off', EndState: 'on' }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must explicitly define boolean Signal') })
  })

  it('requires an explicit API state precondition instead of assuming any state', () => {
    expect(validateManifest({
      Name: 'Lamp',
      Modes: ['Power'],
      InitState: 'off',
      WorkingStates: [
        { Name: 'off', Trust: 'trusted', Privacy: 'public' },
        { Name: 'on', Trust: 'trusted', Privacy: 'public' }
      ],
      APIs: [{ Name: 'turn_on', EndState: 'on', Signal: true }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('must explicitly define StartState') })
  })

  it('rejects an ambiguous content-input capability instead of treating it as enabled', () => {
    expect(validateManifest({
      Name: 'Messenger',
      Modes: ['Status'],
      InitState: 'idle',
      WorkingStates: [
        { Name: 'idle', Trust: 'trusted', Privacy: 'private' },
        { Name: 'sending', Trust: 'trusted', Privacy: 'private' }
      ],
      APIs: [{
        Name: 'send',
        StartState: 'idle',
        EndState: 'sending',
        Signal: true,
        AcceptsContent: 'yes'
      }]
    })).toMatchObject({ valid: false, msg: expect.stringContaining('AcceptsContent must be boolean') })
  })
})
