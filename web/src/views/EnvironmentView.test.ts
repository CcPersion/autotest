// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import EnvironmentView from './EnvironmentView.vue'

const activeEnvironment = {
  id: 'e1', projectId: 'p1', name: '测试环境', baseUrl: 'https://api.example.com',
  variables: { timeout: 5000, enabled: true, token: '${secret:appSecret}' }, revision: 2,
  requestOptions: { defaultHeaders: [{ name: 'X-Trace', value: 'qa', enabled: true }], followRedirects: false },
  archived: false, createdAt: '', updatedAt: '',
}
const archivedEnvironment = { ...activeEnvironment, id: 'e2', name: '旧环境', archived: true, revision: 4 }
const appSecret = { id: 's1', projectId: 'p1', name: 'appSecret', mask: '••••••••', revision: 1, archived: false, createdAt: '', updatedAt: '' }

function apis() {
  return {
    environmentApi: {
      list: vi.fn().mockResolvedValue([activeEnvironment]),
      create: vi.fn().mockResolvedValue(activeEnvironment),
      update: vi.fn().mockResolvedValue({ ...activeEnvironment, revision: 3 }),
      archive: vi.fn().mockResolvedValue({ ...activeEnvironment, archived: true, revision: 3 }),
      restore: vi.fn().mockResolvedValue({ ...archivedEnvironment, archived: false, revision: 5 }),
    },
    secretApi: {
      list: vi.fn().mockResolvedValue([appSecret]),
      create: vi.fn().mockResolvedValue({ ...appSecret, id: 's2', name: 'clientSecret' }),
      replace: vi.fn().mockResolvedValue({ ...appSecret, revision: 2 }),
      archive: vi.fn().mockResolvedValue({ ...appSecret, archived: true, revision: 2 }),
    },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('环境与密钥配置', () => {
  it('无项目时显示引导且不加载跨项目数据', async () => {
    const { environmentApi, secretApi } = apis()
    const wrapper = mount(EnvironmentView, { props: { projectId: null, environmentApi, secretApi } })

    await flushPromises()

    expect(wrapper.text()).toContain('请先选择或创建项目')
    expect(environmentApi.list).not.toHaveBeenCalled()
    expect(secretApi.list).not.toHaveBeenCalled()
  })

  it('加载活动环境、保持变量 JSON 类型并可选择密钥引用', async () => {
    const { environmentApi, secretApi } = apis()
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()

    expect(environmentApi.list).toHaveBeenCalledWith('p1', false)
    expect(secretApi.list).toHaveBeenCalledWith('p1', false)
    expect(wrapper.text()).toContain('测试环境')

    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('预发布')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://staging.example.com')
    await wrapper.get('textarea[name="environment-variables"]').setValue('{"timeout":10000,"enabled":false}')
    await wrapper.get('select[name="secret-reference"]').setValue('appSecret')
    await wrapper.get('button[data-action="insert-secret-reference"]').trigger('click')

    expect(wrapper.get('textarea[name="environment-variables"]').element.value).toContain('${secret:appSecret}')
    await wrapper.get('form[data-form="environment"]').trigger('submit')

    expect(environmentApi.create).toHaveBeenCalledWith('p1', {
      name: '预发布', baseUrl: 'https://staging.example.com',
      variables: { timeout: 10000, enabled: false, secret: '${secret:appSecret}' },
    })
    expect(wrapper.emitted('changed')).toHaveLength(1)

    await wrapper.get('button[data-action="edit-environment"]').trigger('click')
    await wrapper.get('form[data-form="environment"]').trigger('submit')
    expect(environmentApi.update).toHaveBeenCalledWith('p1', 'e1', expect.objectContaining({ revision: 2 }))
    expect(wrapper.emitted('changed')).toHaveLength(2)

    await wrapper.get('button[data-tab="密钥"]').trigger('click')
    expect(wrapper.text()).toContain('••••••••')
  })

  it('支持归档环境查看与恢复，并提示 revision 冲突', async () => {
    const { environmentApi, secretApi } = apis()
    environmentApi.list
      .mockResolvedValueOnce([activeEnvironment])
      .mockResolvedValueOnce([archivedEnvironment])
    environmentApi.update.mockRejectedValue({ status: 409, code: 'REVISION_CONFLICT', message: 'stale' })
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()

    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await wrapper.get('button[data-action="archive-environment"]').trigger('click')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    await wrapper.get('button[data-action="show-archived-environments"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('旧环境')
    await wrapper.get('button[data-action="restore-environment"]').trigger('click')
    expect(environmentApi.restore).toHaveBeenCalledWith('p1', 'e2', 4)
    expect(wrapper.emitted('changed')).toHaveLength(2)

    await wrapper.get('button[data-action="edit-environment"]').trigger('click')
    await wrapper.get('form[data-form="environment"]').trigger('submit')
    expect(wrapper.text()).toContain('版本已变化，请刷新后重试')
  })

  it('保存环境默认 Header、代理和超时配置', async () => {
    const { environmentApi, secretApi } = apis()
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()
    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('带默认配置')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://defaults.example.com')
    await wrapper.get('textarea[name="environment-request-options"]').setValue(JSON.stringify({
      defaultHeaders: [{ name: 'X-Trace', value: 'qa', enabled: true }],
      followRedirects: false,
      responseTimeoutMillis: 3000,
      proxy: { scheme: 'http', host: 'proxy.example', port: 8080, password: '${secret:proxy-password}' },
    }))
    await wrapper.get('form[data-form="environment"]').trigger('submit')

    expect(environmentApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({
      requestOptions: expect.objectContaining({ followRedirects: false, responseTimeoutMillis: 3000 }),
    }))
  })

  it('通过结构化表单保存 PKCS12 证书和密码密钥引用', async () => {
    const { environmentApi, secretApi } = apis()
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()
    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('双向 TLS')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://mtls.example.com')
    await wrapper.get('input[name="environment-client-cert-enabled"]').setValue(true)
    await wrapper.get('select[name="environment-client-cert-secret"]').setValue('appSecret')
    await wrapper.get('select[name="environment-client-cert-password"]').setValue('appSecret')
    await wrapper.get('form[data-form="environment"]').trigger('submit')

    expect(environmentApi.create).toHaveBeenCalledWith('p1', expect.objectContaining({
      requestOptions: expect.objectContaining({
        clientCertificate: { type: 'PKCS12', secretRef: '${secret:appSecret}', passwordRef: '${secret:appSecret}' },
      }),
    }))
  })

  it('创建和替换密钥只展示固定掩码，替换完成或关闭都清空明文', async () => {
    const { environmentApi, secretApi } = apis()
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()
    await wrapper.get('button[data-tab="密钥"]').trigger('click')

    await wrapper.get('button[data-action="create-secret"]').trigger('click')
    await wrapper.get('input[name="secret-name"]').setValue('clientSecret')
    await wrapper.get('input[name="secret-value"]').setValue('sentinel-value')
    await wrapper.get('form[data-form="secret"]').trigger('submit')
    expect(secretApi.create).toHaveBeenCalledWith('p1', { name: 'clientSecret', value: 'sentinel-value' })
    expect(wrapper.text()).not.toContain('sentinel-value')

    const existingSecretCard = wrapper.findAll('.secret-card').find((card) => card.text().includes('appSecret'))
    await existingSecretCard!.get('button[data-action="replace-secret"]').trigger('click')
    await wrapper.get('input[name="secret-value"]').setValue('replacement-value')
    await wrapper.get('form[data-form="secret"]').trigger('submit')
    expect(secretApi.replace).toHaveBeenCalledWith('p1', 's1', { value: 'replacement-value', revision: 1 })
    expect(wrapper.find('input[name="secret-value"]').exists()).toBe(false)

    await wrapper.get('button[data-action="replace-secret"]').trigger('click')
    await wrapper.get('input[name="secret-value"]').setValue('discard-me')
    await wrapper.get('button[data-action="close-secret-form"]').trigger('click')
    expect(wrapper.find('input[name="secret-value"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('discard-me')
  })

  it('按密钥引用错误显示具体提示且不显示秘密', async () => {
    const { environmentApi, secretApi } = apis()
    environmentApi.create.mockRejectedValue({ status: 400, code: 'SECRET_REFERENCE_NOT_FOUND', message: 'token 引用不存在' })
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()
    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('坏环境')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://bad.example.com')
    await wrapper.get('textarea[name="environment-variables"]').setValue('{"token":"${secret:notFound}"}')
    await wrapper.get('form[data-form="environment"]').trigger('submit')

    expect(wrapper.text()).toContain('密钥引用不存在')
    expect(wrapper.text()).not.toContain('sentinel-value')
  })

  it('按错误 code 优先提示环境名称冲突，而不是笼统版本冲突', async () => {
    const { environmentApi, secretApi } = apis()
    environmentApi.create.mockRejectedValue({ status: 409, code: 'NAME_CONFLICT', message: 'conflict' })
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()
    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('重复环境')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://duplicate.example.com')
    await wrapper.get('form[data-form="environment"]').trigger('submit')

    expect(wrapper.text()).toContain('名称已存在')
    expect(wrapper.text()).not.toContain('版本已变化，请刷新后重试')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('projectId 切换立即清空旧数据、表单明文和错误状态', async () => {
    const { environmentApi, secretApi } = apis()
    const p2Environment = { ...activeEnvironment, projectId: 'p2', name: '支付环境' }
    const p2Secret = { ...appSecret, projectId: 'p2', name: 'paymentSecret' }
    environmentApi.list.mockImplementation((projectId: string) => Promise.resolve(projectId === 'p1' ? [activeEnvironment] : [p2Environment]))
    secretApi.list.mockImplementation((projectId: string) => Promise.resolve(projectId === 'p1' ? [appSecret] : [p2Secret]))
    secretApi.create.mockRejectedValue({ message: '旧项目密钥错误' })
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()

    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('button[data-tab="密钥"]').trigger('click')
    await wrapper.get('button[data-action="create-secret"]').trigger('click')
    await wrapper.get('input[name="secret-name"]').setValue('oldSecret')
    await wrapper.get('input[name="secret-value"]').setValue('old-plaintext')
    await wrapper.get('form[data-form="secret"]').trigger('submit')
    expect(wrapper.text()).toContain('旧项目密钥错误')

    await wrapper.setProps({ projectId: 'p2' })

    expect(wrapper.get('.environment-chip').text()).not.toContain('测试环境')
    expect(wrapper.text()).not.toContain('旧项目密钥错误')
    expect(wrapper.find('form[data-form="environment"]').exists()).toBe(false)
    expect(wrapper.find('form[data-form="secret"]').exists()).toBe(false)
    expect(wrapper.find('input[name="secret-value"]').exists()).toBe(false)
  })

  it('旧项目慢响应不能覆盖切换后的新项目数据', async () => {
    const p1Environment = deferred<typeof activeEnvironment[]>()
    const p1Secret = deferred<typeof appSecret[]>()
    const p2Environment = deferred<typeof activeEnvironment[]>()
    const p2Secret = deferred<typeof appSecret[]>()
    const { environmentApi, secretApi } = apis()
    environmentApi.list.mockImplementation((projectId: string) => projectId === 'p1' ? p1Environment.promise : p2Environment.promise)
    secretApi.list.mockImplementation((projectId: string) => projectId === 'p1' ? p1Secret.promise : p2Secret.promise)
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })

    await wrapper.setProps({ projectId: 'p2' })
    p2Environment.resolve([{ ...activeEnvironment, projectId: 'p2', name: '支付环境' }])
    p2Secret.resolve([{ ...appSecret, projectId: 'p2', name: 'paymentSecret' }])
    await flushPromises()
    expect(wrapper.text()).toContain('支付环境')

    p1Environment.resolve([activeEnvironment])
    p1Secret.resolve([appSecret])
    await flushPromises()

    expect(wrapper.text()).toContain('支付环境')
    expect(wrapper.get('.environment-list').text()).not.toContain('测试环境')
    expect(wrapper.text()).not.toContain('appSecret')
  })

  it('p1 慢环境创建切到 p2 后不污染、不 emit，且 p2 可立即创建', async () => {
    const pending = deferred<typeof activeEnvironment>()
    const { environmentApi, secretApi } = apis()
    const p2Environment = { ...activeEnvironment, projectId: 'p2', id: 'e2', name: '支付环境' }
    environmentApi.list.mockImplementation((projectId: string) => Promise.resolve(projectId === 'p1' ? [activeEnvironment] : [p2Environment]))
    secretApi.list.mockResolvedValue([])
    environmentApi.create.mockReturnValueOnce(pending.promise).mockResolvedValue(p2Environment)
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()

    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('旧项目环境')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://old.example.com')
    void wrapper.get('form[data-form="environment"]').trigger('submit')
    await flushPromises()

    await wrapper.setProps({ projectId: 'p2' })
    await flushPromises()
    expect(wrapper.find('form[data-form="environment"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('支付环境')
    pending.resolve({ ...activeEnvironment, id: 'e-old', projectId: 'p1', name: '旧项目环境' })
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧项目环境')
    expect(wrapper.emitted('changed')).toBeUndefined()

    await wrapper.get('button[data-action="create-environment"]').trigger('click')
    await wrapper.get('input[name="environment-name"]').setValue('新项目环境')
    await wrapper.get('input[name="environment-base-url"]').setValue('https://new.example.com')
    await wrapper.get('form[data-form="environment"]').trigger('submit')
    expect(environmentApi.create).toHaveBeenLastCalledWith('p2', expect.objectContaining({ name: '新项目环境' }))
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('p1 慢密钥创建切到 p2 后不污染、不 emit，且 p2 可立即创建', async () => {
    const pending = deferred<typeof appSecret>()
    const { environmentApi, secretApi } = apis()
    const p2Environment = { ...activeEnvironment, projectId: 'p2', id: 'e2', name: '支付环境' }
    const p2Secret = { ...appSecret, projectId: 'p2', id: 's2', name: 'paymentSecret' }
    environmentApi.list.mockImplementation((projectId: string) => Promise.resolve(projectId === 'p1' ? [activeEnvironment] : [p2Environment]))
    secretApi.list.mockResolvedValue([])
    secretApi.create.mockReturnValueOnce(pending.promise).mockResolvedValue(p2Secret)
    const wrapper = mount(EnvironmentView, { props: { projectId: 'p1', environmentApi, secretApi } })
    await flushPromises()

    await wrapper.get('button[data-tab="密钥"]').trigger('click')
    await wrapper.get('button[data-action="create-secret"]').trigger('click')
    await wrapper.get('input[name="secret-name"]').setValue('oldSecret')
    await wrapper.get('input[name="secret-value"]').setValue('old-plaintext')
    void wrapper.get('form[data-form="secret"]').trigger('submit')
    await flushPromises()

    await wrapper.setProps({ projectId: 'p2' })
    await flushPromises()
    expect(wrapper.find('form[data-form="secret"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('支付环境')
    pending.resolve({ ...appSecret, id: 's-old', projectId: 'p1', name: 'oldSecret' })
    await flushPromises()

    expect(wrapper.text()).not.toContain('oldSecret')
    expect(wrapper.emitted('changed')).toBeUndefined()

    await wrapper.get('button[data-action="create-secret"]').trigger('click')
    await wrapper.get('input[name="secret-name"]').setValue('newSecret')
    await wrapper.get('input[name="secret-value"]').setValue('new-plaintext')
    await wrapper.get('form[data-form="secret"]').trigger('submit')
    await flushPromises()
    expect(secretApi.create).toHaveBeenLastCalledWith('p2', { name: 'newSecret', value: 'new-plaintext' })
    expect(wrapper.find('form[data-form="secret"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('oldSecret')
  })
})
