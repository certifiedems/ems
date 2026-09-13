// ems_frontend/src/utils/proctoringRules.js
//
// The proctoring rules an administrator controls, as the browser sees them.
//
// The server owns the rules and judges every strike. This file is the shared
// vocabulary the admin screen and the exam page read them through, plus the
// built-in rules both fall back to. Those mirror EffectiveProctoringPolicy on
// the server, so a page that could not load its rules proctors exactly the way
// an exam with no saved rules is proctored.

export const ENFORCEMENT = {
  DISABLED: 'DISABLED',
  RECORD_ONLY: 'RECORD_ONLY',
  STRIKE: 'STRIKE'
}

export const ENFORCEMENT_OPTIONS = [
  { value: ENFORCEMENT.DISABLED, label: 'Off', hint: 'Not monitored' },
  { value: ENFORCEMENT.RECORD_ONLY, label: 'Record only', hint: 'Logged with evidence, no strike' },
  { value: ENFORCEMENT.STRIKE, label: 'Strike', hint: 'Counts toward the strike limit' }
]

/**
 * Every violation an administrator can configure, grouped by where the exam
 * client detects it.
 *
 * Should list the same types the server treats as configurable. The admin
 * screen still renders any extra type the server sends, so the two cannot
 * silently disagree about what a saved policy contains.
 */
export const VIOLATION_GROUPS = [
  {
    key: 'browser',
    title: 'Browser & session',
    description: 'Signals from the exam window itself.',
    violations: [
      {
        type: 'TAB_SWITCH',
        label: 'Tab switch',
        description: 'The candidate switches to another browser tab.'
      },
      {
        type: 'WINDOW_FOCUS_LOST',
        label: 'Window focus lost',
        description: 'The exam window is minimised, or another application is brought in front of it.'
      },
      {
        type: 'FULLSCREEN_EXIT',
        label: 'Fullscreen exit',
        description: 'The candidate leaves fullscreen. When off, the exam does not run in fullscreen at all.'
      },
      {
        type: 'BROWSER_MONITORING',
        label: 'Restricted shortcut',
        description: 'Copy, paste, developer-tools or window shortcuts are pressed. They stay blocked either way.'
      },
      {
        type: 'NETWORK_LOSS',
        label: 'Network loss',
        description: 'The connection drops for more than a few seconds, or the session heartbeat fails.'
      },
      {
        type: 'SCREEN_RECORDING_SUSPECTED',
        label: 'Screen recording suspected',
        description: 'A second recorder, or a readback of the exam content, is attempted.'
      },
      {
        type: 'SESSION_TAMPERING',
        label: 'Session tampering',
        description: 'The exam page is interfered with: the proctoring watermark is removed or hidden, or developer tools are opened during the exam.'
      },
      {
        type: 'MULTIPLE_LOGIN',
        label: 'Multiple login',
        description: 'The same attempt is open in two browsers, tabs or devices at once. Reopening the exam after a crash is not flagged.'
      }
    ]
  },
  {
    key: 'screen',
    title: 'Screen sharing',
    description: 'With both switched off, candidates are not asked to share their screen.',
    violations: [
      {
        type: 'SCREEN_SHARE_STOPPED',
        label: 'Screen share stopped',
        description: 'The candidate stops sharing their screen during the exam.'
      },
      {
        type: 'SCREEN_SHARE_DENIED',
        label: 'Screen share refused',
        description: 'The candidate refuses the screen-sharing prompt when it is requested again.'
      }
    ]
  },
  {
    key: 'camera',
    title: 'Camera',
    description: 'The camera itself, and detections from the on-device vision models. A camera is always required.',
    violations: [
      {
        type: 'WEBCAM_OFF',
        label: 'Camera turned off',
        description: 'The camera stops sending video: unplugged, switched off, taken by another app, or its permission revoked. Also catches a frozen last frame the vision models would keep accepting.'
      },
      {
        type: 'PHONE_DETECTED',
        label: 'Phone detected',
        description: 'A mobile phone is seen in the camera frame.'
      },
      {
        type: 'MULTIPLE_FACES',
        label: 'Multiple faces',
        description: 'More than one person is in front of the camera.'
      },
      {
        type: 'FACE_NOT_VISIBLE',
        label: 'Face not visible',
        description: 'Nobody is visible in the frame for a sustained period.'
      },
      {
        type: 'FACE_TURNED_AWAY',
        label: 'Face turned away',
        description: 'The candidate turns their head away from the screen.'
      },
      {
        type: 'EYES_OFF_SCREEN',
        label: 'Eyes off screen',
        description: 'Gaze moves down or to the side. Inferred from a few pixels of iris movement, so record only by default.'
      },
      {
        type: 'PROCTOR_SETUP_INVALID',
        label: 'Camera setup invalid',
        description: 'The camera angle stops meeting requirements, such as a laptop moved onto a lap. A heuristic, so record only by default.'
      }
    ]
  },
  {
    key: 'microphone',
    title: 'Microphone',
    description: 'Sounds classified by the on-device sound engine. How loud each must be is set under Sound sensitivity.',
    violations: [
      {
        type: 'VOICE_DETECTED',
        label: 'Voice detected',
        description: 'A human voice is heard near the candidate, spoken or whispered.'
      },
      {
        type: 'BACKGROUND_NOISE',
        label: 'Background noise',
        description: 'A sustained sound such as a television, a fan or road noise.'
      },
      {
        type: 'SOUND_DETECTED',
        label: 'Other sound',
        description: 'A sound the engine cannot identify: a door, a chair, a knock. The forgiven-sounds allowance applies.'
      }
    ]
  }
]

export const CONFIGURABLE_VIOLATION_TYPES = VIOLATION_GROUPS.flatMap((group) =>
  group.violations.map((violation) => violation.type)
)

/** The same bounds the server validates. */
export const POLICY_LIMITS = {
  strikeLimit: { min: 1, max: 20 },
  unidentifiedSoundGrace: { min: 0, max: 20 },
  dbAboveFloor: { min: 0, max: 60 }
}

/**
 * The two detections too uncertain to end an attempt on by default: gaze from a
 * couple of pixels of iris movement, and camera geometry from face proportions.
 */
const RECORD_ONLY_BY_DEFAULT = new Set(['EYES_OFF_SCREEN', 'PROCTOR_SETUP_INVALID'])

/**
 * The rules enforced when none have been saved.
 *
 * A voice is held to no loudness bar, because a quiet one is the case that
 * matters most. Other sounds must clear 15 dB above the room, for the candidate
 * with a mechanical keyboard: every keystroke is a genuine impulse, and without
 * a bar their allowance would be spent on typing and the exam ended on it.
 */
export const BUILT_IN_POLICY = Object.freeze({
  strikeLimit: 3,
  unidentifiedSoundGrace: 2,
  voiceMinDbAboveFloor: 0,
  backgroundNoiseMinDbAboveFloor: 0,
  unidentifiedSoundMinDbAboveFloor: 15,
  rules: Object.freeze(Object.fromEntries(CONFIGURABLE_VIOLATION_TYPES.map((type) => [
    type,
    RECORD_ONLY_BY_DEFAULT.has(type) ? ENFORCEMENT.RECORD_ONLY : ENFORCEMENT.STRIKE
  ])))
})

/** The policy field holding the loudness bar for each sound violation. */
export const SOUND_THRESHOLD_FIELD = {
  VOICE_DETECTED: 'voiceMinDbAboveFloor',
  BACKGROUND_NOISE: 'backgroundNoiseMinDbAboveFloor',
  SOUND_DETECTED: 'unidentifiedSoundMinDbAboveFloor'
}

/** How a policy enforces one type. A type it does not mention keeps its built-in rule. */
export const enforcementOf = (policy, type) =>
  policy?.rules?.[type] || BUILT_IN_POLICY.rules[type] || ENFORCEMENT.STRIKE

export const isMonitored = (policy, type) => enforcementOf(policy, type) !== ENFORCEMENT.DISABLED

/** Screen sharing is only worth demanding while one of its two violations is watched for. */
export const requiresScreenShare = (policy) =>
  isMonitored(policy, 'SCREEN_SHARE_STOPPED') || isMonitored(policy, 'SCREEN_SHARE_DENIED')

export const requiresFullscreen = (policy) => isMonitored(policy, 'FULLSCREEN_EXIT')

/** dB above the room's own noise floor a sound of this type must peak at before it is raised. */
export const soundThresholdDb = (policy, type) => {
  const field = SOUND_THRESHOLD_FIELD[type]
  if (!field) {
    return 0
  }
  const value = policy?.[field]
  return Number.isFinite(value) ? value : BUILT_IN_POLICY[field]
}
