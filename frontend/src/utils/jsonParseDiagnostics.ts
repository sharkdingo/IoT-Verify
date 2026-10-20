export interface JsonParseDiagnostic {
  reason: string
  position?: number
  line?: number
  column?: number
}

/** Coordinates describe only a location explicitly reported by the JSON parser. */
export const diagnoseJsonParseError = (source: string, cause: unknown): JsonParseDiagnostic => {
  const reason = cause instanceof Error ? cause.message : String(cause ?? '')
  const diagnostic: JsonParseDiagnostic = { reason }
  // V8 can quote arbitrary input in an error; only its diagnostic suffix owns the coordinate.
  const offset = reason.match(/\bat position\s+(\d+)(?:\s+\(line\s+\d+\s+column\s+\d+\))?\s*$/i)
  if (offset) {
    const position = Number(offset[1])
    if (!Number.isSafeInteger(position) || position > source.length) return diagnostic
    const lines = source.slice(0, position).split(/\r\n|\r|\n/)
    return { reason, position, line: lines.length, column: lines[lines.length - 1]!.length + 1 }
  }

  const coordinate = reason.match(/\bat line\s+(\d+)\s+column\s+(\d+)(?:\s+of the JSON data)?\s*$/i)
  if (!coordinate) return diagnostic
  const line = Number(coordinate[1])
  const column = Number(coordinate[2])
  const lines = source.split(/\r\n|\r|\n/)
  if (!Number.isSafeInteger(line) || !Number.isSafeInteger(column)
    || line < 1 || line > lines.length || column < 1 || column > lines[line - 1]!.length + 1) {
    return diagnostic
  }
  return { reason, line, column }
}
