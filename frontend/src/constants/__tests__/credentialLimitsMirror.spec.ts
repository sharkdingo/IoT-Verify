// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { CREDENTIAL_LIMITS, REQUEST_LIMITS } from '../requestLimits'

/**
 * The two layers must reject the same credentials.
 *
 * `REQUEST_LIMITS` has always been described as mirroring the backend's `RequestLimits`, with a comment saying
 * both sides "must reject identically" — and nothing checked it. The credential rules were not even in the
 * mirror: `Landing.vue` carried its own `10`, `64`, `72` and a literal `^1[3-9]\d{9}$`, duplicating what
 * `RegisterRequestDto` declares, under a comment asking the reader to "keep the two in step".
 *
 * They agreed. The failure mode is asymmetric drift, and only one direction is visible in testing: a client that
 * is *stricter* than the server merely refuses something acceptable, while a client that is *looser* shows the
 * user a server rejection on a field whose own hint told them the value was fine. Verified against the live API
 * before centralising — all ten cases behaved correctly, including a 30-character/90-byte password rejected on
 * BCrypt's 72-byte boundary while passing the 10-64 character rule.
 *
 * This reads the Java source rather than importing it, which is the only option across the language boundary, and
 * is why it asserts on the declared constants rather than on behaviour.
 */

const javaLimits = () => readFileSync(
  join(__dirname, '../../../../backend/src/main/java/cn/edu/nju/Iot_Verify/dto/RequestLimits.java'),
  'utf8'
)

const javaInt = (name: string): number => {
  const match = javaLimits().match(new RegExp(`public static final int ${name}\\s*=\\s*(\\d+)\\s*;`))
  expect(match, `RequestLimits.${name} should be declared`).not.toBeNull()
  return Number(match![1])
}

const javaString = (name: string): string => {
  const match = javaLimits().match(new RegExp(`public static final String ${name}\\s*=\\s*"([^"]+)"\\s*;`))
  expect(match, `RequestLimits.${name} should be declared`).not.toBeNull()
  // Java source escapes the backslash; the runtime value has one.
  return match![1].replace(/\\\\/g, '\\')
}

describe('credential limits mirror the backend', () => {
  it('agrees on the password bounds', () => {
    expect(CREDENTIAL_LIMITS.minPasswordLength).toBe(javaInt('MIN_PASSWORD_LENGTH'))
    expect(CREDENTIAL_LIMITS.maxPasswordLength).toBe(javaInt('MAX_PASSWORD_LENGTH'))
  })

  it('agrees on BCrypt\'s byte ceiling', () => {
    // Not a style rule: BCrypt hashes at most 72 UTF-8 bytes, so a longer password has its tail silently
    // ignored — two passwords differing only past byte 72 would authenticate each other.
    expect(CREDENTIAL_LIMITS.maxPasswordBcryptBytes).toBe(javaInt('MAX_PASSWORD_BCRYPT_BYTES'))
    expect(CREDENTIAL_LIMITS.maxPasswordBcryptBytes).toBe(72)
  })

  it('agrees on the phone pattern', () => {
    expect(CREDENTIAL_LIMITS.phonePattern.source).toBe(javaString('PHONE_PATTERN'))
  })

  it('agrees on the username bounds', () => {
    expect(CREDENTIAL_LIMITS.maxUsernameLength).toBe(javaInt('MAX_USERNAME_LENGTH'))
    expect(CREDENTIAL_LIMITS.minUsernameDisplayLength).toBe(javaInt('MIN_USERNAME_DISPLAY_LENGTH'))
    expect(CREDENTIAL_LIMITS.maxUsernameDisplayLength).toBe(javaInt('MAX_USERNAME_DISPLAY_LENGTH'))
  })

  it('keeps the pre-existing request limits mirrored too', () => {
    // The convention this file has always claimed, now actually asserted.
    const pairs: Array<[keyof typeof REQUEST_LIMITS, string]> = [
      ['devices', 'MAX_DEVICES'],
      ['environmentVariables', 'MAX_ENVIRONMENT_VARIABLES'],
      ['rules', 'MAX_RULES'],
      ['specifications', 'MAX_SPECS'],
      ['ruleConditions', 'MAX_RULE_CONDITIONS'],
      ['specificationConditions', 'MAX_SPEC_CONDITIONS'],
      ['deviceVariables', 'MAX_DEVICE_VARIABLES'],
      ['devicePrivacies', 'MAX_DEVICE_PRIVACIES'],
      ['templates', 'MAX_TEMPLATES'],
      ['modelIdentifierCharacters', 'MAX_IDENTIFIER_LENGTH'],
      ['chatSessions', 'MAX_CHAT_SESSIONS'],
      ['chatContentCharacters', 'MAX_CHAT_CONTENT_LENGTH']
    ]
    const diverged = pairs
      .filter(([ts, java]) => REQUEST_LIMITS[ts] !== javaInt(java))
      .map(([ts, java]) => `${String(ts)}=${REQUEST_LIMITS[ts]} vs ${java}=${javaInt(java)}`)

    expect(diverged).toEqual([])
  })

  it('bounds every declared per-template collection in the schema that enforces it', () => {
    /*
     * These constants look unreferenced in Java and are not dead.
     *
     * A dead-code audit reported all ten `MAX_TEMPLATE_*` as declared-and-never-used and concluded the manifest
     * collections were unbounded on an authenticated write path. I acted on it and added `@Size` to
     * `DeviceTemplateDto` before checking the mechanism. Both premises were wrong: the template endpoint takes a
     * raw `JsonNode` and runs `validateRawManifest` *before* DTO conversion, so Bean Validation never sees a
     * manifest, and `backend/device-template-schema.json` already carried every bound as `maxItems`. Verified
     * live against the running API: a 21-mode template is rejected 400 with "$.Modes: at most 20 items, found
     * 21". The `@Size` work was reverted.
     *
     * So the check belongs on the schema, not on the DTO — and it belongs here because this file already owns
     * "the layers agree about limits", and the next person to grep those constants will reach the same wrong
     * conclusion without it.
     */
    const source = javaLimits()
    const declared = [...source.matchAll(/public static final int (MAX_TEMPLATE_\w+)\s*=/g)].map(m => m[1])
    expect(declared.length, 'the template caps should still be declared').toBeGreaterThan(0)

    const schema = JSON.parse(readFileSync(
      join(__dirname, '../../../../backend/device-template-schema.json'), 'utf8'))
    const bounded = (field: string) => schema.properties?.[field]?.maxItems

    /*
     * Every field compares its VALUE, not merely that a bound exists.
     *
     * The first version value-checked only `Modes` and asserted `maxItems > 0` for the other six, so
     * `MAX_TEMPLATE_TRANSITIONS` could move to 500 against a schema still at 100 and this stayed green — it
     * coupled one constant and merely acknowledged the rest. They all agree today (Modes 20, the rest 100).
     */
    const pairs: Array<[string, string]> = [
      ['Modes', 'MAX_TEMPLATE_MODES'],
      ['WorkingStates', 'MAX_TEMPLATE_WORKING_STATES'],
      ['InternalVariables', 'MAX_TEMPLATE_INTERNAL_VARIABLES'],
      ['ImpactedVariables', 'MAX_TEMPLATE_IMPACTED_VARIABLES'],
      ['Transitions', 'MAX_TEMPLATE_TRANSITIONS'],
      ['APIs', 'MAX_TEMPLATE_APIS'],
      ['Contents', 'MAX_TEMPLATE_CONTENTS']
    ]
    const diverged = pairs
      .filter(([field, constant]) => bounded(field) !== javaInt(constant))
      .map(([field, constant]) => `${field}=${bounded(field)} vs ${constant}=${javaInt(constant)}`)
    expect(diverged).toEqual([])

    for (const [field] of pairs) {
      expect(bounded(field), `${field} should carry a maxItems bound`).toBeGreaterThan(0)
    }
  })

  it('leaves no credential rule written out at a call site', () => {
    /*
     * The point of the mirror is that there is one place to change. A literal at a call site re-creates the drift
     * this file exists to prevent.
     *
     * This used to scan `Landing.vue` only, and keyed the username check on the identifier `usernameLength` — so
     * `utils/accountIdentifier.ts` hardcoding `length >= 3 && length <= 20` was structurally invisible to it.
     * That mattered more than an ordinary duplicate: `Landing.vue` checks length against `CREDENTIAL_LIMITS` and
     * reports `auth.usernameLength`, *then* calls `isValidNormalizedUsername` and reports
     * `auth.usernameInvalidCharacters`. Disagreement means a length problem is shown as a character-set problem.
     * Every module that participates in credential validation is scanned now, not just the form.
     */
    const files = ['../../views/Landing.vue', '../../utils/accountIdentifier.ts']
      .map(path => ({
        path,
        text: readFileSync(join(__dirname, path), 'utf8')
          .replace(/<!--[\s\S]*?-->/g, '')
          .replace(/\/\/[^\n]*/g, '')
          .replace(/\/\*[\s\S]*?\*\//g, '')
      }))

    for (const { path, text } of files) {
      expect(text, `${path}: the phone pattern should come from CREDENTIAL_LIMITS`).not.toMatch(/\^1\[3-9\]/)
      expect(text, `${path}: password bounds should come from CREDENTIAL_LIMITS`)
        .not.toMatch(/password\.length\s*[<>]\s*(?:10|64)\b/)
      expect(text, `${path}: the BCrypt ceiling should come from CREDENTIAL_LIMITS`)
        .not.toMatch(/\.length\s*>\s*72\b/)
      // Any length comparison against the display bounds, however the variable is named — the previous
      // `usernameLength`-keyed pattern is exactly what let the second copy through.
      expect(text, `${path}: username bounds should come from CREDENTIAL_LIMITS`)
        .not.toMatch(/length\s*[<>]=?\s*(?:3|20)\b/)
    }
  })
})
