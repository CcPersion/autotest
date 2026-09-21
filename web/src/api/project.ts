import { apiRequest } from './http'

export interface ProjectSummary {
  id: string
  name: string
  description: string | null
  revision: number
  archived: boolean
  createdAt: string
  updatedAt: string
  targetAllowlist?: string[]
}

export type ProjectDetail = ProjectSummary

export interface ProjectCreateInput {
  name: string
  description?: string
  targetAllowlist?: string[]
}

export interface ProjectUpdateInput {
  name: string
  description?: string
  targetAllowlist?: string[]
  revision: number
}

export interface ProjectApi {
  list(includeArchived?: boolean): Promise<ProjectSummary[]>
  get(projectId: string): Promise<ProjectDetail>
  create(input: ProjectCreateInput): Promise<ProjectDetail>
  update(projectId: string, input: ProjectUpdateInput): Promise<ProjectDetail>
  archive(projectId: string, revision: number): Promise<ProjectDetail>
  restore(projectId: string, revision: number): Promise<ProjectDetail>
}

export const projectApi: ProjectApi = {
  list(includeArchived = false) {
    return apiRequest<ProjectSummary[]>(`/api/v1/projects?includeArchived=${includeArchived}`)
  },
  get(projectId) {
    return apiRequest<ProjectDetail>(`/api/v1/projects/${encodeURIComponent(projectId)}`)
  },
  create(input) {
    return apiRequest<ProjectDetail>('/api/v1/projects', { method: 'POST', body: input })
  },
  update(projectId, input) {
    return apiRequest<ProjectDetail>(`/api/v1/projects/${encodeURIComponent(projectId)}`, {
      method: 'PUT',
      body: input,
    })
  },
  archive(projectId, revision) {
    return apiRequest<ProjectDetail>(`/api/v1/projects/${encodeURIComponent(projectId)}/archive`, {
      method: 'POST',
      body: { revision },
    })
  },
  restore(projectId, revision) {
    return apiRequest<ProjectDetail>(`/api/v1/projects/${encodeURIComponent(projectId)}/restore`, {
      method: 'POST',
      body: { revision },
    })
  },
}
