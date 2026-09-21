import { apiRequest } from './http'

export interface JdbcDataSource {
  id: string
  projectId: string
  environmentId: string
  name: string
  databaseType: 'POSTGRESQL' | 'MYSQL'
  host: string
  port: number
  databaseName: string
  username: string
  secretRef: string
  options: Record<string, unknown>
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface JdbcDataSourceWrite {
  name: string
  databaseType: JdbcDataSource['databaseType']
  host: string
  port: number
  databaseName: string
  username: string
  secretRef: string
  options?: Record<string, unknown>
  revision?: number
}

export interface JdbcDataSourceApi {
  list(projectId: string, environmentId: string, includeArchived?: boolean): Promise<JdbcDataSource[]>
  create(projectId: string, environmentId: string, input: JdbcDataSourceWrite): Promise<JdbcDataSource>
  update(projectId: string, environmentId: string, id: string, input: JdbcDataSourceWrite): Promise<JdbcDataSource>
  archive(projectId: string, environmentId: string, id: string, revision: number): Promise<JdbcDataSource>
  restore(projectId: string, environmentId: string, id: string, revision: number): Promise<JdbcDataSource>
  testConnection(projectId: string, environmentId: string, id: string): Promise<{ success: boolean; message: string }>
}

function path(projectId: string, environmentId: string, id?: string) {
  const base = `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environmentId)}/jdbc-data-sources`
  return id ? `${base}/${encodeURIComponent(id)}` : base
}

export const jdbcDataSourceApi: JdbcDataSourceApi = {
  list: (projectId, environmentId, includeArchived = false) => apiRequest<JdbcDataSource[]>(`${path(projectId, environmentId)}?includeArchived=${includeArchived}`),
  create: (projectId, environmentId, input) => apiRequest<JdbcDataSource>(path(projectId, environmentId), { method: 'POST', body: input }),
  update: (projectId, environmentId, id, input) => apiRequest<JdbcDataSource>(path(projectId, environmentId, id), { method: 'PUT', body: input }),
  archive: (projectId, environmentId, id, revision) => apiRequest<JdbcDataSource>(`${path(projectId, environmentId, id)}/archive`, { method: 'POST', body: { revision } }),
  restore: (projectId, environmentId, id, revision) => apiRequest<JdbcDataSource>(`${path(projectId, environmentId, id)}/restore`, { method: 'POST', body: { revision } }),
  testConnection: (projectId, environmentId, id) => apiRequest<{ success: boolean; message: string }>(`${path(projectId, environmentId, id)}/test-connection`, { method: 'POST', body: {} }),
}
