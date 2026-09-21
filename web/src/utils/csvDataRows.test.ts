import { describe, expect, it } from 'vitest'

import { parseCsvDataRows, serializeCsvDataRows } from './csvDataRows'

describe('数据行 CSV', () => {
  it('中文、逗号、换行和空值可以无损往返', () => {
    const rows = [
      { username: '张三', note: '逗号,和换行\n仍在这里', empty: '' },
      { username: '李四', note: '普通文本', empty: '' },
    ]

    expect(parseCsvDataRows(serializeCsvDataRows(rows))).toEqual(rows)
  })

  it('空文本不产生伪造数据行，列名按首行保留顺序', () => {
    expect(parseCsvDataRows('')).toEqual([])
    expect(parseCsvDataRows('b,a\n2,1\n')).toEqual([{ b: '2', a: '1' }])
  })
})
