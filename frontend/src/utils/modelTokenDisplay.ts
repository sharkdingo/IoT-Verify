import type { ModelTokenSource } from '@/types/modelToken'

export type ModelTokenTranslator = (key: string) => string

// These are canonical identifiers from the bundled device manifests and the
// explorer's synthetic `workingState` property. Matching is intentionally exact:
// user-defined identifiers that are not in this list must remain untouched.
const MODEL_TOKEN_MESSAGE_KEYS: Readonly<Record<string, string>> = {
  workingState: 'app.modelTokens.workingState',
  MachineState: 'app.modelTokens.machineState',
  SwitchState: 'app.modelTokens.switchState',
  LockState: 'app.modelTokens.lockState',
  DoorState: 'app.modelTokens.doorState',
  WindowState: 'app.modelTokens.windowState',
  OpenableState: 'app.modelTokens.openableState',
  AlertState: 'app.modelTokens.alertState',
  ScanState: 'app.modelTokens.scanState',
  CarLocation: 'app.modelTokens.carLocation',
  HvacMode: 'app.modelTokens.hvacMode',
  ThermostatMode: 'app.modelTokens.thermostatMode',
  ThermostatFanMode: 'app.modelTokens.thermostatFanMode',
  Mode: 'app.modelTokens.mode',
  State: 'app.modelTokens.state',
  state: 'app.modelTokens.state',
  temperature: 'app.modelTokens.temperature',
  humidity: 'app.modelTokens.humidity',
  illuminance: 'app.modelTokens.illuminance',
  brightness: 'app.modelTokens.brightness',
  battery: 'app.modelTokens.battery',
  motion: 'app.modelTokens.motion',
  contact: 'app.modelTokens.contact',
  smoke: 'app.modelTokens.smoke',
  gas: 'app.modelTokens.gas',
  carbonDioxide: 'app.modelTokens.carbonDioxide',
  airQuality: 'app.modelTokens.airQuality',
  water: 'app.modelTokens.water',
  waterQuality: 'app.modelTokens.waterQuality',
  waterTemperature: 'app.modelTokens.waterTemperature',
  thermostatSetpoint: 'app.modelTokens.thermostatSetpoint',
  thermostatOperatingState: 'app.modelTokens.thermostatOperatingState',
  power: 'app.modelTokens.power',
  energy: 'app.modelTokens.energy',
  progress: 'app.modelTokens.progress',
  operationTime: 'app.modelTokens.operationTime',
  time: 'app.modelTokens.time',
  date: 'app.modelTokens.date',
  weather: 'app.modelTokens.weather',
  season: 'app.modelTokens.season',
  location: 'app.modelTokens.location',
  photo: 'app.modelTokens.photo',
  'token photo': 'app.modelTokens.tokenPhoto',
  content: 'app.modelTokens.content',
  on: 'app.modelTokens.on',
  off: 'app.modelTokens.off',
  open: 'app.modelTokens.open',
  closed: 'app.modelTokens.closed',
  locked: 'app.modelTokens.locked',
  unlocked: 'app.modelTokens.unlocked',
  active: 'app.modelTokens.active',
  inactive: 'app.modelTokens.inactive',
  idle: 'app.modelTokens.idle',
  detected: 'app.modelTokens.detected',
  clear: 'app.modelTokens.clear',
  authorized: 'app.modelTokens.authorized',
  'not authorized': 'app.modelTokens.notAuthorized',
  'scan authorized card': 'app.modelTokens.scanAuthorizedCard',
  'scan unauthorized card': 'app.modelTokens.scanUnauthorizedCard',
  'reset from authorized': 'app.modelTokens.resetFromAuthorized',
  'reset from unauthorized': 'app.modelTokens.resetFromUnauthorized',
  'detect open': 'app.modelTokens.detectOpen',
  'detect closed': 'app.modelTokens.detectClosed',
  'arrive home': 'app.modelTokens.arriveHome',
  'leave home': 'app.modelTokens.leaveHome',
  ready: 'app.modelTokens.ready',
  run: 'app.modelTokens.run',
  running: 'app.modelTokens.running',
  pause: 'app.modelTokens.pause',
  paused: 'app.modelTokens.paused',
  finish: 'app.modelTokens.finish',
  finished: 'app.modelTokens.finished',
  heat: 'app.modelTokens.heat',
  heating: 'app.modelTokens.heating',
  cool: 'app.modelTokens.cool',
  cooling: 'app.modelTokens.cooling',
  auto: 'app.modelTokens.auto',
  dry: 'app.modelTokens.dry',
  fanOnly: 'app.modelTokens.fanOnly',
  'fan only': 'app.modelTokens.fanOnly',
  home: 'app.modelTokens.home',
  away: 'app.modelTokens.away',
  sleep: 'app.modelTokens.sleep',
  'emergency heat': 'app.modelTokens.emergencyHeat',
  emergencyHeat: 'app.modelTokens.emergencyHeat',
  airWash: 'app.modelTokens.airWash',
  autumn: 'app.modelTokens.autumn',
  both: 'app.modelTokens.both',
  circulate: 'app.modelTokens.circulate',
  cleaning: 'app.modelTokens.cleaning',
  cooking: 'app.modelTokens.cooking',
  coolClean: 'app.modelTokens.coolClean',
  defrosting: 'app.modelTokens.defrosting',
  delayWash: 'app.modelTokens.delayWash',
  draining: 'app.modelTokens.draining',
  dryClean: 'app.modelTokens.dryClean',
  dryerJobState: 'app.modelTokens.dryerJobState',
  DryerMode: 'app.modelTokens.dryerMode',
  drying: 'app.modelTokens.drying',
  cloudy: 'app.modelTokens.cloudy',
  foggy: 'app.modelTokens.foggy',
  garage: 'app.modelTokens.garage',
  garageDoor: 'app.modelTokens.garageDoor',
  grill: 'app.modelTokens.grill',
  heatClean: 'app.modelTokens.heatClean',
  heavy: 'app.modelTokens.heavy',
  highHeat: 'app.modelTokens.highHeat',
  Key: 'app.modelTokens.key',
  lowHeat: 'app.modelTokens.lowHeat',
  MothersDay: 'app.modelTokens.mothersDay',
  none: 'app.modelTokens.none',
  others: 'app.modelTokens.others',
  ovenJobState: 'app.modelTokens.ovenJobState',
  OvenMode: 'app.modelTokens.ovenMode',
  'pending cool': 'app.modelTokens.pendingCool',
  'pending heat': 'app.modelTokens.pendingHeat',
  posted: 'app.modelTokens.posted',
  posting: 'app.modelTokens.posting',
  preheat: 'app.modelTokens.preheat',
  preWash: 'app.modelTokens.preWash',
  rainy: 'app.modelTokens.rainy',
  receive: 'app.modelTokens.receive',
  receiveKey: 'app.modelTokens.receiveKey',
  receiveMail: 'app.modelTokens.receiveMail',
  regular: 'app.modelTokens.regular',
  reset: 'app.modelTokens.reset',
  RFID: 'app.modelTokens.rfid',
  rinse: 'app.modelTokens.rinse',
  rinsing: 'app.modelTokens.rinsing',
  sending: 'app.modelTokens.sending',
  sent: 'app.modelTokens.sent',
  sentAlertMessage: 'app.modelTokens.sentAlertMessage',
  sentPhoto: 'app.modelTokens.sentPhoto',
  sendingAlertMessage: 'app.modelTokens.sendingAlertMessage',
  sendingPhoto: 'app.modelTokens.sendingPhoto',
  Shanghai: 'app.modelTokens.shanghai',
  siren: 'app.modelTokens.siren',
  snowy: 'app.modelTokens.snowy',
  spin: 'app.modelTokens.spin',
  spinDry: 'app.modelTokens.spinDry',
  spring: 'app.modelTokens.spring',
  steps: 'app.modelTokens.steps',
  stop: 'app.modelTokens.stop',
  stormy: 'app.modelTokens.stormy',
  strobe: 'app.modelTokens.strobe',
  summer: 'app.modelTokens.summer',
  sunny: 'app.modelTokens.sunny',
  'taking photo': 'app.modelTokens.takingPhoto',
  tested: 'app.modelTokens.tested',
  transferred: 'app.modelTokens.transferred',
  transfering: 'app.modelTokens.transferring',
  'uploaded to cloud': 'app.modelTokens.uploadedToCloud',
  'uploading to cloud': 'app.modelTokens.uploadingToCloud',
  Valve: 'app.modelTokens.valve',
  'vent economizer': 'app.modelTokens.ventEconomizer',
  warming: 'app.modelTokens.warming',
  wash: 'app.modelTokens.wash',
  washing: 'app.modelTokens.washing',
  washerJobState: 'app.modelTokens.washerJobState',
  WasherMode: 'app.modelTokens.washerMode',
  weekdays: 'app.modelTokens.weekdays',
  weekend: 'app.modelTokens.weekend',
  weightSensing: 'app.modelTokens.weightSensing',
  wet: 'app.modelTokens.wet',
  windy: 'app.modelTokens.windy',
  winter: 'app.modelTokens.winter',
  wrinklePrevent: 'app.modelTokens.wrinklePrevent',
  close: 'app.modelTokens.close',
  fanAuto: 'app.modelTokens.fanAuto',
  fanCirculate: 'app.modelTokens.fanCirculate',
  fanOn: 'app.modelTokens.fanOn',
  lock: 'app.modelTokens.lock',
  post: 'app.modelTokens.post',
  'send alert message': 'app.modelTokens.sendAlertMessage',
  'send mail': 'app.modelTokens.sendMail',
  'send photo': 'app.modelTokens.sendPhoto',
  'set away mode': 'app.modelTokens.setAwayMode',
  'set home mode': 'app.modelTokens.setHomeMode',
  'set sleep mode': 'app.modelTokens.setSleepMode',
  'take photo': 'app.modelTokens.takePhoto',
  'transfer money': 'app.modelTokens.transferMoney',
  unlock: 'app.modelTokens.unlock',
  'upload to cloud': 'app.modelTokens.uploadToCloud',
  true: 'app.modelTokens.true',
  false: 'app.modelTokens.false'
}

// NuSMV removes spaces from emitted state names; retain the bundled spelling's translation.
const EMITTED_TOKEN_MESSAGE_KEYS: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(MODEL_TOKEN_MESSAGE_KEYS)
    .filter(([token]) => token.includes(' '))
    .map(([token, key]) => [token.replace(/ /g, ''), key])
)

const formatSingleToken = (token: string, translate: ModelTokenTranslator): string => {
  const key = MODEL_TOKEN_MESSAGE_KEYS[token] || EMITTED_TOKEN_MESSAGE_KEYS[token]
  if (!key) return token
  const translated = translate(key)
  return translated === key ? token : translated
}

export const formatBuiltInModelToken = (
  value: unknown,
  translate: ModelTokenTranslator
): string => {
  const raw = value === null || value === undefined ? '' : String(value)
  if (!/[;,|]/.test(raw)) return formatSingleToken(raw, translate)

  return raw.split(/([;,|])/).map(segment => {
    if (/^[;,|]$/.test(segment)) return segment
    const match = /^(\s*)(.*?)(\s*)$/.exec(segment)
    if (!match) return segment
    return `${match[1]}${formatSingleToken(match[2], translate)}${match[3]}`
  }).join('')
}


export const formatModelTokenBySource = (
  source: ModelTokenSource,
  value: unknown,
  translate: ModelTokenTranslator
): string => {
  const raw = value === null || value === undefined ? '' : String(value)
  return source === 'BUNDLED' ? formatBuiltInModelToken(raw, translate) : raw
}

export const formatModelTokenForTemplate = (
  template: { defaultTemplate?: boolean } | null | undefined,
  value: unknown,
  translate: ModelTokenTranslator
): string => {
  const raw = value === null || value === undefined ? '' : String(value)
  return template?.defaultTemplate === true
    ? formatBuiltInModelToken(raw, translate)
    : raw
}
