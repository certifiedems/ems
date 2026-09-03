// ems_frontend/src/pages/admin/AdminExamsPage.jsx
import { useEffect, useState, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, Button, Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, MenuItem, Grid, Skeleton, Snackbar, Alert, Stack, Chip,
  Typography, Divider, InputAdornment
} from '@mui/material'
import { useForm } from 'react-hook-form'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import StatusChip from '../../components/common/StatusChip'
import EmptyState from '../../components/common/EmptyState'
import { fonts, tone } from '../../styles/tokens'
import {
  SEVERITIES, SEVERITY_LABELS, PERCENTAGE_FIELD, DEFAULT_BLUEPRINT,
  severityCounts, percentageTotal, isMixBalanced
} from '../../utils/examBlueprint'
import AddIcon from '@mui/icons-material/AddRounded'
import PublishIcon from '@mui/icons-material/PublishRounded'
import EditIcon from '@mui/icons-material/EditRounded'

const LEVELS = ['L1', 'L2', 'L3']

const SEVERITY_TONE = { LOW: 'green', MEDIUM: 'copper', HIGH: 'danger' }

const emptyForm = {
  examCode: '',
  examName: '',
  certificationLevel: 'L1',
  durationMinutes: 60,
  totalMarks: 30,
  passingPercentage: 60,
  ...DEFAULT_BLUEPRINT,
}

/** Compact "6 Low · 12 Med · 12 High" breakdown used in the table. */
const SeverityBreakdown = ({ counts }) => (
  <Stack direction="row" spacing={0.5}>
    {SEVERITIES.map((severity) => {
      const t = tone[SEVERITY_TONE[severity]]
      return (
        <Chip
          key={severity}
          size="small"
          variant="outlined"
          label={`${counts[severity]} ${SEVERITY_LABELS[severity]}`}
          sx={{
            fontFamily: fonts.mono,
            fontSize: '0.66rem',
            height: 22,
            color: t.fg,
            background: t.bg,
            borderColor: t.border,
          }}
        />
      )
    })}
  </Stack>
)

const AdminExamsPage = () => {
  const [exams, setExams] = useState([])
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const { register, handleSubmit, reset, watch, formState: { errors } } = useForm({ defaultValues: emptyForm })

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminAPI.getAllExams()
      setExams(res.data.data || [])
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to load exams' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /*
   * Watched rather than read on submit: the whole point of the blueprint
   * section is that the admin sees the paper their percentages describe while
   * they are typing them, not after they have saved something they did not
   * mean.
   */
  const draft = watch()
  const draftCounts = severityCounts(draft)
  const draftTotalPercentage = percentageTotal(draft)
  const mixBalanced = isMixBalanced(draft)

  const openCreate = () => {
    setEditing(null)
    reset(emptyForm)
    setOpen(true)
  }

  const openEdit = (exam) => {
    setEditing(exam)
    reset({
      examCode: exam.examCode,
      examName: exam.examName,
      certificationLevel: exam.certificationLevel,
      durationMinutes: exam.durationMinutes,
      totalMarks: Number(exam.totalMarks),
      passingPercentage: Number(exam.passingPercentage),
      totalQuestions: exam.totalQuestions ?? DEFAULT_BLUEPRINT.totalQuestions,
      lowSeverityPercentage: Number(exam.lowSeverityPercentage ?? DEFAULT_BLUEPRINT.lowSeverityPercentage),
      mediumSeverityPercentage: Number(exam.mediumSeverityPercentage ?? DEFAULT_BLUEPRINT.mediumSeverityPercentage),
      highSeverityPercentage: Number(exam.highSeverityPercentage ?? DEFAULT_BLUEPRINT.highSeverityPercentage),
    })
    setOpen(true)
  }

  const onSave = async (data) => {
    if (!isMixBalanced(data)) {
      setFeedback({ severity: 'error', msg: 'Severity percentages must add up to 100%' })
      return
    }

    setSaving(true)
    const payload = {
      examCode: data.examCode,
      examName: data.examName,
      certificationLevel: data.certificationLevel,
      durationMinutes: Number(data.durationMinutes),
      totalMarks: Number(data.totalMarks),
      passingPercentage: Number(data.passingPercentage),
      totalQuestions: Number(data.totalQuestions),
      lowSeverityPercentage: Number(data.lowSeverityPercentage),
      mediumSeverityPercentage: Number(data.mediumSeverityPercentage),
      highSeverityPercentage: Number(data.highSeverityPercentage),
    }

    try {
      if (editing) {
        await adminAPI.updateExam(editing.id, payload)
        setFeedback({ severity: 'success', msg: 'Exam updated' })
      } else {
        await adminAPI.createExam(payload)
        setFeedback({ severity: 'success', msg: 'Exam created' })
      }
      setOpen(false)
      setEditing(null)
      reset(emptyForm)
      load()
    } catch (err) {
      setFeedback({
        severity: 'error',
        msg: err.response?.data?.message || `Failed to ${editing ? 'update' : 'create'} exam`,
      })
    } finally {
      setSaving(false)
    }
  }

  const publish = async (examId) => {
    try {
      await adminAPI.publishExam(examId)
      setFeedback({ severity: 'success', msg: 'Exam published' })
      load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to publish' })
    }
  }

  const tableContent = (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Code</TableCell>
            <TableCell>Name</TableCell>
            <TableCell>Level</TableCell>
            <TableCell>Duration</TableCell>
            <TableCell>Question paper</TableCell>
            <TableCell>Pass %</TableCell>
            <TableCell>Status</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {exams.map((ex) => {
            /*
             * The counts come from the server, which derived them from the same
             * percentages it will use to draw the paper. Recomputing them here
             * would risk showing a split the exam does not actually have.
             */
            const counts = {
              LOW: ex.lowSeverityQuestions ?? 0,
              MEDIUM: ex.mediumSeverityQuestions ?? 0,
              HIGH: ex.highSeverityQuestions ?? 0,
            }
            return (
              <TableRow key={ex.id} hover>
                <TableCell>{ex.examCode}</TableCell>
                <TableCell>{ex.examName}</TableCell>
                <TableCell>{ex.certificationLevel}</TableCell>
                <TableCell>{ex.durationMinutes} min</TableCell>
                <TableCell>
                  <Stack spacing={0.75}>
                    <Typography variant="body2" sx={{ fontFamily: fonts.mono }}>
                      {ex.totalQuestions ?? '—'} questions
                    </Typography>
                    <SeverityBreakdown counts={counts} />
                  </Stack>
                </TableCell>
                <TableCell>{String(ex.passingPercentage)}%</TableCell>
                <TableCell><StatusChip status={ex.published ? 'PUBLISHED' : ex.examStatus} /></TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} justifyContent="flex-end">
                    <Button
                      size="small"
                      variant="text"
                      startIcon={<EditIcon />}
                      onClick={() => openEdit(ex)}
                    >
                      Edit
                    </Button>
                    <Button
                      size="small"
                      variant="outlined"
                      startIcon={<PublishIcon />}
                      disabled={ex.published}
                      onClick={() => publish(ex.id)}
                    >
                      {ex.published ? 'Published' : 'Publish'}
                    </Button>
                  </Stack>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </TableContainer>
  )

  let paperContent
  if (loading) {
    paperContent = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4].map((i) => <Skeleton key={i} height={52} />)}
      </Box>
    )
  } else if (exams.length === 0) {
    paperContent = <EmptyState title="No exams yet" description="Create your first exam to get started." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader
        title="Exam Management"
        subtitle="Create, publish and manage examinations"
        action={
          <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
            New exam
          </Button>
        }
      />

      <Paper>
        {paperContent}
      </Paper>

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? `Edit ${editing.examCode}` : 'New Exam'}</DialogTitle>
        <form onSubmit={handleSubmit(onSave)}>
          <DialogContent dividers>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth label="Exam Code"
                  {...register('examCode', { required: 'Required' })}
                  error={!!errors.examCode} helperText={errors.examCode?.message}
                />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth select label="Level"
                  /*
                   * Controlled off the watched value rather than left to the
                   * select's own state: MUI's Select keeps its displayed option
                   * internally when uncontrolled, so opening this dialog to edit
                   * an L2 exam would show "L1" over a form value of L2 and save
                   * whichever one the admin did not look at.
                   */
                  value={draft.certificationLevel ?? emptyForm.certificationLevel}
                  {...register('certificationLevel', { required: true })}
                >
                  {LEVELS.map((l) => <MenuItem key={l} value={l}>{l}</MenuItem>)}
                </TextField>
              </Grid>
              <Grid item xs={12}>
                <TextField
                  fullWidth label="Exam Name"
                  {...register('examName', { required: 'Required' })}
                  error={!!errors.examName} helperText={errors.examName?.message}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth type="number" label="Duration (min)"
                  {...register('durationMinutes', {
                    required: 'Required',
                    min: { value: 1, message: 'Min 1' },
                  })}
                  error={!!errors.durationMinutes} helperText={errors.durationMinutes?.message}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth type="number" label="Total Marks"
                  {...register('totalMarks', {
                    required: 'Required',
                    min: { value: 0, message: 'Min 0' },
                  })}
                  error={!!errors.totalMarks} helperText={errors.totalMarks?.message}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth type="number" label="Passing %"
                  InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }}
                  {...register('passingPercentage', {
                    required: 'Required',
                    min: { value: 0, message: '0–100' },
                    max: { value: 100, message: '0–100' },
                  })}
                  error={!!errors.passingPercentage} helperText={errors.passingPercentage?.message}
                />
              </Grid>

              <Grid item xs={12}>
                <Divider sx={{ mt: 1 }} />
              </Grid>

              <Grid item xs={12}>
                <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                  Question paper
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  How many questions each attempt draws, and how they split across severities.
                  The three shares must add up to 100%.
                </Typography>
              </Grid>

              <Grid item xs={12} sm={3}>
                <TextField
                  fullWidth type="number" label="Questions"
                  {...register('totalQuestions', {
                    required: 'Required',
                    min: { value: 1, message: 'Min 1' },
                  })}
                  error={!!errors.totalQuestions} helperText={errors.totalQuestions?.message}
                />
              </Grid>

              {SEVERITIES.map((severity) => (
                <Grid item xs={12} sm={3} key={severity}>
                  <TextField
                    fullWidth type="number" label={`${SEVERITY_LABELS[severity]} %`}
                    InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }}
                    {...register(PERCENTAGE_FIELD[severity], {
                      required: 'Required',
                      min: { value: 0, message: '0–100' },
                      max: { value: 100, message: '0–100' },
                    })}
                    error={!!errors[PERCENTAGE_FIELD[severity]]}
                    helperText={
                      errors[PERCENTAGE_FIELD[severity]]?.message
                      || `${draftCounts[severity]} question${draftCounts[severity] === 1 ? '' : 's'}`
                    }
                  />
                </Grid>
              ))}

              <Grid item xs={12}>
                {mixBalanced ? (
                  <Alert severity="success" variant="outlined" sx={{ py: 0.25 }}>
                    {`Each attempt draws ${draft.totalQuestions || 0} questions — `}
                    {SEVERITIES.map((s) => `${draftCounts[s]} ${SEVERITY_LABELS[s].toLowerCase()}`).join(', ')}
                    {`. Candidates pass at ${draft.passingPercentage || 0}%.`}
                  </Alert>
                ) : (
                  <Alert severity="error" variant="outlined" sx={{ py: 0.25 }}>
                    {`Severity percentages add up to ${Number(draftTotalPercentage.toFixed(2))}% — they must total 100%.`}
                  </Alert>
                )}
              </Grid>
            </Grid>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={saving || !mixBalanced}>
              {saving ? 'Saving…' : (editing ? 'Save changes' : 'Create')}
            </Button>
          </DialogActions>
        </form>
      </Dialog>

      <Snackbar
        open={Boolean(feedback)}
        autoHideDuration={4000}
        onClose={() => setFeedback(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {feedback && (
          <Alert severity={feedback.severity} onClose={() => setFeedback(null)}>
            {feedback.msg}
          </Alert>
        )}
      </Snackbar>
    </Box>
  )
}

export default AdminExamsPage
