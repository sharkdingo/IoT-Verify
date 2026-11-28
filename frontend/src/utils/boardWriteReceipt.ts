export const isDefinitiveMutationRejection = (error: unknown): boolean => {
  const status = Number((error as { response?: { status?: number } } | null)?.response?.status || 0)
  return status >= 400 && status < 500
}

export type BoardWriteReceipt<T> =
  | { status: 'confirmed'; mutation: T }
  | { status: 'rejected' | 'unconfirmed'; error: unknown; snapshotRefreshed: boolean }

// Call within the mutation queue so the recovered scene and undo revision are installed together.
export const readBoardWriteReceipt = async <T,>(
  write: () => Promise<T>,
  isCurrent: () => boolean,
  { refreshOnRejection, reconcile }: {
    refreshOnRejection?: (error: unknown) => boolean
    reconcile: () => Promise<boolean>
  }
): Promise<BoardWriteReceipt<T> | null> => {
  if (!isCurrent()) return null
  try {
    const mutation = await write()
    return isCurrent() ? { status: 'confirmed', mutation } : null
  } catch (error: unknown) {
    if (!isCurrent()) return null
    const rejected = isDefinitiveMutationRejection(error)
    const snapshotRefreshed = !rejected || refreshOnRejection?.(error)
      ? await reconcile()
      : false
    return isCurrent() ? { status: rejected ? 'rejected' : 'unconfirmed', error, snapshotRefreshed } : null
  }
}

export const isRecordMissing = (error: unknown): boolean =>
  (error as { response?: { status?: number } } | null)?.response?.status === 404

export const readDeletionReceipt = async (
  remove: () => Promise<void>, read: () => Promise<unknown>, isCurrent: () => boolean
) => {
  let absent = false
  const receipt = await readBoardWriteReceipt(remove, isCurrent, {
    refreshOnRejection: isRecordMissing,
    reconcile: async () => {
      try {
        await read()
        return true
      } catch (error) {
        absent = isRecordMissing(error)
        return absent
      }
    }
  })
  // A read can prove absence, but cannot attribute it to the original DELETE.
  return receipt && absent ? { status: 'absent' as const } : receipt
}
