// ems_frontend/src/pages/admin/AdminDashboardPage.jsx
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, Grid, Paper, Skeleton, Stack, ToggleButton, ToggleButtonGroup, Typography
} from '@mui/material'
import PeopleIcon from '@mui/icons-material/PeopleAltRounded'
import QuizIcon from '@mui/icons-material/QuizRounded'
import WorkspacePremiumIcon from '@mui/icons-material/WorkspacePremiumRounded'
import ReportProblemIcon from '@mui/icons-material/ReportProblemRounded'
import PaidIcon from '@mui/icons-material/PaidRounded'
import HelpCenterIcon from '@mui/icons-material/HelpCenterRounded'
import VerifiedIcon from '@mui/icons-material/VerifiedRounded'
import { adminAPI } from '../../api/adminAPI'
import { getApiErrorMessage } from '../../utils/apiError'
import PageHeader from '../../components/common/PageHeader'
import StatCard from '../../components/common/StatCard'
import ChartCard from '../../components/dashboard/charts/ChartCard'
import ColumnChart from '../../components/dashboard/charts/ColumnChart'
import AreaChart from '../../components/dashboard/charts/AreaChart'
import FunnelChart from '../../components/dashboard/charts/FunnelChart'
import {
  SERIES, formatCount, formatCountCompact, formatMoney, formatMoneyCompact, monthLong, monthShort
} from '../../components/dashboard/charts/chartKit'
import { tokens, fonts, tierForLevel } from '../../styles/tokens'

const RANGES = [6, 12, 24, 36]

const toggleSx = {
  '& .MuiToggleButton-root': {
    px: 1.5,
    border: `1px solid ${tokens.line2}`,
    color: tokens.body,
    fontSize: 12,
    fontWeight: 600,
    textTransform: 'none',
    '&.Mui-selected': {
      background: 'rgba(192,138,46,.18)',
      color: tokens.copperLt,
      '&:hover': { background: 'rgba(192,138,46,.24)' },
    },
  },
}

const sectionLabelSx = {
  fontFamily: fonts.mono,
  fontSize: 10.5,
  letterSpacing: '1.8px',
  textTransform: 'uppercase',
  color: tokens.muted,
}

const browserTimeZone = () => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    return undefined
  }
}

const plural = (count, one, many = `${one}s`) => `${formatCount(count)} ${Number(count) === 1 ? one : many}`

const AdminDashboardPage = () => {
  const navigate = useNavigate()
  const [months, setMonths] = useState(12)
  const [analytics, setAnalytics] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let mounted = true
    setLoading(true)
    adminAPI
      .getAnalytics({ months, timeZone: browserTimeZone() })
      .then((res) => {
        if (!mounted) return
        setAnalytics(res.data?.data || null)
        setError('')
      })
      .catch((err) => {
        if (mounted) setError(getApiErrorMessage(err, 'Failed to load the dashboard figures'))
      })
      .finally(() => {
        if (mounted) setLoading(false)
      })
    return () => { mounted = false }
  }, [months, reloadKey])

  const firstLoad = loading && !analytics
  const refreshing = loading && Boolean(analytics)
  const totals = analytics?.totals
  const currency = analytics?.currency || 'INR'

  const series = useMemo(
    () => (analytics?.months || []).map((m, index) => ({
      ...m,
      label: monthShort(m.month, index === 0 || m.month.endsWith('-01')),
      fullLabel: monthLong(m.month),
    })),
    [analytics]
  )
  const current = series[series.length - 1]

  const money = (value) => formatMoney(value, currency)
  const moneyCompact = (value) => formatMoneyCompact(value, currency)

  const revenueData = series.map((m) => ({
    key: m.month,
    label: m.label,
    fullLabel: m.fullLabel,
    value: Number(m.revenue) || 0,
    detail: plural(m.paidTransactions, 'payment'),
  }))
  const candidateData = series.map((m) => ({
    key: m.month,
    label: m.label,
    fullLabel: m.fullLabel,
    value: m.totalCandidates,
    detail: `+${formatCount(m.newCandidates)} new that month`,
  }))
  const certificationData = series.map((m) => ({
    key: m.month,
    label: m.label,
    fullLabel: m.fullLabel,
    value: m.certificationsIssued,
    detail: m.attemptsScored ? `${m.attemptsPassed} of ${m.attemptsScored} attempts passed` : 'No attempts scored',
  }))

  const periodRevenue = revenueData.reduce((sum, point) => sum + point.value, 0)
  const periodNewCandidates = series.reduce((sum, m) => sum + m.newCandidates, 0)
  const periodCertifications = series.reduce((sum, m) => sum + m.certificationsIssued, 0)
  const rangeLabel = `the last ${months} months`

  const kpis = [
    {
      title: 'Revenue',
      value: money(totals?.revenue),
      icon: <PaidIcon />,
      tone: 'copper',
      trend: (
        <>
          {current ? `${money(current.revenue)} in ${current.fullLabel}` : null}
          {Number(totals?.nonLiveAmount) > 0 && (
            <Box component="span" sx={{ display: 'block', color: tokens.muted }}>
              Excludes {money(totals.nonLiveAmount)} in test or simulated payments
            </Box>
          )}
        </>
      ),
    },
    {
      title: 'Certified professionals',
      value: formatCount(totals?.certifiedCandidates),
      icon: <WorkspacePremiumIcon />,
      tone: 'green',
      trend: `${plural(totals?.certificationsIssued, 'certification')} issued · ${formatCount(totals?.activeCertifications)} active`,
    },
    {
      title: 'Registered candidates',
      value: formatCount(totals?.registeredCandidates),
      icon: <PeopleIcon />,
      tone: 'info',
      trend: current ? `+${formatCount(current.newCandidates)} in ${current.fullLabel}` : null,
    },
    {
      title: 'Pass rate',
      value: totals?.passRatePercentage != null ? `${totals.passRatePercentage}%` : '—',
      icon: <VerifiedIcon />,
      tone: 'neutral',
      trend: totals?.attemptsScored
        ? `${formatCount(totals.attemptsPassed)} of ${plural(totals.attemptsScored, 'scored attempt')}`
        : 'No attempts scored yet',
    },
  ]

  const platformTiles = [
    { title: 'Exams', value: formatCount(totals?.exams), icon: <QuizIcon />, tone: 'info' },
    { title: 'Questions', value: formatCount(totals?.questions), icon: <HelpCenterIcon />, tone: 'neutral' },
    { title: 'Violations', value: formatCount(totals?.violations), icon: <ReportProblemIcon />, tone: 'danger' },
  ]

  const funnel = analytics?.funnel
  const funnelSteps = funnel
    ? [
        { label: 'Registered', verb: 'registered', value: funnel.registered },
        { label: 'Applied for a level', verb: 'applied', value: funnel.applied },
        { label: 'Paid', verb: 'paid', value: funnel.paid },
        { label: 'Sat an exam', verb: 'sat an exam', value: funnel.sat },
        { label: 'Certified', verb: 'were certified', value: funnel.certified },
      ]
    : []

  const levelFooter = (
    <Stack
      direction="row"
      spacing={2.5}
      flexWrap="wrap"
      useFlexGap
      sx={{ pt: 1.5, borderTop: `1px solid ${tokens.line}` }}
    >
      {(analytics?.levels || []).map((level) => (
        <Box key={level.level} sx={{ display: 'flex', alignItems: 'center', gap: 0.85 }}>
          <Box
            aria-hidden="true"
            sx={{
              flex: 'none',
              width: 9,
              height: 9,
              borderRadius: '50%',
              background: tierForLevel(Number(String(level.level).replace(/\D/g, ''))).b,
            }}
          />
          <Typography sx={{ fontSize: 12, color: tokens.body }}>
            <Box component="span" sx={{ fontWeight: 700, color: tokens.ink }}>{level.level}</Box>
            {' '}{formatCount(level.issued)} issued · {formatCount(level.active)} active
          </Typography>
        </Box>
      ))}
    </Stack>
  )

  const chartSkeleton = <Skeleton variant="rounded" height={320} />

  return (
    <Box>
      <PageHeader
        title="Admin Dashboard"
        subtitle={
          analytics
            ? `Figures as of ${new Date(analytics.generatedAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })} · months in ${analytics.timeZone}`
            : 'Platform overview and board figures'
        }
      />

      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={<Button color="inherit" size="small" onClick={() => setReloadKey((k) => k + 1)}>Retry</Button>}
        >
          {error}
        </Alert>
      )}

      <Typography sx={{ ...sectionLabelSx, mb: 1.25 }}>All time</Typography>
      <Grid container spacing={2.5}>
        {kpis.map((card) => (
          <Grid item xs={12} sm={6} lg={3} key={card.title}>
            {firstLoad ? <Skeleton variant="rounded" height={118} /> : <StatCard {...card} />}
          </Grid>
        ))}
      </Grid>

      <Box
        sx={{ mt: 3.5, mb: 1.5, display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5 }}
      >
        <Typography sx={sectionLabelSx}>Trends · month by month</Typography>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={months}
          onChange={(event, next) => next && setMonths(next)}
          aria-label="Trend range"
          sx={toggleSx}
        >
          {RANGES.map((range) => (
            <ToggleButton key={range} value={range}>{range} months</ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Box>

      <Grid container spacing={2.5}>
        <Grid item xs={12} lg={6}>
          {firstLoad ? chartSkeleton : analytics && (
            <ChartCard
              title="Revenue"
              subtitle={`${money(periodRevenue)} in live payments over ${rangeLabel}`}
              busy={refreshing}
              table={{
                columns: [
                  { key: 'month', label: 'Month' },
                  { key: 'revenue', label: 'Revenue', align: 'right' },
                  { key: 'payments', label: 'Payments', align: 'right' },
                ],
                rows: series.map((m) => ({
                  key: m.month,
                  month: m.fullLabel,
                  revenue: money(m.revenue),
                  payments: formatCount(m.paidTransactions),
                })),
              }}
            >
              <ColumnChart
                data={revenueData}
                color={SERIES.revenue}
                formatValue={money}
                formatCompact={moneyCompact}
                formatAxis={moneyCompact}
                ariaLabel={`Revenue by month over ${rangeLabel}`}
              />
            </ChartCard>
          )}
        </Grid>

        <Grid item xs={12} lg={6}>
          {firstLoad ? chartSkeleton : analytics && (
            <ChartCard
              title="Registered candidates"
              subtitle={`${formatCount(totals?.registeredCandidates)} in total · +${formatCount(periodNewCandidates)} over ${rangeLabel}`}
              busy={refreshing}
              table={{
                columns: [
                  { key: 'month', label: 'Month' },
                  { key: 'added', label: 'New', align: 'right' },
                  { key: 'total', label: 'Total', align: 'right' },
                ],
                rows: series.map((m) => ({
                  key: m.month,
                  month: m.fullLabel,
                  added: formatCount(m.newCandidates),
                  total: formatCount(m.totalCandidates),
                })),
              }}
            >
              <AreaChart
                data={candidateData}
                color={SERIES.candidates}
                formatValue={(value) => plural(value, 'candidate')}
                formatCompact={formatCountCompact}
                formatAxis={formatCountCompact}
                integer
                ariaLabel={`Total registered candidates at the end of each month over ${rangeLabel}`}
              />
            </ChartCard>
          )}
        </Grid>

        <Grid item xs={12} lg={6}>
          {firstLoad ? chartSkeleton : analytics && (
            <ChartCard
              title="Certifications issued"
              subtitle={`${formatCount(periodCertifications)} over ${rangeLabel}`}
              busy={refreshing}
              footer={levelFooter}
              table={{
                columns: [
                  { key: 'month', label: 'Month' },
                  { key: 'issued', label: 'Issued', align: 'right' },
                  { key: 'scored', label: 'Attempts scored', align: 'right' },
                  { key: 'passed', label: 'Passed', align: 'right' },
                ],
                rows: series.map((m) => ({
                  key: m.month,
                  month: m.fullLabel,
                  issued: formatCount(m.certificationsIssued),
                  scored: formatCount(m.attemptsScored),
                  passed: formatCount(m.attemptsPassed),
                })),
              }}
            >
              <ColumnChart
                data={certificationData}
                color={SERIES.certified}
                formatValue={(value) => plural(value, 'certification')}
                formatCompact={formatCountCompact}
                formatAxis={formatCountCompact}
                integer
                ariaLabel={`Certifications issued by month over ${rangeLabel}`}
              />
            </ChartCard>
          )}
        </Grid>

        <Grid item xs={12} lg={6}>
          {firstLoad ? chartSkeleton : analytics && (
            <ChartCard
              title="Candidate journey"
              subtitle="Different candidates who reached each step, all time"
              busy={refreshing}
            >
              <FunnelChart steps={funnelSteps} color={SERIES.candidates} />
            </ChartCard>
          )}
        </Grid>
      </Grid>

      <Typography sx={{ ...sectionLabelSx, mt: 3.5, mb: 1.25 }}>Platform</Typography>
      <Grid container spacing={2.5}>
        {platformTiles.map((card) => (
          <Grid item xs={12} sm={4} key={card.title}>
            {firstLoad ? <Skeleton variant="rounded" height={104} /> : <StatCard {...card} />}
          </Grid>
        ))}
        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" fontWeight={700} gutterBottom>Management</Typography>
            <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
              <Button variant="outlined" onClick={() => navigate('/admin/exam-tracker')}>Track exams</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/users')}>Manage users</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/exams')}>Manage exams</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/questions')}>Manage questions</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/payments')}>Review payments</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/violations')}>Review violations</Button>
              <Button variant="outlined" onClick={() => navigate('/admin/reports')}>Generate reports</Button>
            </Stack>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  )
}

export default AdminDashboardPage
