import { apiRequest } from './http'

export interface RedisDataSource {
  id: string
  projectId: string
  environmentId: string
  name: string
  host: string
  port: number
  databaseNumber: number
  username?: string | null
  secretRef?: string | null
  options: Record<string, unknown>
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface RedisDataSourceWrite {
  name: string
  host: string
  port: number
  databaseNumber: number
  username?: string
  secretRef?: string
  options?: Record<string, unknown>
  revision?: number
}

export interface RedisDataSourceApi {
  list(projectId: string, environmentId: string, includeArchived?: boolean): Promise<RedisDataSource[]>
  create(projectId: string, environmentId: string, input: RedisDataSourceWrite): Promise<RedisDataSource>
  update(projectId: string, environmentId: string, id: string, input: RedisDataSourceWrite): Promise<RedisDataSource>
  archive(projectId: string, environmentId: string, id: string, revision: number): Promise<RedisDataSource>
  restore(projectId: string, environmentId: string, id: string, revision: number): Promise<RedisDataSource>
  testConnection(projectId: string, environmentId: string, id: string): Promise<{ success: boolean; message: string }>
}

function path(projectId: string, environmentId: string, id?: string) {
  const base = `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environmentId)}/redis-data-sources`
  return id ? `${base}/${encodeURIComponent(id)}` : base
}

export const redisDataSourceApi: RedisDataSourceApi = {
  list: (projectId, environmentId, includeArchived = false) => apiRequest<RedisDataSource[]>(`${path(projectId, environmentId)}?includeArchived=${includeArchived}`),
  create: (projectId, environmentId, input) => apiRequest<RedisDataSource>(path(projectId, environmentId), { method: 'POST', body: input }),
  update: (projectId, environmentId, id, input) => apiRequest<RedisDataSource>(path(projectId, environmentId, id), { method: 'PUT', body: input }),
  archive: (projectId, environmentId, id, revision) => apiRequest<RedisDataSource>(`${path(projectId, environmentId, id)}/archive`, { method: 'POST', body: { revision } }),
  restore: (projectId, environmentId, id, revision) => apiRequest<RedisDataSource>(`${path(projectId, environmentId, id)}/restore`, { method: 'POST', body: { revision } }),
  testConnection: (projectId, environmentId, id) => apiRequest<{ success: boolean; message: string }>(`${path(projectId, environmentId, id)}/test-connection`, { method: 'POST', body: {} }),
}
