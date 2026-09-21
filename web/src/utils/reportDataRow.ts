export function extractDataRowId(resultKey: string): string | null {
  const parts = resultKey.split('#')
  return parts.length >= 3 ? parts[parts.length - 2] || null : null
}

export function formatDataRowLabel(resultKey: string): string | null {
  const id = extractDataRowId(resultKey)
  return id ? `数据行 ${id.length > 12 ? `${id.slice(0, 8)}…` : id}` : null
}
