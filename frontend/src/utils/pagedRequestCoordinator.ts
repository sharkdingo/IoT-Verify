export type PagedRequestKind = 'replace' | 'append'

export type PagedRequestToken = Readonly<{
  generation: number
  requestId: number
  kind: PagedRequestKind
}>

export const createPagedRequestCoordinator = () => {
  let generation = 0
  let nextRequestId = 0
  let latestReplaceRequestId: number | null = null
  let appendRequestId: number | null = null

  const beginReplace = (): PagedRequestToken => {
    generation += 1
    const token: PagedRequestToken = {
      generation,
      requestId: ++nextRequestId,
      kind: 'replace'
    }
    latestReplaceRequestId = token.requestId
    return token
  }

  const beginAppend = (): PagedRequestToken | null => {
    if (appendRequestId !== null || latestReplaceRequestId !== null) return null
    const token: PagedRequestToken = {
      generation,
      requestId: ++nextRequestId,
      kind: 'append'
    }
    appendRequestId = token.requestId
    return token
  }

  const invalidate = () => {
    generation += 1
    latestReplaceRequestId = null
  }

  const isCurrent = (token: PagedRequestToken): boolean => {
    if (token.generation !== generation) return false
    return token.kind === 'replace'
      ? latestReplaceRequestId === token.requestId
      : appendRequestId === token.requestId
  }

  const finish = (token: PagedRequestToken) => {
    if (token.kind === 'replace' && latestReplaceRequestId === token.requestId) {
      latestReplaceRequestId = null
    }
    if (token.kind === 'append' && appendRequestId === token.requestId) {
      appendRequestId = null
    }
  }

  return {
    beginReplace,
    beginAppend,
    hasPendingReplace: () => latestReplaceRequestId !== null,
    invalidate,
    isCurrent,
    finish
  }
}
