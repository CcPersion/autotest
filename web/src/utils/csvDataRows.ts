export type CsvDataRow = Record<string, string>

export function parseCsvDataRows(input: string): CsvDataRow[] {
  const records = readRecords(input.replace(/^\uFEFF/, ''))
  if (!records.length || records[0].every((cell) => cell === '')) return []
  const headers = records[0].map((header) => header.trim())
  if (headers.some((header) => !header)) throw new Error('CSV 列名不能为空')
  if (new Set(headers).size !== headers.length) throw new Error('CSV 列名不能重复')
  return records.slice(1)
    .filter((record) => !(record.length === 1 && record[0] === ''))
    .map((record) => Object.fromEntries(headers.map((header, index) => [header, record[index] ?? ''])))
}

export function serializeCsvDataRows(rows: Array<Record<string, unknown>>): string {
  const columns: string[] = []
  for (const row of rows) {
    for (const key of Object.keys(row)) if (!columns.includes(key)) columns.push(key)
  }
  if (!columns.length) return ''
  return [columns, ...rows.map((row) => columns.map((column) => cellText(row[column])))]
    .map((record) => record.map(escapeCell).join(','))
    .join('\r\n') + '\r\n'
}

function escapeCell(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

function readRecords(input: string): string[][] {
  const records: string[][] = []
  let record: string[] = []
  let cell = ''
  let quoted = false
  for (let index = 0; index < input.length; index += 1) {
    const character = input[index]
    if (character === '"') {
      if (quoted && input[index + 1] === '"') {
        cell += '"'
        index += 1
      } else {
        quoted = !quoted
      }
    } else if (character === ',' && !quoted) {
      record.push(cell)
      cell = ''
    } else if ((character === '\n' || character === '\r') && !quoted) {
      if (character === '\r' && input[index + 1] === '\n') index += 1
      record.push(cell)
      records.push(record)
      record = []
      cell = ''
    } else {
      cell += character
    }
  }
  if (cell || record.length) {
    record.push(cell)
    records.push(record)
  }
  return records
}
