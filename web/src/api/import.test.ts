import { describe, expect, it, vi } from 'vitest'
import { importApi } from './import'

describe('importApi', () => {
  it('sends Curl preview to the project-scoped endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ previewId: 'p1' }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }))

    await importApi.previewCurl('project/1', { source: 'curl https://example.test' })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project%2F1/imports/curl/preview', expect.objectContaining({ method: 'POST' }))
    fetchMock.mockRestore()
  })

  it('confirms explicit choices and supports OpenAPI preview', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({}), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }))

    await importApi.previewOpenApi('p1', { moduleId: null, source: 'openapi: 3.0.3' })
    await importApi.confirm('p1', 'preview-1', [{ index: 0, action: 'CREATE' }])

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/projects/p1/imports/openapi/preview', expect.objectContaining({ method: 'POST' }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/projects/p1/imports/confirm', expect.objectContaining({ method: 'POST' }))
    fetchMock.mockRestore()
  })
})
