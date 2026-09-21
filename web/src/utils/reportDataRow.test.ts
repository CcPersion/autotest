import { describe, expect, it } from 'vitest'

import { extractDataRowId, formatDataRowLabel } from './reportDataRow'

describe('报告数据行标识', () => {
  it('从逐行结果键提取行 id 并生成短标签', () => {
    expect(extractDataRowId('step-1#row-123456789#0')).toBe('row-123456789')
    expect(formatDataRowLabel('step-1#row-123456789#0')).toBe('数据行 row-1234…')
  })

  it('普通单次结果不显示数据行标签', () => {
    expect(extractDataRowId('step-1#0')).toBeNull()
    expect(formatDataRowLabel('step-1#0')).toBeNull()
  })
})
