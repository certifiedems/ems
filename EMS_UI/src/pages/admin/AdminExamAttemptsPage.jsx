// ems_frontend/src/pages/admin/AdminExamAttemptsPage.jsx
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Box, Button, Grid, Paper, Skeleton, Snackbar, Stack, TextField, Typography
} from '@mui/material'
import SaveIcon from '@mui/icons-material/SaveRounded'
import UndoIcon from '@mui/icons-material/UndoRounded'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import { fonts, tone } from '../../styles/tokens'

/** The same bounds ExamAttemptPolicyRequest validates on the server. */
const ATTEMPT_LIMITS = { min: 1, max: 10 }

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`

const errorMessage = (err, fallback) => {
  const data = err.response?.data
  if (data?.message === 'Validation failed' && data.errors?.length) {
    return data.errors.join(' · ')
  }
  return data?.message || fallback
}

const rangeError = (value) =>
  (Number.isInteger(value) && value >= ATTEMPT_LIMITS.min && value <= ATTEMPT_LIMITS.max
    ? ''
    : `A whole number from ${ATTEMPT_LIMITS.min} to ${ATTEMPT_LIMITS.max}`)

const lastSavedLine = (policy) => {
  if (!policy.updatedAt) return 'Built-in value — nothing saved yet'
  const when = new Date(policy.updatedAt).toLocaleString()
  return policy.updatedBy ? `Last saved by ${policy.updatedBy} · ${when}` : `Last saved ${when}`
}

/** What the number means for a candidate, in the terms they meet it in. */
const allowanceLine = (attempts) => (attempts === 1
  ? 'One attempt. A candidate who fails or is terminated pays again to retake.'
  : `The first attempt plus ${plural(attempts - 1, 'free retake')} after a fail or a termination.`)

/*
 * Whether the question bank can keep every attempt fresh. Said beside the
 * number rather than refused on save: an allowance past this point still
 * works, and the bank can grow after the allowance is set.
 */
const QuestionBankNote = ({ attempts, distinctPapers }) => {
  if (distinctPapers == null) {
    return (
      <Typography variant="caption" color="text.secondary">
        No published exam at this level yet, so the question bank cannot be checked against a paper.
      </Typography>
    )
  }
  if (Number.isInteger(attempts) && attempts > distinctPapers) {
    return (
      <Alert severity="warning" variant="outlined">
        The question bank has enough questions for {plural(distinctPapers, 'attempt')} with no repeats.
        Attempts after that reuse the questions the candidate saw longest ago — add questions at this level
        to keep every attempt fresh.
      </Alert>
    )
  }
  return (
    <Typography variant="caption" color="text.secondary">
      The question bank has enough questions for {plural(distinctPapers, 'attempt')} with no repeats.
    </Typography>
  )
}

const LevelCard = ({ policy, draft, onChange, onSave, saving }) => {
  const error = rangeError(draft)
  const dirty = draft !== policy.attemptsPerPayment
  const t = tone.copper

  return (
    <Paper sx={{ p: 2.5, height: '100%', display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Stack direction="row" spacing={1.5} alignItems="center">
        <Box
          sx={{
            flex: 'none',
            width: 42,
            height: 42,
            borderRadius: '12px',
            display: 'grid',
            placeItems: 'center',
            fontFamily: fonts.mono,
            fontWeight: 700,
            color: t.fg,
            background: t.bg,
            border: `1.5px solid ${t.border}`
          }}
        >
          {policy.certificationLevel}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
            {policy.certificationLevel} exam
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            {lastSavedLine(policy)}
          </Typography>
        </Box>
      </Stack>

      <TextField
        fullWidth
        type="number"
        label="Attempts per payment"
        value={draft}
        onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
        inputProps={{ min: ATTEMPT_LIMITS.min, max: ATTEMPT_LIMITS.max, step: 1 }}
        error={Boolean(error)}
        helperText={error || allowanceLine(draft)}
        disabled={saving}
      />

      <QuestionBankNote attempts={draft} distinctPapers={policy.distinctPapers} />

      <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 'auto' }}>
        <Button
          color="inherit"
          startIcon={<UndoIcon />}
          onClick={() => onChange(policy.attemptsPerPayment)}
          disabled={!dirty || saving}
        >
          Discard
        </Button>
        <Button
          variant="contained"
          startIcon={<SaveIcon />}
          onClick={onSave}
          disabled={!dirty || Boolean(error) || saving}
        >
          {saving ? 'Saving…' : 'Save'}
        </Button>
      </Stack>
    </Paper>
  )
}

const AdminExamAttemptsPage = () => {
  const [policies, setPolicies] = useState(null)
  const [drafts, setDrafts] = useState({})
  const [loadError, setLoadError] = useState('')
  const [savingLevel, setSavingLevel] = useState(null)
  const [feedback, setFeedback] = useState(null)

  const load = useCallback(async () => {
    setLoadError('')
    try {
      const res = await adminAPI.getAttemptPolicies()
      const list = res.data.data || []
      setPolicies(list)
      setDrafts(Object.fromEntries(list.map((policy) => [policy.certificationLevel, policy.attemptsPerPayment])))
    } catch (err) {
      setLoadError(errorMessage(err, 'Failed to load attempt allowances'))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const handleSave = async (policy) => {
    const level = policy.certificationLevel
    setSavingLevel(level)
    try {
      const res = await adminAPI.updateAttemptPolicy(level, {
        attemptsPerPayment: drafts[level],
        version: policy.version
      })
      const saved = res.data.data
      setPolicies((prev) => prev.map((item) => (item.certificationLevel === level ? saved : item)))
      setDrafts((prev) => ({ ...prev, [level]: saved.attemptsPerPayment }))
      setFeedback({
        severity: 'success',
        msg: `${level} payments now cover ${plural(saved.attemptsPerPayment, 'attempt')}`
      })
    } catch (err) {
      // A 409 is another admin's save landing first; the useful next step is to see it.
      setFeedback({
        severity: 'error',
        msg: errorMessage(err, `Failed to save the ${level} allowance`),
        reload: err.response?.status === 409
      })
    } finally {
      setSavingLevel(null)
    }
  }

  let body
  if (loadError) {
    body = (
      <Alert
        severity="error"
        action={<Button color="inherit" size="small" onClick={load}>Retry</Button>}
      >
        {loadError}
      </Alert>
    )
  } else if (!policies) {
    body = (
      <Grid container spacing={2}>
        {[0, 1, 2].map((i) => (
          <Grid item xs={12} md={4} key={i}>
            <Skeleton variant="rounded" height={260} />
          </Grid>
        ))}
      </Grid>
    )
  } else {
    body = (
      <Grid container spacing={2}>
        {policies.map((policy) => (
          <Grid item xs={12} md={4} key={policy.certificationLevel}>
            <LevelCard
              policy={policy}
              draft={drafts[policy.certificationLevel]}
              onChange={(value) => setDrafts((prev) => ({ ...prev, [policy.certificationLevel]: value }))}
              onSave={() => handleSave(policy)}
              saving={savingLevel === policy.certificationLevel}
            />
          </Grid>
        ))}
      </Grid>
    )
  }

  return (
    <Box>
      <PageHeader
        title="Exam Attempts"
        subtitle="Choose how many attempts one exam payment covers at each certification level"
      />

      <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
        A candidate who fails or is terminated starts their next attempt without paying again, until the
        attempts on their payment are used up. Each attempt is drawn from questions the candidate has not seen
        before. A change applies to payments made after you save — candidates who have already paid keep the
        attempts they paid for.
      </Alert>

      {body}

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
                  load()
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

export default AdminExamAttemptsPage
