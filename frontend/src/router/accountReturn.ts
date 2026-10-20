/** Account navigation retains only a workspace address, never an external destination. */
export const resolveAccountReturn = (value: unknown): string =>
  typeof value === 'string'
    && /^\/board(?:[?#]|$)/.test(value)
    && !/[\u0000-\u001f\u007f]/.test(value)
    ? value
    : '/board'
