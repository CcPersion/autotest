// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { readSse } from './ai'

describe('AI SSE API', () => {
  it('解析分块 SSE 数据并保留事件顺序', async () => {
    const chunks = [
      new TextEncoder().encode('event: TEXT\ndata: {"type":"TEXT","content":"你好","toolName":null,"arguments":null}\n\n'),
      new TextEncoder().encode('event: DONE\ndata: {"type":"DONE","content":null,"toolName":null,"arguments":null}\n\n'),
    ]
    const response = new Response(new ReadableStream({
      start(controller) { chunks.forEach((chunk) => controller.enqueue(chunk)); controller.close() },
    }), { status: 200 })
    const events: string[] = []
    await readSse(response, (event) => events.push(event.type))
    expect(events).toEqual(['TEXT', 'DONE'])
  })

  it('非 2xx 响应直接失败，不伪造完成事件', async () => {
    const callback = vi.fn()
    await expect(readSse(new Response('', { status: 409 }), callback)).rejects.toThrow('409')
    expect(callback).not.toHaveBeenCalled()
  })
})
