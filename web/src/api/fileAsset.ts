import { apiRequest } from './http'

export type FileAssetKind = 'REQUEST_FILE' | 'PKCS12'

export interface FileAsset {
  fileId: string
  projectId: string
  kind: FileAssetKind
  originalName: string
  mimeType: string
  size: number
  sha256: string
  status: 'ACTIVE' | 'ARCHIVED'
  revision: number
}

function path(projectId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/files`
}

export interface FileAssetApi {
  list(projectId: string): Promise<FileAsset[]>
  upload(projectId: string, file: File, kind: FileAssetKind): Promise<FileAsset>
}

export const fileAssetApi: FileAssetApi = {
  list(projectId) {
    return apiRequest<FileAsset[]>(`${path(projectId)}?status=ACTIVE`)
  },
  upload(projectId, file, kind) {
    const body = new FormData()
    body.append('file', file, file.name)
    body.append('kind', kind)
    return apiRequest<FileAsset>(path(projectId), { method: 'POST', body })
  },
}
