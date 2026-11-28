import { describe, expect, it } from 'vitest'
import { buildPlaybackEdges } from './playbackScene'
import { validateModelPlaybackScene } from '@/utils/playbackSceneResponse'

describe('historical playback scene', () => {
  it('derives canvas edges only from the frozen run scene', () => {
    const scene = {
      nodes: [
        { id: 'old_sensor', templateName: 'Sensor', label: 'Old sensor', position: { x: 40, y: 80 }, state: 'on', width: 160, height: 120 },
        { id: 'old_alarm', templateName: 'Alarm', label: 'Old alarm', position: { x: 420, y: 80 }, state: 'off', width: 160, height: 120 }
      ],
      rules: [{
        id: 17,
        conditions: [{
          deviceName: 'old_sensor',
          attribute: 'state',
          targetType: 'state' as const,
          relation: '=',
          value: 'on'
        }],
        command: { deviceName: 'old_alarm', action: 'on' },
        ruleString: 'Old sensor activates old alarm'
      }]
    }

    expect(buildPlaybackEdges(scene)).toEqual([
      expect.objectContaining({
        from: 'old_sensor',
        to: 'old_alarm',
        ruleId: '17',
        ruleIndex: 0
      })
    ])
  })

  it('rejects malformed nested runtime values before they can reach the canvas', () => {
    const invalidScene = {
      nodes: [{
        id: 'switch',
        templateName: 'Switch',
        label: 'Switch',
        position: { x: 0, y: 0 },
        state: 'off',
        width: 160,
        height: 120,
        variables: [null]
      }],
      rules: []
    }

    expect(() => validateModelPlaybackScene(invalidScene, 1, 0, detail => {
      throw new Error(detail)
    })).toThrow('playbackScene node variables[0] must be an object')
  })
})
