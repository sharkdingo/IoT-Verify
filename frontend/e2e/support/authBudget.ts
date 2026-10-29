/** Actionable diagnostics for an actual registration failure; no speculative account probe. */
export const budgetAdviceMessage = (retryAfterSeconds?: number) => {
  const wait = typeof retryAfterSeconds === 'number'
    ? `The current window resets in ~${Math.ceil(retryAfterSeconds / 60)} minutes.`
    : 'The window resets on the hour boundary the limiter opened.'

  return [
    '',
    'The backend has no register budget left; this is an auth fixture failure, not a product assertion.',
    'Account isolation and retries can exceed the default registration limits. Prefer the smoke suite locally.',
    'For a complete run, configure the dedicated test backend and restart it:',
    '',
    '  AUTH_SOURCE_REGISTER_RATE_LIMIT_PER_HOUR=2000',
    '  AUTH_REGISTER_RATE_LIMIT_PER_HOUR=600',
    '  AUTH_SOURCE_LOGIN_RATE_LIMIT_PER_MINUTE=4000',
    '  AUTH_LOGIN_RATE_LIMIT_PER_MINUTE=500',
    '  mvn spring-boot:run',
    '',
    'Exporting these in the Playwright shell does nothing — they are read by the JVM, not by the tests.',
    wait,
    ''
  ].join('\n')
}
