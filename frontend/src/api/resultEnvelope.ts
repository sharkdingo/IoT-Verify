export const unpack = <T>(response: { data: { data: T } }): T => response.data.data
