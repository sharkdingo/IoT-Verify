// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import ControlCenter from '../ControlCenter.vue'

const auth = vi.hoisted(() => ({ token: 'session-A' }))
const authState = reactive(auth)
vi.mock('@/stores/auth', () => ({ useAuth: () => ({
  state: reactive(auth), getToken: () => auth.token,
  isCurrentSessionToken: (token: string) => token === auth.token
}) }))
const notices = vi.hoisted(() => ({ error: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: notices, ElTooltip: { inheritAttrs: false, template: '<slot />' } }))
const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: { app: {} } }, missingWarn: false, fallbackWarn: false })
const template = { id: 1, name: 'Local probe', manifest: { Name: 'Local probe', Modes: [], WorkingStates: [],
  InternalVariables: [{ Name: 'level', IsInside: true, LowerBound: 0, UpperBound: 100 }], APIs: [], Transitions: [] } }
const wrappers: VueWrapper[] = []
const focus = vi.spyOn(HTMLElement.prototype, 'focus')
const scrollIntoView = vi.fn()
Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })
const editor = async (attachTo?: HTMLElement) => {
  const wrapper = mount(ControlCenter, { attachTo, props: { deviceTemplates: [template], activeSection: 'devices' }, global: { plugins: [i18n] } })
  wrappers.push(wrapper)
  await wrapper.get('[data-testid="single-device-template"]').setValue(template.name)
  await wrapper.get('[data-testid="single-device-name"]').setValue('Hall probe')
  return wrapper
}
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); auth.token = 'session-A'; vi.clearAllMocks() })

describe('single device creation field ownership', () => {
  it('explains missing template and name fields without dispatching a doomed creation', async () => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-template"]').setValue('')
    expect(wrapper.get('[data-testid="single-device-create"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="single-device-readiness"]').text()).toBe(i18n.global.t('app.selectDeviceTemplate'))
    await (wrapper.vm as any).handleCreateDevice()
    expect(wrapper.emitted('create-device')).toBeUndefined()
    expect(notices.warning).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="single-device-template"]').setValue(template.name)
    await wrapper.get('[data-testid="single-device-name"]').setValue('')
    expect(wrapper.get('[data-testid="single-device-readiness"]').text()).toBe(i18n.global.t('app.enterDeviceName'))
  })
  it('reveals the current field error without moving focus or scrolling valid and unfocused edits', async () => {
    const wrapper = await editor(document.body)
    const input = wrapper.get<HTMLInputElement>('[data-testid="single-device-variable-level"]')
    input.element.focus()
    focus.mockClear()
    await input.setValue('1.5')
    expect(document.activeElement).toBe(input.element)
    expect(focus).not.toHaveBeenCalled()
    expect(scrollIntoView).toHaveBeenCalledExactlyOnceWith({ block: 'nearest' })
    expect(scrollIntoView.mock.contexts[0]).toBe(wrapper.get('[data-testid="single-device-value-error-level"]').element)
    scrollIntoView.mockClear()
    await input.setValue('29')
    expect(scrollIntoView).not.toHaveBeenCalled()
    input.element.blur()
    await input.setValue('101')
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it.each(['1.5', '101'])('keeps invalid local value %s inline and prevents both button and handler dispatch', async value => {
    const wrapper = await editor()
    const input = wrapper.get('[data-testid="single-device-variable-level"]')
    await input.setValue(value)
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('aria-describedby')).toBe('single-device-value-error-level')
    expect(wrapper.get('[data-testid="single-device-value-error-level"]').text()).toBe('app.variableValueIntegerDomain')
    expect(wrapper.get('[data-testid="single-device-create"]').attributes('disabled')).toBeDefined()
    await (wrapper.vm as any).handleCreateDevice()
    expect(wrapper.emitted('create-device')).toBeUndefined()
    expect(notices.error).not.toHaveBeenCalled()
    expect(focus).not.toHaveBeenCalled()
    expect(scrollIntoView).not.toHaveBeenCalled()
    await input.setValue('29')
    expect(input.attributes('aria-invalid')).toBeUndefined()
    expect(wrapper.get('[data-testid="single-device-create"]').attributes('disabled')).toBeUndefined()
  })

  it.each(['', '29', '+01'])('submits the legal/default local value %s and resets only its current draft', async value => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-variable-level"]').setValue(value)
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    expect(submission.runtime?.variables).toEqual(value ? [{ name: 'level', value }] : undefined)
    submission.complete(true)
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-name"]').element.value).toBe('')
    expect(wrapper.find('[data-testid="single-device-save-failure"]').exists()).toBe(false)
  })

  it('keeps a current backend rejection and draft in the form and clears the failure on correction', async () => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-variable-level"]').setValue('29')
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    const reason = "Variable value out of range for 'level': 101 (allowed 0..100)"
    submission.complete(false, { cause: `nodes[0].variables[0].value: ${reason}`, details: ['HTTP 422'], fields: { 'nodes[0].variables[0].value': reason } })
    await flushPromises()
    const failure = wrapper.get('[data-testid="single-device-save-failure"]')
    expect(failure.attributes('role')).toBe('alert')
    expect(failure.attributes('tabindex')).toBe('-1')
    expect(focus).toHaveBeenCalledExactlyOnceWith({ preventScroll: true })
    expect(focus.mock.contexts[0]).toBe(failure.element)
    expect(scrollIntoView).toHaveBeenCalledExactlyOnceWith({ block: 'nearest' })
    const action = failure.element.parentElement!
    expect(scrollIntoView.mock.contexts[0]).toBe(action)
    expect(action.querySelector('[data-testid="single-device-create"]')).toBe(wrapper.get('[data-testid="single-device-create"]').element)
    expect(wrapper.get('[data-testid="single-device-create"]').attributes('disabled')).toBeUndefined()
    expect(failure.get('[data-testid="failure-fields"]').text()).toContain(reason)
    expect(failure.find('[data-testid="failure-next-step"]').exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-variable-level"]').element.value).toBe('29')
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-name"]').element.value).toBe('Hall probe')
    expect(notices.error).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="single-device-variable-level"]').setValue('30')
    expect(wrapper.find('[data-testid="single-device-save-failure"]').exists()).toBe(false)
  })

  it.each([false, true])('an old completion (saved=%s) cannot change a newer draft', async saved => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    ;(wrapper.vm as any).deviceForm.name = 'New draft'
    ;(wrapper.vm as any).singleDeviceRuntime.variables.level = '30'
    submission.complete(saved, { cause: 'Old rejection', details: [], fields: {} })
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-name"]').element.value).toBe('New draft')
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-variable-level"]').element.value).toBe('30')
    expect(wrapper.find('[data-testid="single-device-save-failure"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="single-device-create"]').attributes('disabled')).toBeUndefined()
    expect(focus).not.toHaveBeenCalled()
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it.each(['closed', 'account', 'unmounted'].flatMap(retired => [false, true].map(saved => ({ retired, saved }))))('an old completion cannot reattach after $retired (saved=$saved)', async ({ retired, saved }) => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    if (retired === 'closed') await wrapper.setProps({ activeSection: 'templates' })
    if (retired === 'account') authState.token = 'session-B'
    if (retired === 'unmounted') wrapper.unmount()
    submission.complete(saved, { cause: 'Old rejection', details: [], fields: {} })
    await flushPromises()
    if (retired === 'closed') await wrapper.setProps({ activeSection: 'devices' })
    expect(wrapper.find('[data-testid="single-device-save-failure"]').exists()).toBe(false)
    expect(focus).not.toHaveBeenCalled()
    expect(scrollIntoView).not.toHaveBeenCalled()
    if (retired !== 'unmounted') expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-name"]').element.value).toBe('Hall probe')
  })

  it.each(['draft', 'mode', 'section', 'account', 'unmounted'])('does not steal focus when the accepted failure is retired before its render tick by %s', async retired => {
    const wrapper = await editor()
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    submission.complete(false, { cause: 'Current rejection', details: ['HTTP 422'], fields: { value: 'Out of range' } })
    await Promise.resolve()
    expect(focus).not.toHaveBeenCalled()
    if (retired === 'draft') (wrapper.vm as any).deviceForm.name = 'New draft'
    if (retired === 'mode') (wrapper.vm as any).deviceCreateMode = 'multiple'
    if (retired === 'section') await wrapper.setProps({ activeSection: 'templates' })
    if (retired === 'account') authState.token = 'session-B'
    if (retired === 'unmounted') wrapper.unmount()
    await flushPromises()
    expect(focus).not.toHaveBeenCalled()
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('does not treat a state-derived enum value as an editable local override', async () => {
    const wrapper = await editor()
    const derived = { id: 2, name: 'Derived probe', manifest: { Name: 'Derived probe', Modes: ['Location'], InitState: 'home',
      WorkingStates: [{ Name: 'home', Dynamics: [{ VariableName: 'place', Value: 'home' }] }, { Name: 'away', Dynamics: [{ VariableName: 'place', Value: 'away' }] }],
      InternalVariables: [{ Name: 'place', IsInside: true, Values: ['home', 'away'] }], APIs: [], Transitions: [] } }
    await wrapper.setProps({ deviceTemplates: [derived] })
    await wrapper.get('[data-testid="single-device-template"]').setValue(derived.name)
    expect(wrapper.find('[data-testid="single-device-variable-place"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="single-device-variable-derived-place"]').text()).toContain('home')
    expect(wrapper.find('[data-testid="single-device-value-error-place"]').exists()).toBe(false)
    await wrapper.get('[data-testid="single-device-create"]').trigger('click')
    const submission = wrapper.emitted('create-device')![0]![0] as any
    expect(submission.runtime.variables).toEqual([{ name: 'place', value: 'home' }])
    submission.complete(true)
    await flushPromises()
  })
})
