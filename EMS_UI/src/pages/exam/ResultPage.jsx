// ems_frontend/src/pages/exam/ResultPage.jsx
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Box, Paper, Grid, Typography, Button, Alert, Stack,
  Divider, CircularProgress, LinearProgress
} from '@mui/material'
import { examAPI } from '../../api/examAPI'
import { certificateAPI } from '../../api/certificateAPI'
import PageHeader from '../../components/common/PageHeader'
import StatCard from '../../components/common/StatCard'
import CheckCircleIcon from '@mui/icons-material/CheckCircleRounded'
import CancelIcon from '@mui/icons-material/CancelRounded'
import WorkspacePremiumIcon from '@mui/icons-material/WorkspacePremiumRounded'
import QuizIcon from '@mui/icons-material/QuizRounded'
import DoneAllIcon from '@mui/icons-material/DoneAllRounded'
import CloseIcon from '@mui/icons-material/CloseRounded'

const Row = ({ label, value }) => (
  <Stack direction="row" justifyContent="space-between">
    <Typography color="text.secondary">{label}</Typography>
    <Typography fontWeight={600}>{value}</Typography>
  </Stack>
)

const ResultPage = () => {
  const navigate = useNavigate()
  const { sessionId } = useParams()
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [generating, setGenerating] = useState(false)
  const [certError, setCertError] = useState('')
  const [reApplying, setReApplying] = useState(false)
  const [reApplyError, setReApplyError] = useState('')

  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const res = await examAPI.getExamResult(sessionId)
        if (mounted) setResult(res.data.data)
      } catch (err) {
        if (mounted) setError(err.response?.data?.message || 'Failed to load result')
      } finally {
        if (mounted) setLoading(false)
      }
    })()
    return () => { mounted = false }
  }, [sessionId])

  const handleGenerateCertificate = async () => {
    setGenerating(true)
    setCertError('')
    try {
      await certificateAPI.generateCertificate(sessionId)
      navigate('/certificates')
    } catch (err) {
      setCertError(err.response?.data?.message || 'Failed to generate certificate')
    } finally {
      setGenerating(false)
    }
  }

  const handleReApply = async () => {
    if (!result?.applicationId) {
      navigate('/exams')
      return
    }
    setReApplying(true)
    setReApplyError('')
    try {
      const res = await examAPI.reApply(result.applicationId)
      const created = res.data.data
      // A retake the payment still covers is already paid for, so it goes
      // straight to booking a slot; anything else starts at the payment step.
      navigate(created?.paymentStatus === 'SUCCESS'
        ? `/exam/schedule/${created.applicationId}`
        : `/exam/payment/${created?.applicationId}`)
    } catch (err) {
      setReApplyError(err.response?.data?.message || 'Failed to re-apply. Please try again.')
      setReApplying(false)
    }
  }

  if (loading) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: 300 }}>
        <CircularProgress />
      </Box>
    )
  }

  if (error || !result) {
    return (
      <Box>
        <PageHeader title="Exam Result" />
        <Alert severity="error">{error || 'Result not available.'}</Alert>
      </Box>
    )
  }

  const passed = result.resultStatus === 'PASS'
  const percentage = Number(result.percentage || 0)

  return (
    <Box>
      <PageHeader title="Exam Result" subtitle={`Exam ${result.examCode}`} />

      <Grid container spacing={3}>
        <Grid item xs={12}>
          <Paper
            sx={{
              p: 4,
              textAlign: 'center',
              background: passed
                ? 'linear-gradient(150deg, rgba(14,77,60,.65), rgba(4,20,14,.95))'
                : 'linear-gradient(150deg, rgba(120,26,26,.45), rgba(20,6,6,.95))',
              border: `1px solid ${passed ? 'rgba(63,211,160,.34)' : 'rgba(248,113,113,.34)'}`
            }}
          >
            {passed ? (
              <CheckCircleIcon sx={{ fontSize: 72, color: 'success.main' }} />
            ) : (
              <CancelIcon sx={{ fontSize: 72, color: 'error.main' }} />
            )}
            <Typography variant="h4" fontWeight={800} sx={{ mt: 1 }}>
              {passed ? 'Congratulations, you passed!' : 'You did not pass'}
            </Typography>
            <Typography variant="h6" color="text.secondary">
              Score: {percentage}%
            </Typography>
            <Box sx={{ maxWidth: 400, mx: 'auto', mt: 2 }}>
              <LinearProgress
                variant="determinate"
                value={Math.min(percentage, 100)}
                color={passed ? 'success' : 'error'}
                sx={{ height: 10, borderRadius: 5 }}
              />
            </Box>
          </Paper>
        </Grid>

        <Grid item xs={6} md={3}>
          <StatCard title="Total Questions" value={result.totalQuestions} icon={<QuizIcon />} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            title="Attempted" value={result.attemptedQuestions}
            icon={<DoneAllIcon />} tone="info"
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            title="Correct" value={result.correctAnswers}
            icon={<CheckCircleIcon />} tone="green"
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard
            title="Wrong" value={result.wrongAnswers}
            icon={<CloseIcon />} tone="danger"
          />
        </Grid>

        <Grid item xs={12} md={7}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" fontWeight={700} gutterBottom>Score breakdown</Typography>
            <Stack spacing={1.5} sx={{ mt: 1 }}>
              <Row label="Obtained marks" value={`${result.obtainedMarks} / ${result.totalMarks}`} />
              <Divider />
              <Row label="Percentage" value={`${percentage}%`} />
              <Divider />
              <Row label="Result" value={result.resultStatus} />
              <Divider />
              <Row label="Submitted at" value={result.submittedAt ? new Date(result.submittedAt).toLocaleString() : '-'} />
            </Stack>
          </Paper>
        </Grid>

        <Grid item xs={12} md={5}>
          <Paper sx={{ p: 3, height: '100%' }}>
            <Typography variant="h6" fontWeight={700} gutterBottom>Next steps</Typography>
            {certError && <Alert severity="error" sx={{ mb: 2 }}>{certError}</Alert>}

            {passed ? (
              <Stack spacing={1.5}>
                <Typography variant="body2" color="text.secondary">
                  You are eligible for a certificate. Generate it now to add it to your profile.
                </Typography>
                <Button
                  variant="contained"
                  startIcon={generating ? <CircularProgress size={18} color="inherit" /> : <WorkspacePremiumIcon />}
                  disabled={generating}
                  onClick={handleGenerateCertificate}
                >
                  {generating ? 'Generating…' : 'Generate certificate'}
                </Button>
                <Button variant="outlined" onClick={() => navigate('/certificates')}>
                  View my certificates
                </Button>
              </Stack>
            ) : (
              <Stack spacing={1.5}>
                {reApplyError && <Alert severity="error">{reApplyError}</Alert>}
                <Typography variant="body2" color="text.secondary">
                  {result.retakeAvailable
                    ? `Your payment covers ${result.attemptsRemaining} more attempt${result.attemptsRemaining === 1 ? '' : 's'}, so you can take the exam again without paying. Your next attempt will have different questions.`
                    : "Don't worry – you can re-apply and take the exam again."}
                </Typography>
                <Button
                  variant="contained" color="warning"
                  disabled={reApplying}
                  startIcon={reApplying ? <CircularProgress size={18} color="inherit" /> : null}
                  onClick={handleReApply}
                >
                  {reApplying && 'Applying…'}
                  {!reApplying && (result.retakeAvailable
                    ? `Start attempt ${result.attemptNumber + 1} of ${result.attemptsAllowed}`
                    : 'Re-apply for exam')}
                </Button>
                <Button variant="outlined" onClick={() => navigate('/exams')}>
                  View all applications
                </Button>
              </Stack>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  )
}

export default ResultPage
