import { apiRequest } from './http'

export interface ModuleNode {
  id: string
  projectId: string
  parentId: string | null
  name: string
  sortOrder: number
  revision: number
  children: ModuleNode[]
}

export interface ModuleCreateInput {
  name: string
  parentId?: string | null
  position?: number
}

export interface ModuleRenameInput {
  name: string
  revision: number
}

export interface ModuleMoveInput {
  parentId?: string | null
  position: number
  revision: number
}

export interface ModuleApi {
  tree(projectId: string): Promise<ModuleNode[]>
  create(projectId: string, input: ModuleCreateInput): Promise<ModuleNode>
  rename(projectId: string, moduleId: string, input: ModuleRenameInput): Promise<ModuleNode>
  move(projectId: string, moduleId: string, input: ModuleMoveInput): Promise<ModuleNode>
  remove(projectId: string, moduleId: string, revision: number): Promise<void>
}

export const moduleApi: ModuleApi = {
  tree(projectId) {
    return apiRequest<ModuleNode[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/modules/tree`)
  },
  create(projectId, input) {
    return apiRequest<ModuleNode>(`/api/v1/projects/${encodeURIComponent(projectId)}/modules`, {
      method: 'POST',
      body: input,
    })
  },
  rename(projectId, moduleId, input) {
    return apiRequest<ModuleNode>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/modules/${encodeURIComponent(moduleId)}`,
      { method: 'PUT', body: input },
    )
  },
  move(projectId, moduleId, input) {
    return apiRequest<ModuleNode>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/modules/${encodeURIComponent(moduleId)}/move`,
      { method: 'POST', body: input },
    )
  },
  remove(projectId, moduleId, revision) {
    return apiRequest<void>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/modules/${encodeURIComponent(moduleId)}?revision=${revision}`,
      { method: 'DELETE' },
    )
  },
}
