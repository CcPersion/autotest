import { describe, expect, it, vi } from 'vitest'

import { aiPatchApi } from './aiPatch'
import { apiRequest } from './http'

vi.mock('./http', () => ({ apiRequest: vi.fn() }))

describe('AI Patch API', () => {
  it('预览请求保留目标、基线和操作', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ previewId: 'p-1' })
    await aiPatchApi.preview('project/1', {
      title: '调整登录接口', targetType: 'API_DEFINITION', targetId: 'def-1', baseRevision: 2,
      operations: [{ op: 'replace', path: '/name', value: '登录接口 v2' }],
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/project%2F1/ai/patches/preview', expect.objectContaining({
      method: 'POST', body: expect.objectContaining({ targetId: 'def-1', baseRevision: 2 }),
    }))
  })

  it('确认只发送预览令牌', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ revision: 3 })
    await aiPatchApi.confirm('p1', 'preview-1')
    expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/p1/ai/patches/confirm', {
      method: 'POST', body: { previewId: 'preview-1' },
    })
  })
})
