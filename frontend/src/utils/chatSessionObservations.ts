import type { ChatSessionObservationBaseline } from '@/types/chat'

type SessionObservation = ChatSessionObservationBaseline['sessions'][number]

export const captureSessionObservations = (sessions: readonly SessionObservation[]) =>
  new Map(sessions.map(({ id, active, latestTerminalMessageId }) => [id, { id, active, latestTerminalMessageId }]))

export const observeSessionChanges = <T extends SessionObservation>(
  previous: ReadonlyMap<string, SessionObservation>,
  next: Iterable<T>,
  compareTerminalMessages: boolean,
  excludedSessionId?: string | null
) => {
  const nextIds = new Set<string>()
  const completed: T[] = []
  for (const session of next) {
    nextIds.add(session.id)
    if (session.id === excludedSessionId) continue
    const prior = previous.get(session.id)
    if ((prior?.active && !session.active)
      || (compareTerminalMessages && session.latestTerminalMessageId !== null
        && session.latestTerminalMessageId !== prior?.latestTerminalMessageId)) {
      completed.push(session)
    }
  }
  const removed = Array.from(previous.values()).filter(session =>
    session.active && session.id !== excludedSessionId && !nextIds.has(session.id))
  return { completed, removed }
}

export const waitForChatSessionIdle = async (
  readActivity: () => Promise<{ active: boolean }>,
  ownsRequest: () => boolean,
  deadline: number,
  failureLimit: number
): Promise<boolean> => {
  let consecutiveFailures = 0
  while (Date.now() < deadline && ownsRequest()) {
    try {
      const { active } = await readActivity()
      if (!ownsRequest()) return false
      if (!active) return true
      consecutiveFailures = 0
    } catch (error: any) {
      if (!ownsRequest()) return false
      if (error?.response?.status === 404) return true
      consecutiveFailures += 1
      if (consecutiveFailures >= failureLimit) throw error
    }
    await new Promise<void>(resolve => window.setTimeout(resolve, 500))
  }
  return false
}
