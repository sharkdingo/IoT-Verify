import type { TraceState, TraceTrustPrivacy } from '@/types/verify'

type TraceEvidence = {
  deviceTrust?: string | null
  environmentTrust?: string | null
  trustPrivacy?: TraceTrustPrivacy[]
  privacies?: TraceTrustPrivacy[]
}

export const detailedTraceState = (
  stateIndex: number,
  { deviceTrust = null, environmentTrust = null, trustPrivacy = [], privacies = [] }: TraceEvidence = {}
) => ({
  stateIndex,
  devices: [{
    deviceId: 'device_1',
    deviceLabel: 'Hall sensor',
    templateName: 'Motion Detector',
    modelTokenSource: 'BUNDLED',
    state: 'idle',
    compromised: false,
    variables: [{
      name: 'workingState',
      value: 'idle',
      trust: deviceTrust,
      modelTokenSource: 'BUNDLED'
    }],
    trustPrivacy,
    privacies
  }],
  triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Record motion' }],
  compromisedAutomationLinks: [],
  trustPrivacies: [],
  envVariables: [{
    name: 'temperature',
    value: '21',
    trust: environmentTrust,
    modelTokenSource: 'UNKNOWN'
  }],
  globalVariables: [{
    name: 'compromisedPointCount',
    value: '0',
    trust: null,
    modelTokenSource: 'UNKNOWN'
  }]
} satisfies TraceState)

export const malformedTraceStateFields = (state: ReturnType<typeof detailedTraceState>) => {
  const [device] = state.devices
  return {
    malformedDeviceAndReadingFields: [
      { ...state, devices: [{ ...device, modelTokenSource: null }] },
      {
        ...state,
        devices: [{ ...device, variables: [{ name: 'workingState', value: 'idle' }] }]
      },
      {
        ...state,
        envVariables: [{ name: 'temperature', value: '21', trust: 'untrusted' }]
      },
      { ...state, devices: [{ ...device, modelTokenSource: 'FORGED' }] },
      {
        ...state,
        devices: [{
          ...device,
          variables: [{ name: 'workingState', value: 'idle', modelTokenSource: 'CUSTOM' }]
        }]
      },
      {
        ...state,
        devices: [{ ...device, variables: [{ name: 'workingState', value: 1 }] }]
      }
    ],
    invalidEnvironmentTrust: {
      ...state,
      envVariables: [{ name: 'temperature', value: '21', trust: true }]
    },
    invalidGlobalProvenance: {
      ...state,
      globalVariables: [{
        name: 'compromisedPointCount',
        value: '0',
        modelTokenSource: 'BUNDLED'
      }]
    }
  }
}
