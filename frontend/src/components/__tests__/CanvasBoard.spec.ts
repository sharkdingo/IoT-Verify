import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'

import CanvasBoard from '@/components/CanvasBoard.vue'
import { i18n } from '@/assets/i18n'
import { getLinkPoints, getSelfLoopPath } from '@/utils/rule'

const pointerEvent = (type: string, x: number, y: number) => {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 })
  Object.defineProperties(event, {
    pointerId: { value: 1 },
    isPrimary: { value: true }
  })
  return event
}

describe('CanvasBoard live edge geometry', () => {
  for (const corner of ['tl', 'tr', 'bl', 'br']) {
    it(`updates the self-loop path and hitarea during ${corner} resizing and restores them on cancel`, async () => {
      const node = reactive({
        id: 'light-1', templateName: 'Light', label: 'Hall light',
        position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
      })
      const wrapper = mount(CanvasBoard, {
        props: {
          nodes: [node], edges: [{
            id: 'self', from: node.id, to: node.id, fromLabel: node.label, toLabel: node.label,
            fromPos: node.position, toPos: node.position
          }], pan: { x: 0, y: 0 }, zoom: 1.5,
          getNodeIcon: () => '', hasNodeStateMachine: () => true,
          getNodeEffectiveState: current => current.state
        },
        global: { plugins: [i18n] }
      })
      try {
        const originalPath = getSelfLoopPath(node)
        wrapper.get(`.resize-handle.${corner}`).element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
        window.dispatchEvent(pointerEvent('pointermove', 130, 115))
        await nextTick()
        expect(wrapper.get('path.edge-base-line').attributes('d')).not.toBe(originalPath)
        expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(getSelfLoopPath(node))
        expect(wrapper.get('path.edge-hitarea').attributes('d')).toBe(getSelfLoopPath(node))
        window.dispatchEvent(pointerEvent('pointercancel', 130, 115))
        await nextTick()
        expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
        expect(node).toMatchObject({ position: { x: 40, y: 50 }, width: 176, height: 128 })
        expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
      } finally {
        wrapper.unmount()
      }
    })
  }

  for (const zoom of [0.4, 1.5]) {
    for (const finish of ['pointerup', 'pointercancel', 'lostpointercapture', 'lock'] as const) {
      it(`moves every self-loop layer and both edge labels before ${finish} at zoom ${zoom}`, async () => {
        const source = reactive({
          id: 'light-1', templateName: 'Light', label: 'Hall light',
          position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
        })
        const target = reactive({
          ...source, id: 'light-2', label: 'Other light', position: { x: 420, y: 220 }
        })
        const edges = [source, target].map((node, index) => ({
          id: `edge-${index}`, from: source.id, to: node.id,
          fromLabel: source.label, toLabel: node.label,
          fromPos: source.position, toPos: node.position,
          ruleId: `rule-${index}`, ruleIndex: index, toApi: 'turn on'
        }))
        const wrapper = mount(CanvasBoard, {
          props: {
            nodes: [source, target], edges, pan: { x: 35, y: -20 }, zoom,
            highlightedTrace: { selectedStateIndex: 0, states: [{
              devices: [], triggeredRules: [{ ruleId: 'rule-0', ruleIndex: 0 }]
            }] },
            getNodeIcon: () => '', hasNodeStateMachine: () => true,
            getNodeEffectiveState: node => node.state || 'off'
          },
          global: { plugins: [i18n] }
        })
        try {
          const nodeElement = wrapper.get('[data-node-id="light-1"]')
          const loopHitarea = wrapper.get('path.edge-hitarea')
          await loopHitarea.trigger('focus')
          await wrapper.get('line.edge-hitarea').trigger('pointerenter')
          const originalPath = getSelfLoopPath(source)
          expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
          nodeElement.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
          window.dispatchEvent(pointerEvent('pointermove', 112, 108))
          await nextTick()
          window.dispatchEvent(pointerEvent('pointermove', 140, 120))
          await nextTick()

          const visualNode = { ...source, position: { x: 40 + 40 / zoom, y: 50 + 20 / zoom } }
          for (const selector of ['path.edge-base-line', 'path.edge-hitarea', 'path.particle-line']) {
            expect(wrapper.get(selector).attributes('d')).toBe(getSelfLoopPath(visualNode))
          }
          const points = getLinkPoints(visualNode, target)
          expect(Number(wrapper.get('line.edge-base-line').attributes('x1'))).toBe(points.fromPoint.x)
          expect(Number(wrapper.get('line.edge-base-line').attributes('y1'))).toBe(points.fromPoint.y)
          const labels = wrapper.findAll('.edge-label')
          expect(labels[0].attributes('transform')).toBe(
            `translate(${visualNode.position.x + source.width / 2} ${visualNode.position.y - 16})`
          )
          expect(labels[1].attributes('transform')).toBe(
            `translate(${(points.fromPoint.x + points.toPoint.x) / 2} ${(points.fromPoint.y + points.toPoint.y) / 2 - 10})`
          )
          // Rendering a preview must not persist intermediate coordinates or create undo entries.
          expect(source.position).toEqual({ x: 40, y: 50 })
          expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()

          if (finish === 'lock') await wrapper.setProps({ interactionLocked: true })
          else if (finish === 'lostpointercapture') nodeElement.element.dispatchEvent(pointerEvent(finish, 140, 120))
          else window.dispatchEvent(pointerEvent(finish, 140, 120))
          await nextTick()
          if (finish === 'pointerup') {
            expect(source.position).toEqual(visualNode.position)
            expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
          } else {
            expect(source.position).toEqual({ x: 40, y: 50 })
            expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
            expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
          }
          expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
        } finally {
          wrapper.unmount()
        }
      })
    }
  }
})

describe('CanvasBoard device context actions', () => {
  it('opens the custom device menu on mouse right-click', async () => {
    const node = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Hall light',
      position: { x: 40, y: 50 },
      state: 'off',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    await wrapper.get('[data-node-id="light-1"]').trigger('contextmenu', {
      clientX: 240,
      clientY: 180
    })

    expect(wrapper.emitted('node-context')).toEqual([[node, { x: 240, y: 180 }]])
    expect(wrapper.get('.device-label').attributes('title')).toBe('Hall light')

    await wrapper.get('[data-node-id="light-1"]').trigger('keydown', {
      key: 'F10',
      shiftKey: true
    })
    expect(wrapper.emitted('node-context')).toHaveLength(2)
    const keyboardPayload = wrapper.emitted('node-context')?.[1]
    expect(keyboardPayload?.[0]).toStrictEqual(node)
    expect(keyboardPayload?.[1]).toEqual({ x: expect.any(Number), y: expect.any(Number) })
    wrapper.unmount()
  })

  it('keeps rule labels hidden until the edge is hovered or keyboard-focused', async () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 420, y: 220 },
      state: 'on',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'state',
      toApi: 'take photo',
      itemType: 'variable' as const,
      relation: 'in',
      value: 'active',
      ruleId: 'rule-1'
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        focusedRuleId: 'rule-1',
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working',
        formatNodeModelToken: (_node, value) => ({
          state: '状态',
          active: '活动',
          'take photo': '拍照'
        }[String(value)] || String(value ?? ''))
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.find('.edge-label').exists()).toBe(false)

    const hitarea = wrapper.get('.edge-hitarea')
    await hitarea.trigger('pointerenter')
    expect(wrapper.find('.edge-label').exists()).toBe(true)

    await hitarea.trigger('pointerleave')
    expect(wrapper.find('.edge-label').exists()).toBe(false)

    await hitarea.trigger('focus')
    expect(wrapper.find('.edge-label').exists()).toBe(true)
    /*
     * `∈`, not the translated word. The edge label is an SVG `<text>` on a canvas connector — the tightest space
     * in the product — and the inspector already printed `∈` for the same condition, so the word form meant one
     * operator read two ways on two surfaces a user compares side by side. Set-membership notation is also the
     * more precise reading in a formal-verification product, where `in` is exactly set membership.
     *
     * This assertion is incidental to the test, whose subject is hover/focus visibility; it is updated rather
     * than protected.
     */
    expect(wrapper.get('.edge-label').text()).toContain('Hall motion.状态 ∈ 活动')
    expect(wrapper.get('.edge-label').text()).toContain('Hall camera.拍照')
    expect(edge).toMatchObject({ fromApi: 'state', relation: 'in', value: 'active', toApi: 'take photo' })

    wrapper.unmount()
  })

  it('localizes each playback delta on the changed device while the triggered edge carries command flow', () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Living-room Temperature Sensor',
      position: { x: 420, y: 220 },
      state: 'off',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'motion',
      toApi: 'turn on',
      itemType: 'api' as const,
      ruleId: 'rule-1',
      ruleIndex: 0
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 1,
          states: [
            {
              stateIndex: 1,
              triggeredRules: [],
              compromisedAutomationLinks: [],
              devices: [
                { deviceId: 'motion_1', deviceLabel: source.label, state: 'idle', variables: [] },
                { deviceId: 'light_1', deviceLabel: target.label, state: 'off', variables: [{ name: 'brightness', value: '0' }] }
              ]
            },
            {
              stateIndex: 2,
              triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
              compromisedAutomationLinks: [],
              devices: [
                { deviceId: 'motion_1', deviceLabel: source.label, state: 'active', variables: [] },
                { deviceId: 'light_1', deviceLabel: target.label, state: 'on', variables: [{ name: 'brightness', value: '100' }] }
              ]
            }
          ]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    const changedTarget = wrapper.get('[data-node-id="light-1"]')
    expect(changedTarget.classes()).toContain('trace-changed')
    expect(changedTarget.get('.device-runtime-chip--changed').text()).toContain('100')
    expect(changedTarget.find('[data-testid="trace-change-badge"]').exists()).toBe(false)
    // The node shows the destination value only — no `previous → current` pair.
    //
    // This assertion had no recorded reason, so it read as an accident: `previousValue` is computed in
    // `getNodeRuntimeBadges` and `.device-runtime-chip__previous` is fully styled in `board.css`, yet
    // nothing rendered either. I rendered the pair, and the numbers said this was right all along —
    // `.device-runtime-chip--changed` is capped at `58cqmin`, i.e. **64px on a standard 150×110 node**,
    // where "Temperature 24 → 26" truncates to a fragment. A fragment is worse than the destination value.
    //
    // The transition lives in the popover anchored to the node, which has room for it, and in
    // `badge.title` for hover and assistive technology. Keeping the reason here so the next reader does
    // not re-litigate it from the unused CSS.
    expect(changedTarget.find('.device-runtime-chip__previous').exists()).toBe(false)
    expect(wrapper.findAll('.edge-line--active').length).toBeGreaterThan(0)
    expect(wrapper.find('.particle-line').exists()).toBe(true)

    wrapper.unmount()
  })

  it('restarts delivered-rule edge flow for each model transition', async () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Hall light',
      position: { x: 420, y: 220 },
      state: 'off',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'motion',
      toApi: 'turn on',
      itemType: 'variable' as const,
      relation: '=',
      value: 'active',
      ruleId: 'rule-1',
      ruleIndex: 0
    }
    const states = [
      { triggeredRules: [], compromisedAutomationLinks: [], devices: [] },
      {
        triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
        compromisedAutomationLinks: [],
        devices: []
      },
      {
        triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
        compromisedAutomationLinks: [],
        devices: []
      }
    ]
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: { states, selectedStateIndex: 1 },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    const firstFlow = wrapper.get('.particle-line').element
    expect(firstFlow.getAttribute('data-playback-state')).toBe('1')
    expect(wrapper.html()).toContain('repeatCount="1"')
    expect(wrapper.html()).not.toContain('repeatCount="indefinite"')

    await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 2 } })
    await wrapper.vm.$nextTick()

    const secondFlow = wrapper.get('.particle-line').element
    expect(secondFlow.getAttribute('data-playback-state')).toBe('2')
    expect(secondFlow).not.toBe(firstFlow)
    wrapper.unmount()
  })

  it('replays every semantic device change and renders the new visual state immediately', async () => {
    vi.useFakeTimers()
    const node = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 80, y: 80 },
      state: 'off',
      width: 176,
      height: 128
    }
    const states = [
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'off',
          variables: [],
          trustPrivacy: [{ name: 'off', propertyScope: 'state' as const, trust: true }]
        }]
      },
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'off',
          variables: [],
          trustPrivacy: [{ name: 'off', propertyScope: 'state' as const, trust: false }]
        }]
      },
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'on',
          variables: [],
          trustPrivacy: [{ name: 'on', propertyScope: 'state' as const, trust: false }]
        }]
      }
    ]
    const getNodeIcon = vi.fn((_node: typeof node, state?: string) => `/${state}.svg`)
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: { states, selectedStateIndex: 0 },
        getNodeIcon,
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    try {
      await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 1 } })
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      const changedNode = wrapper.get('[data-node-id="camera-1"]')
      expect(changedNode.classes()).toContain('trace-changed')
      expect(changedNode.classes()).toContain('trace-change-pulse')

      await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 2 } })
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      expect(changedNode.classes()).toContain('trace-change-pulse')
      expect(changedNode.get('.device-state-value').text()).toBe('on')
      expect(changedNode.get('.device-img').attributes('src')).toBe('/on.svg')

      await wrapper.setProps({ highlightedTrace: null })
      await wrapper.vm.$nextTick()
      expect(changedNode.classes()).not.toContain('trace-change-pulse')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('localizes playback security labels without changing canonical trace facts', () => {
    const node = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 80, y: 80 },
      state: 'off',
      width: 176,
      height: 128
    }
    const highlightedTrace = {
      selectedStateIndex: 0,
      states: [{
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          templateName: 'Camera',
          modelTokenSource: 'BUNDLED' as const,
          mode: 'MachineState',
          state: 'on',
          variables: [],
          trustPrivacy: [{ name: 'on', propertyScope: 'state' as const, mode: 'MachineState', trust: false }],
          privacies: [{ name: 'photo', propertyScope: 'content' as const, privacy: 'private' }]
        }]
      }]
    }
    const canonicalSnapshot = structuredClone(highlightedTrace)
    const labels: Record<string, string> = {
      MachineState: '设备状态',
      on: '开启',
      photo: '照片'
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working',
        formatPlaybackModelToken: (source, value) => source === 'BUNDLED'
          ? (labels[String(value)] || String(value ?? ''))
          : String(value ?? '')
      },
      global: { plugins: [i18n] }
    })

    const titles = wrapper.findAll('.device-node-trust').map(badge => badge.attributes('title'))
    expect(titles.some(title => title?.includes('设备状态: 开启'))).toBe(true)
    expect(titles.some(title => title?.includes('照片'))).toBe(true)
    expect(titles.join(' ')).not.toContain('MachineState: on')
    expect(highlightedTrace).toEqual(canonicalSnapshot)
    wrapper.unmount()
  })

  it('shows effective template security labels and identifies instance overrides', async () => {
    const previousLocale = i18n.global.locale.value
    i18n.global.locale.value = 'en'
    const node = {
      id: 'sensor-1',
      templateName: 'Private Sensor',
      label: 'Hall sensor',
      position: { x: 80, y: 80 },
      state: 'active',
      width: 176,
      height: 128
    }
    const template = {
      name: 'Private Sensor',
      manifest: {
        Name: 'Private Sensor',
        Modes: ['Power'],
        InitState: 'active',
        WorkingStates: [{ Name: 'active', Trust: 'untrusted', Privacy: 'private' }],
        InternalVariables: [{
          Name: 'reading',
          IsInside: true,
          FalsifiableWhenCompromised: true,
          Trust: 'untrusted',
          Privacy: 'private',
          Values: ['idle', 'active']
        }]
      }
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        deviceTemplates: [template],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'active'
      },
      global: { plugins: [i18n] }
    })

    try {
      const inheritedTitles = wrapper.findAll('.device-node-trust').map(badge => badge.attributes('title')).join(' ')
      expect(inheritedTitles).toContain('Current state (template default)')
      expect(inheritedTitles).toContain('reading (template default)')
      expect(inheritedTitles).toContain('propagation analysis')

      await wrapper.setProps({
        nodes: [{
          ...node,
          currentStateTrust: 'trusted',
          currentStatePrivacy: 'public',
          variables: [{ name: 'reading', value: 'active', trust: 'trusted' }],
          privacies: [{ name: 'reading', privacy: 'public' }]
        }]
      })
      const badges = wrapper.findAll('.device-node-trust')
      expect(badges).toHaveLength(1)
      expect(badges[0].text()).toContain('Shown sources trusted')
      expect(badges[0].attributes('title')).toContain('instance override')
    } finally {
      wrapper.unmount()
      i18n.global.locale.value = previousLocale
    }
  })

  it('formats playback state and variables only from their frozen token sources', () => {
    const nodes = [
      { id: 'bundled-1', label: 'Bundled state', templateName: 'Current bundled', position: { x: 0, y: 0 }, state: 'current', width: 176, height: 128 },
      { id: 'custom-1', label: 'Custom state', templateName: 'Current bundled', position: { x: 200, y: 0 }, state: 'current', width: 176, height: 128 },
      { id: 'unknown-1', label: 'Unknown state', templateName: 'Current bundled', position: { x: 400, y: 0 }, state: 'current', width: 176, height: 128 }
    ]
    const liveFormatter = vi.fn((_node, value) => `current:${String(value ?? '')}`)
    const playbackFormatter = vi.fn((source, value) => source === 'BUNDLED'
      ? `history:${String(value ?? '')}`
      : String(value ?? ''))
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes,
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [
              {
                deviceId: 'bundled_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'BUNDLED' as const,
                variables: [{ name: 'workingState', value: 'on', trust: 'untrusted', modelTokenSource: 'BUNDLED' as const }]
              },
              {
                deviceId: 'custom_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'CUSTOM' as const,
                variables: [{ name: 'workingState', value: 'on', trust: 'untrusted', modelTokenSource: 'CUSTOM' as const }]
              },
              {
                deviceId: 'unknown_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'UNKNOWN' as const,
                variables: [{ name: 'workingState', value: 'on', modelTokenSource: 'UNKNOWN' as const }]
              }
            ]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: () => 'current',
        formatNodeModelToken: liveFormatter,
        formatPlaybackModelToken: playbackFormatter
      },
      global: { plugins: [i18n] }
    })

    const bundled = wrapper.get('[data-node-id="bundled-1"]')
    expect(bundled.get('.device-state-value').text()).toBe('history:off')
    expect(bundled.get('.device-runtime-chip__label').text()).toBe('history:workingState')
    expect(bundled.get('.device-runtime-chip__value').text()).toBe('history:on')
    expect(bundled.get('.device-node-trust--trust').attributes('title')).toContain('history:workingState')

    const custom = wrapper.get('[data-node-id="custom-1"]')
    expect(custom.get('.device-state-value').text()).toBe('off')
    expect(custom.get('.device-runtime-chip__label').text()).toBe('workingState')
    expect(custom.get('.device-runtime-chip__value').text()).toBe('on')
    expect(custom.get('.device-node-trust--trust').attributes('title')).toContain('workingState')
    expect(custom.get('.device-node-trust--trust').attributes('title')).not.toContain('history:workingState')

    const unknown = wrapper.get('[data-node-id="unknown-1"]')
    expect(unknown.get('.device-state-value').text()).toBe('off')
    expect(unknown.get('.device-runtime-chip__label').text()).toBe('workingState')
    expect(unknown.get('.device-runtime-chip__value').text()).toBe('on')
    expect(liveFormatter).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  // The canvas strip is where the contradiction was visible: the demo scene drew "illuminance 0" on the
  // Porch Light beside an environment strip reading 20, because an affect-only shared declaration is
  // declared-unconstrained and NuSMV prints an arbitrary domain member. The backend now sends the row
  // with `observed: false` and an empty value, and the chip must disappear rather than render blank —
  // `getNodeRuntimeBadges` has a second disjunct that resurrects any merely-empty value.
  it('drops an unobserved trace variable from the node strip and keeps observed ones', () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [{
          id: 'light-1', label: 'Porch Light', templateName: 'Light',
          position: { x: 0, y: 0 }, state: 'on', width: 176, height: 128
        }],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [{
              deviceId: 'light_1',
              state: 'on',
              modelTokenSource: 'BUNDLED' as const,
              variables: [
                {
                  name: 'illuminance', value: '', trust: 'untrusted',
                  observed: false, modelTokenSource: 'BUNDLED' as const
                },
                {
                  name: 'brightness', value: '80', trust: 'trusted',
                  modelTokenSource: 'BUNDLED' as const
                }
              ]
            }]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: () => 'on'
      },
      global: { plugins: [i18n] }
    })

    const node = wrapper.get('[data-node-id="light-1"]')
    const labels = node.findAll('.device-runtime-chip__label').map(chip => chip.text())
    expect(labels).toEqual(['brightness'])
    expect(node.text()).not.toContain('illuminance')
    wrapper.unmount()
  })

  it('uses historical state-machine evidence instead of the current template', () => {
    const recordedStateful = {
      id: 'was-stateful-1',
      templateName: 'Now stateless',
      label: 'Was stateful',
      position: { x: 20, y: 30 },
      state: 'current-placeholder',
      width: 176,
      height: 128
    }
    const recordedStateless = {
      id: 'was-stateless-1',
      templateName: 'Now stateful',
      label: 'Was stateless',
      position: { x: 220, y: 30 },
      state: 'current-state',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [recordedStateful, recordedStateless],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [
              {
                deviceId: 'was_stateful_1',
                state: 'historic-state',
                mode: 'HistoricMode',
                modelTokenSource: 'CUSTOM' as const,
                variables: []
              },
              {
                deviceId: 'was_stateless_1',
                modelTokenSource: 'BUNDLED' as const,
                variables: []
              }
            ]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: node => node.id === recordedStateless.id,
        getNodeEffectiveState: node => node.state || 'Working',
        formatPlaybackModelToken: (_source, value) => String(value ?? '')
      },
      global: { plugins: [i18n] }
    })

    const stateful = wrapper.get('[data-node-id="was-stateful-1"]')
    expect(stateful.get('.device-state').classes()).toContain('state-defined')
    expect(stateful.get('.device-state-value').text()).toBe('historic-state')

    const stateless = wrapper.get('[data-node-id="was-stateless-1"]')
    expect(stateless.get('.device-state').classes()).toContain('state-stateless')
    expect(stateless.get('.device-state-value').text()).toBe(i18n.global.t('app.noStateMachine'))
    wrapper.unmount()
  })

  it('shows a localized stateless label instead of the persistence fallback state', () => {
    const node = {
      id: 'sensor-1',
      templateName: 'Temperature Sensor',
      label: 'Hall sensor',
      position: { x: 20, y: 30 },
      state: 'Working',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => false,
        getNodeEffectiveState: () => 'Working'
      },
      global: { plugins: [i18n] }
    })

    const rendered = wrapper.get('[data-node-id="sensor-1"]')
    expect(rendered.get('.device-state').classes()).toContain('state-stateless')
    expect(rendered.get('.device-state-value').text()).toBe(i18n.global.t('app.noStateMachine'))
    expect(rendered.attributes('title')).not.toContain('Working')
    wrapper.unmount()
  })

  it('uses compact, condensed, and expanded tiers and supports keyboard resizing', async () => {
    const node = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Living-room Temperature Sensor',
      position: { x: 20, y: 30 },
      state: 'off',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'off'
      },
      global: { plugins: [i18n] }
    })
    const rendered = wrapper.get('[data-node-id="light-1"]')

    expect(rendered.classes()).toContain('device-node--expanded')
    expect(rendered.findAll('.resize-handle')).toHaveLength(4)
    await wrapper.setProps({ zoom: 0.7 })
    expect(rendered.classes()).toContain('device-node--condensed')
    expect(rendered.findAll('.resize-handle')).toHaveLength(4)
    await wrapper.setProps({ zoom: 0.5 })
    expect(rendered.classes()).toContain('device-node--compact')
    expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    expect(rendered.get('.device-label').text()).toBe('Living-room Temperature Sensor')
    expect(rendered.attributes('style')).toContain('--canvas-zoom: 0.5')

    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    await rendered.trigger('keydown', { key: 'ArrowDown', ctrlKey: true, shiftKey: true })
    expect(node.width).toBe(186)
    expect(node.height).toBe(129)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['light-1'], ['light-1']])

    await wrapper.setProps({ interactionLocked: true })
    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    expect(node.width).toBe(186)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['light-1'], ['light-1']])
    wrapper.unmount()
  })

  it('keeps one pointer resize handle on minimum-sized nodes at low zoom, so they can be grown', async () => {
    /*
     * At zoom 0.4, an 80×60 node occupies 32×24 screen pixels. Before the fix, the 52px threshold hid all
     * handles — users could not resize at all except via keyboard, which is not discoverable. The new logic
     * guarantees the br handle for nodes at their minimum size regardless of zoom, so pointer resize stays
     * available. The other three handles remain hidden since 32×24 < 88, which avoids crowding.
     */
    const node = {
      id: 'small-light-1',
      templateName: 'Light',
      label: 'Small light',
      position: { x: 20, y: 30 },
      state: 'off',
      width: 80,
      height: 60
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 0.4,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'off'
      },
      global: { plugins: [i18n] }
    })
    const rendered = wrapper.get('[data-node-id="small-light-1"]')

    // One handle (br) to grow the node, not four (which would crowd a 32×24 screen footprint).
    expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    expect(rendered.find('.resize-handle.br').exists()).toBe(true)
    expect(rendered.find('.resize-handle.tl').exists()).toBe(false)

    // Keyboard resize still works (unchanged).
    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    expect(node.width).toBe(90)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['small-light-1']])

    // At zoom 1.0, the node is now 90×60 on screen, still showing only br (90 < 88 for all four).
    await wrapper.setProps({ zoom: 1 })
    expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    wrapper.unmount()
  })

})
