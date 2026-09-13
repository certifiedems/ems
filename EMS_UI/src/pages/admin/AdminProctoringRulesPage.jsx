// ems_frontend/src/pages/admin/AdminProctoringRulesPage.jsx
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle,
  Divider, Grid, MenuItem, Paper, Skeleton, Slider, Snackbar, Stack, TextField, ToggleButton,
  ToggleButtonGroup, Typography
} from '@mui/material'
import SaveIcon from '@mui/icons-material/SaveRounded'
import RestoreIcon from '@mui/icons-material/SettingsBackupRestoreRounded'
import UndoIcon from '@mui/icons-material/UndoRounded'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import { fonts, tokens, tone } from '../../styles/tokens'
import {
  BUILT_IN_POLICY, ENFORCEMENT, ENFORCEMENT_OPTIONS, POLICY_LIMITS, VIOLATION_GROUPS
} from '../../utils/proctoringRules'

const DEFAULT_SCOPE = 'DEFAULT'

const ENFORCEMENT_TONE = {
  [ENFORCEMENT.DISABLED]: 'neutral',
  [ENFORCEMENT.RECORD_ONLY]: 'info',
  [ENFORCEMENT.STRIKE]: 'danger'
}

const SOUND_SETTINGS = [
  {
    type: 'VOICE_DETECTED',
    field: 'voiceMinDbAboveFloor',
    label: 'Voice',
    hint: 'Keep this low: a whisper across the desk is the voice that matters.'
  },
  {
    type: 'BACKGROUND_NOISE',
    field: 'backgroundNoiseMinDbAboveFloor',
    label: 'Background noise',
    hint: 'Sustained sources such as a television, a fan or road noise.'
  },
  {
    type: 'SOUND_DETECTED',
    field: 'unidentifiedSoundMinDbAboveFloor',
    label: 'Other sounds',
    hint: 'Raise this if typing or chair noise is being flagged.'
  }
]

const NUMBER_FIELDS = ['strikeLimit', 'unidentifiedSoundGrace', ...SOUND_SETTINGS.map((setting) => setting.field)]

const DB_MARKS = [0, 15, 30, 45, 60].map((value) => ({ value, label: String(value) }))

/** The editable part of a policy: what the form holds and what a save sends. */
const toForm = (policy) => ({
  strikeLimit: policy.strikeLimit,
  unidentifiedSoundGrace: policy.unidentifiedSoundGrace,
  voiceMinDbAboveFloor: policy.voiceMinDbAboveFloor,
  backgroundNoiseMinDbAboveFloor: policy.backgroundNoiseMinDbAboveFloor,
  unidentifiedSoundMinDbAboveFloor: policy.unidentifiedSoundMinDbAboveFloor,
  // Built-in rules first so every known type is present, then whatever the
  // server sent — including a type this screen has no copy for, which has to
  // survive a save rather than be quietly reset to its default.
  rules: { ...BUILT_IN_POLICY.rules, ...(policy.rules || {}) }
})

const sameForm = (a, b) => JSON.stringify(a) === JSON.stringify(b)

const toNumberOrBlank = (raw) => (raw === '' ? '' : Number(raw))

const limitsFor = (field) => {
  if (field === 'strikeLimit') return POLICY_LIMITS.strikeLimit
  if (field === 'unidentifiedSoundGrace') return POLICY_LIMITS.unidentifiedSoundGrace
  return POLICY_LIMITS.dbAboveFloor
}

const rangeError = (value, { min, max }) =>
  (Number.isInteger(value) && value >= min && value <= max ? '' : `A whole number from ${min} to ${max}`)

const humanise = (type) => type.charAt(0) + type.slice(1).toLowerCase().replace(/_/g, ' ')

/** The known groups, plus "Other" for any type the server sends that this screen does not describe. */
const groupsFor = (rules) => {
  const known = new Set(VIOLATION_GROUPS.flatMap((group) => group.violations.map((violation) => violation.type)))
  const unknown = Object.keys(rules).filter((type) => !known.has(type))
  if (unknown.length === 0) {
    return VIOLATION_GROUPS
  }
  return [
    ...VIOLATION_GROUPS,
    {
      key: 'other',
      title: 'Other',
      description: 'Violation types the server knows about that this screen has no description for yet.',
      violations: unknown.map((type) => ({ type, label: humanise(type), description: '' }))
    }
  ]
}

const errorMessage = (err, fallback) => {
  const data = err.response?.data
  if (data?.message === 'Validation failed' && data.errors?.length) {
    return data.errors.join(' · ')
  }
  return data?.message || fallback
}

const lastSavedLine = (policy) => {
  if (!policy) return ''
  if (policy.inheritsDefault) return 'No rules of its own — follows the default'
  if (!policy.updatedAt) return 'Built-in rules — nothing saved yet'
  const when = new Date(policy.updatedAt).toLocaleString()
  return policy.updatedBy ? `Last saved by ${policy.updatedBy} · ${when}` : `Last saved ${when}`
}

const chipSx = (toneKey) => {
  const t = tone[toneKey]
  return {
    height: 22,
    fontSize: '0.68rem',
    fontFamily: fonts.mono,
    color: t.fg,
    background: t.bg,
    border: `1px solid ${t.border}`
  }
}

const SectionHeading = ({ title, subtitle }) => (
  <Box sx={{ mb: 1.5 }}>
    <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{title}</Typography>
    {subtitle && (
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>{subtitle}</Typography>
    )}
  </Box>
)

const ViolationRuleRow = ({ violation, value, onChange, disabled }) => (
  <Box
    sx={{
      display: 'flex',
      flexDirection: { xs: 'column', sm: 'row' },
      alignItems: { xs: 'stretch', sm: 'center' },
      gap: { xs: 1, sm: 2 },
      px: 2,
      py: 1.5
    }}
  >
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Stack direction="row" spacing={1} alignItems="baseline" flexWrap="wrap" useFlexGap>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{violation.label}</Typography>
        {/* The constant the Violations page and the audit log show, so the two can be matched up. */}
        <Typography component="span" sx={{ fontFamily: fonts.mono, fontSize: 10.5, color: tokens.muted }}>
          {violation.type}
        </Typography>
      </Stack>
      {violation.description && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
          {violation.description}
        </Typography>
      )}
    </Box>
    <ToggleButtonGroup
      exclusive
      size="small"
      value={value}
      disabled={disabled}
      onChange={(_, next) => {
        // MUI reports null when the selected button is clicked again; a rule
        // always has exactly one enforcement, so that click changes nothing.
        if (next) onChange(next)
      }}
      aria-label={`${violation.label} enforcement`}
      sx={{ flex: 'none' }}
    >
      {ENFORCEMENT_OPTIONS.map((option) => {
        const t = tone[ENFORCEMENT_TONE[option.value]]
        return (
          <ToggleButton
            key={option.value}
            value={option.value}
            title={option.hint}
            sx={{
              px: 1.5,
              minWidth: 76,
              textTransform: 'none',
              fontSize: 12,
              fontWeight: 600,
              '&.Mui-selected, &.Mui-selected:hover': { color: t.fg, background: t.bg, borderColor: t.border }
            }}
          >
            {option.label}
          </ToggleButton>
        )
      })}
    </ToggleButtonGroup>
  </Box>
)

const AdminProctoringRulesPage = () => {
  const [exams, setExams] = useState([])
  const [scope, setScope] = useState(DEFAULT_SCOPE)
  const [loaded, setLoaded] = useState(null)
  const [form, setForm] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [confirm, setConfirm] = useState(null)
  const latestLoadRef = useRef(0)

  const isExamScope = scope !== DEFAULT_SCOPE

  const loadExams = useCallback(async () => {
    try {
      const res = await adminAPI.getExamProctoringPolicies()
      setExams(res.data.data || [])
    } catch (err) {
      setFeedback({ severity: 'error', msg: errorMessage(err, 'Failed to load exams') })
    }
  }, [])

  const loadPolicy = useCallback(async (target) => {
    // Numbered so a slow response for a scope the admin has already left cannot
    // land on top of the one they are now looking at.
    const requestId = latestLoadRef.current + 1
    latestLoadRef.current = requestId
    setLoading(true)
    setLoadError('')
    try {
      const res = target === DEFAULT_SCOPE
        ? await adminAPI.getDefaultProctoringPolicy()
        : await adminAPI.getExamProctoringPolicy(target)
      if (latestLoadRef.current !== requestId) return
      const policy = res.data.data
      setLoaded(policy)
      setForm(toForm(policy))
    } catch (err) {
      if (latestLoadRef.current !== requestId) return
      setLoaded(null)
      setForm(null)
      setLoadError(errorMessage(err, 'Failed to load proctoring rules'))
    } finally {
      if (latestLoadRef.current === requestId) setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadExams()
  }, [loadExams])

  useEffect(() => {
    loadPolicy(scope)
  }, [scope, loadPolicy])

  const loadedForm = useMemo(() => (loaded ? toForm(loaded) : null), [loaded])
  const dirty = Boolean(form && loadedForm && !sameForm(form, loadedForm))

  const fieldErrors = useMemo(() => (
    form ? Object.fromEntries(NUMBER_FIELDS.map((field) => [field, rangeError(form[field], limitsFor(field))])) : {}
  ), [form])
  const valid = Object.values(fieldErrors).every((message) => !message)

  const counts = useMemo(() => {
    const tally = { [ENFORCEMENT.STRIKE]: 0, [ENFORCEMENT.RECORD_ONLY]: 0, [ENFORCEMENT.DISABLED]: 0 }
    Object.values(form?.rules || {}).forEach((value) => {
      tally[value] = (tally[value] || 0) + 1
    })
    return tally
  }, [form])

  const groups = useMemo(() => groupsFor(form?.rules || {}), [form])
  const builtInForm = useMemo(() => toForm(BUILT_IN_POLICY), [])
  const customExamCount = exams.filter((exam) => exam.customPolicy).length
  const inheritedExam = isExamScope && Boolean(loaded?.inheritsDefault)
  const busy = saving || loading

  /*
   * Saving an unchanged inherited policy is still a real change: it gives the
   * exam rules of its own, so later edits to the default stop reaching it.
   */
  const canSave = Boolean(form) && valid && !busy && (dirty || inheritedExam)

  const setField = (field, value) => setForm((current) => ({ ...current, [field]: value }))

  const setRule = (type, value) =>
    setForm((current) => ({ ...current, rules: { ...current.rules, [type]: value } }))

  const requestScope = (next) => {
    if (next === scope) return
    if (dirty) {
      setConfirm({
        title: 'Discard unsaved changes?',
        body: 'The changes you made to these rules have not been saved and will be lost.',
        confirmLabel: 'Discard and switch',
        onConfirm: () => setScope(next)
      })
      return
    }
    setScope(next)
  }

  const handleSave = async () => {
    setSaving(true)
    // The version this edit started from: the server refuses the save if
    // another admin has saved since, rather than silently overwriting them.
    const payload = { ...form, version: loaded?.version ?? null }
    try {
      const res = isExamScope
        ? await adminAPI.updateExamProctoringPolicy(scope, payload)
        : await adminAPI.updateDefaultProctoringPolicy(payload)
      const policy = res.data.data
      setLoaded(policy)
      setForm(toForm(policy))
      setFeedback({
        severity: 'success',
        msg: isExamScope ? `Rules saved for ${policy.examCode}` : 'Default rules saved'
      })
      if (isExamScope) loadExams()
    } catch (err) {
      setFeedback({
        severity: 'error',
        msg: errorMessage(err, 'Failed to save proctoring rules'),
        reload: err.response?.status === 409
      })
    } finally {
      setSaving(false)
    }
  }

  const handleRevertToDefault = () => {
    setConfirm({
      title: `Revert ${loaded?.examCode} to the default rules?`,
      body: 'This exam’s own rules will be deleted, and attempts that start from now on follow the default rules.',
      confirmLabel: 'Revert to default',
      onConfirm: async () => {
        setSaving(true)
        try {
          const res = await adminAPI.resetExamProctoringPolicy(scope)
          const policy = res.data.data
          setLoaded(policy)
          setForm(toForm(policy))
          setFeedback({ severity: 'success', msg: `${policy.examCode} now follows the default rules` })
          loadExams()
        } catch (err) {
          setFeedback({ severity: 'error', msg: errorMessage(err, 'Failed to revert to the default rules') })
        } finally {
          setSaving(false)
        }
      }
    })
  }

  const runConfirm = () => {
    const action = confirm?.onConfirm
    setConfirm(null)
    action?.()
  }

  let scopeNote
  if (!isExamScope) {
    scopeNote = customExamCount > 0
      ? `These rules apply to every exam without rules of its own. ${customExamCount} exam${customExamCount === 1 ? ' has' : 's have'} their own.`
      : 'These rules apply to every exam.'
  } else if (inheritedExam) {
    scopeNote = `${loaded.examCode} follows the default rules shown below. Saving gives it rules of its own, and later changes to the default no longer reach it.`
  } else {
    scopeNote = `These rules apply only to ${loaded?.examCode || 'this exam'}.`
  }

  let body
  if (loading && !form) {
    body = (
      <Paper sx={{ p: 2.5 }}>
        {[1, 2, 3, 4, 5, 6].map((i) => <Skeleton key={i} height={56} />)}
      </Paper>
    )
  } else if (loadError) {
    body = (
      <Alert
        severity="error"
        action={<Button color="inherit" size="small" onClick={() => loadPolicy(scope)}>Retry</Button>}
      >
        {loadError}
      </Alert>
    )
  } else if (form) {
    body = (
      <>
        <Grid container spacing={2} alignItems="flex-start">
          <Grid item xs={12} lg={8}>
            <Paper sx={{ p: 2.5 }}>
              <SectionHeading
                title="Violations"
                subtitle="Off: not monitored at all. Record only: logged with evidence, no strike. Strike: counts toward the limit."
              />
              {groups.map((group) => (
                <Box key={group.key} sx={{ mt: 2.5 }}>
                  <Typography
                    sx={{
                      fontFamily: fonts.mono,
                      fontSize: 11,
                      letterSpacing: '1.2px',
                      textTransform: 'uppercase',
                      color: tokens.copperLt
                    }}
                  >
                    {group.title}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                    {group.description}
                  </Typography>
                  <Box sx={{ border: `1px solid ${tokens.line}`, borderRadius: 2, overflow: 'hidden' }}>
                    {group.violations.map((violation, index) => (
                      <Box key={violation.type}>
                        {index > 0 && <Divider />}
                        <ViolationRuleRow
                          violation={violation}
                          value={form.rules[violation.type]}
                          onChange={(value) => setRule(violation.type, value)}
                          disabled={busy}
                        />
                      </Box>
                    ))}
                  </Box>
                </Box>
              ))}
            </Paper>
          </Grid>

          <Grid item xs={12} lg={4}>
            <Stack spacing={2}>
              <Paper sx={{ p: 2.5 }}>
                <SectionHeading
                  title="Strike limit"
                  subtitle="The attempt is terminated when the candidate reaches this many strikes."
                />
                <TextField
                  fullWidth
                  type="number"
                  label="Strikes to terminate"
                  value={form.strikeLimit}
                  onChange={(e) => setField('strikeLimit', toNumberOrBlank(e.target.value))}
                  inputProps={{ min: POLICY_LIMITS.strikeLimit.min, max: POLICY_LIMITS.strikeLimit.max, step: 1 }}
                  error={Boolean(fieldErrors.strikeLimit)}
                  helperText={fieldErrors.strikeLimit || (form.strikeLimit === 1
                    ? 'The first strike terminates the attempt.'
                    : `${form.strikeLimit - 1} warning${form.strikeLimit === 2 ? '' : 's'}, then termination on strike ${form.strikeLimit}.`)}
                  disabled={busy}
                />
                <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mt: 2 }}>
                  <Chip size="small" label={`${counts[ENFORCEMENT.STRIKE]} strike`} sx={chipSx('danger')} />
                  <Chip size="small" label={`${counts[ENFORCEMENT.RECORD_ONLY]} record only`} sx={chipSx('info')} />
                  <Chip size="small" label={`${counts[ENFORCEMENT.DISABLED]} off`} sx={chipSx('neutral')} />
                </Stack>
                {counts[ENFORCEMENT.STRIKE] === 0 && (
                  <Alert severity="warning" sx={{ mt: 2 }}>
                    No violation counts as a strike, so no attempt can be terminated under these rules.
                  </Alert>
                )}
              </Paper>

              <Paper sx={{ p: 2.5 }}>
                <SectionHeading
                  title="Sound sensitivity"
                  subtitle="How far above the room's own background level a sound must peak before it is raised. Higher is less sensitive."
                />
                {SOUND_SETTINGS.map((setting) => {
                  const off = form.rules[setting.type] === ENFORCEMENT.DISABLED
                  const level = form[setting.field]
                  return (
                    <Box key={setting.field} sx={{ mt: 2, opacity: off ? 0.55 : 1 }}>
                      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{setting.label}</Typography>
                        <Typography sx={{ fontFamily: fonts.mono, fontSize: 13, color: tokens.copperLt }}>
                          {Number.isInteger(level) ? `${level} dB` : '—'}
                        </Typography>
                      </Stack>
                      <Slider
                        size="small"
                        value={Number.isInteger(level) ? level : 0}
                        min={POLICY_LIMITS.dbAboveFloor.min}
                        max={POLICY_LIMITS.dbAboveFloor.max}
                        step={1}
                        marks={DB_MARKS}
                        valueLabelDisplay="auto"
                        valueLabelFormat={(v) => `${v} dB`}
                        onChange={(_, v) => setField(setting.field, v)}
                        disabled={off || busy}
                        aria-label={`${setting.label}: minimum level above the room`}
                      />
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                        {off ? `${setting.label} is switched off under Violations.` : setting.hint}
                      </Typography>
                    </Box>
                  )
                })}

                <Divider sx={{ my: 2 }} />

                <TextField
                  fullWidth
                  type="number"
                  label="Forgiven other sounds per attempt"
                  value={form.unidentifiedSoundGrace}
                  onChange={(e) => setField('unidentifiedSoundGrace', toNumberOrBlank(e.target.value))}
                  inputProps={{
                    min: POLICY_LIMITS.unidentifiedSoundGrace.min,
                    max: POLICY_LIMITS.unidentifiedSoundGrace.max,
                    step: 1
                  }}
                  error={Boolean(fieldErrors.unidentifiedSoundGrace)}
                  helperText={fieldErrors.unidentifiedSoundGrace || (form.rules.SOUND_DETECTED === ENFORCEMENT.STRIKE
                    ? 'A cough or a dropped pen: the first ones are recorded without a strike.'
                    : 'Only applies while Other sound is set to Strike.')}
                  disabled={busy || form.rules.SOUND_DETECTED !== ENFORCEMENT.STRIKE}
                />
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
                  The sound engine only picks up sounds a few dB above the room in the first place, so the
                  lowest settings behave alike.
                </Typography>
              </Paper>
            </Stack>
          </Grid>
        </Grid>

        <Paper
          sx={{
            position: 'sticky',
            bottom: 16,
            zIndex: 2,
            mt: 2,
            px: 2.5,
            py: 1.5,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 2,
            border: `1px solid ${dirty ? tone.copper.border : tokens.line}`
          }}
        >
          <Typography variant="body2" sx={{ color: dirty ? tokens.copperLt : 'text.secondary' }}>
            {dirty && 'You have unsaved changes'}
            {!dirty && inheritedExam && 'Showing the default rules this exam follows'}
            {!dirty && !inheritedExam && 'No unsaved changes'}
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <Button
              color="inherit"
              onClick={() => setForm(builtInForm)}
              disabled={busy || sameForm(form, builtInForm)}
            >
              Use built-in values
            </Button>
            <Button color="inherit" startIcon={<UndoIcon />} onClick={() => setForm(loadedForm)} disabled={!dirty || busy}>
              Discard
            </Button>
            <Button variant="contained" startIcon={<SaveIcon />} onClick={handleSave} disabled={!canSave}>
              {saving && 'Saving…'}
              {!saving && (inheritedExam ? `Save as ${loaded.examCode} rules` : 'Save rules')}
            </Button>
          </Stack>
        </Paper>
      </>
    )
  }

  return (
    <Box>
      <PageHeader
        title="Proctoring Rules"
        subtitle="Choose which violations are monitored, how many strikes end an attempt, and how loud a sound must be to count"
      />

      <Paper sx={{ p: 2.5, mb: 2 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ xs: 'stretch', md: 'center' }}>
          <TextField
            select
            size="small"
            label="Rules for"
            value={scope}
            onChange={(e) => requestScope(e.target.value)}
            sx={{ minWidth: { md: 380 } }}
          >
            <MenuItem value={DEFAULT_SCOPE}>All exams (default)</MenuItem>
            {exams.map((exam) => (
              <MenuItem key={exam.examId} value={String(exam.examId)}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, width: '100%', minWidth: 0 }}>
                  <Box component="span" sx={{ fontFamily: fonts.mono, fontSize: 11, color: tokens.muted, flex: 'none' }}>
                    {exam.certificationLevel}
                  </Box>
                  <Box
                    component="span"
                    sx={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  >
                    {exam.examCode} · {exam.examName}
                  </Box>
                  {exam.customPolicy && <Chip size="small" label="Custom" sx={chipSx('copper')} />}
                </Box>
              </MenuItem>
            ))}
          </TextField>
          <Typography variant="body2" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>
            {loading ? 'Loading…' : lastSavedLine(loaded)}
          </Typography>
          {isExamScope && loaded && !loaded.inheritsDefault && (
            <Button
              variant="outlined"
              color="inherit"
              startIcon={<RestoreIcon />}
              onClick={handleRevertToDefault}
              disabled={busy}
            >
              Revert to default
            </Button>
          )}
        </Stack>

        {loaded && (
          <Alert severity="info" variant="outlined" sx={{ mt: 2 }}>
            {scopeNote} Saved changes apply to attempts that start afterwards; an attempt already under way keeps
            the rules it started with.
          </Alert>
        )}
      </Paper>

      {body}

      <Dialog open={Boolean(confirm)} onClose={() => setConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{confirm?.title}</DialogTitle>
        <DialogContent>
          <DialogContentText>{confirm?.body}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)}>Cancel</Button>
          <Button variant="contained" color="warning" onClick={runConfirm}>{confirm?.confirmLabel}</Button>
        </DialogActions>
      </Dialog>

      {/* Top rather than bottom: the save bar is pinned to the bottom edge. */}
      <Snackbar
        open={Boolean(feedback)}
        autoHideDuration={feedback?.reload ? null : 5000}
        onClose={(_, reason) => {
          if (reason !== 'clickaway') setFeedback(null)
        }}
        anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
      >
        {feedback && (
          <Alert
            severity={feedback.severity}
            onClose={() => setFeedback(null)}
            action={feedback.reload ? (
              <Button
                color="inherit"
                size="small"
                onClick={() => {
                  setFeedback(null)
                  loadPolicy(scope)
                }}
              >
                Reload
              </Button>
            ) : undefined}
          >
            {feedback.msg}
          </Alert>
        )}
      </Snackbar>
    </Box>
  )
}

export default AdminProctoringRulesPage
