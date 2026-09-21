import { apiRequest } from './http'

export interface User {
  id: string
  username: string
  revision: number
}

export interface SessionApi {
  me(): Promise<User>
  login(username: string, password: string): Promise<User>
  logout(): Promise<void>
  changePassword(currentPassword: string, newPassword: string): Promise<void>
}

export const sessionApi: SessionApi = {
  me: () => apiRequest<User>('/api/v1/auth/me'),
  login: (username, password) => apiRequest<User>('/api/v1/auth/login', {
    method: 'POST',
    body: { username, password },
  }),
  logout: () => apiRequest<void>('/api/v1/auth/logout', { method: 'POST' }),
  changePassword: (currentPassword, newPassword) => apiRequest<void>('/api/v1/auth/password', {
    method: 'PUT',
    body: { currentPassword, newPassword },
  }),
}
