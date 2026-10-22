export const REQUEST_LIMITS = Object.freeze({
  devices: 100,
  environmentVariables: 200,
  rules: 100,
  specifications: 100,
  ruleConditions: 50,
  specificationConditions: 50,
  deviceVariables: 100,
  devicePrivacies: 100,
  templates: 100,
  modelIdentifierCharacters: 200,
  chatSessions: 100,
  chatContentCharacters: 10000,
  sceneBytes: 64 * 1024 * 1024,
  // Mirrors RequestLimits.MAX_NATURAL_CHANGE_RATE_SPAN. Every value in a declared
  // NaturalChangeRate interval is modeled as reachable in one step, so its span is a state-space
  // cost that both sides must reject identically.
  naturalChangeRateSpan: 100
})

/**
 * Credential rules, mirroring the `RequestLimits` credential block.
 *
 * These were hardcoded at each site instead — `Landing.vue` carried its own `10`, `64`, `72` and a literal
 * `^1[3-9]\d{9}$`, duplicating what `RegisterRequestDto` declares. Every other cross-layer limit in this product
 * goes through the mirrored-constants pair precisely so both sides reject identically; the credential rules were
 * the exception, and the convention was a comment with nothing checking it.
 *
 * They agree today. The failure mode is asymmetric drift: lowering the minimum on the server while a hardcoded
 * client check keeps the old one gives a form that refuses what the server would accept, and raising it gives a
 * form that accepts what the server refuses — the second showing the user a rejection on a field whose own hint
 * told them it was fine.
 *
 * `credentialLimitsMirror.spec.ts` reads both files and fails if any value diverges.
 */
export const CREDENTIAL_LIMITS = Object.freeze({
  /** BCrypt hashes at most 72 UTF-8 bytes, so a longer password would have its tail silently ignored. */
  maxPasswordBcryptBytes: 72,
  minPasswordLength: 10,
  maxPasswordLength: 64,
  /** Mainland China mobile numbers; the only format accepted as a sign-in identifier. */
  phonePattern: /^1[3-9]\d{9}$/,
  /** Before normalization; the rule shown to users is the narrower 3-20. */
  maxUsernameLength: 100,
  minUsernameDisplayLength: 3,
  maxUsernameDisplayLength: 20
})
