// ems_frontend/src/pages/admin/AdminAuditLogPage.jsx
import { useEffect, useState, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TextField, MenuItem, Grid, Chip, Skeleton, Snackbar, Alert, Button
} from '@mui/material'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'

const EVENT_TYPES = [
  'REGISTRATION', 'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT',
  'PASSWORD_RESET', 'PASSWORD_CHANGE', 'PAYMENT', 'EXAM_START', 'EXAM_END',
  'EXAM_INVALIDATED', 'CERTIFICATE_DOWNLOAD', 'CERTIFICATE_MAINTENANCE', 'ADMIN_ACTION'
]
const OUTCOMES = ['SUCCESS', 'FAILURE']

const AdminAuditLogPage = () => {
  const [logs, setLogs] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filters, setFilters] = useState({ eventType: '', outcome: '', actor: '', targetUserId: '' })

  const load = useCallback(async (activeFilters) => {
    setLoading(true)
    try {
      const params = {}
      if (activeFilters.eventType) params.eventType = activeFilters.eventType
      if (activeFilters.outcome) params.outcome = activeFilters.outcome
      if (activeFilters.actor) params.actor = activeFilters.actor
      if (activeFilters.targetUserId) params.targetUserId = activeFilters.targetUserId
      const res = await adminAPI.getAuditLogs(params)
      setLogs(res.data.data || [])
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to load audit logs')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load(filters)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const setField = (field, value) => setFilters((f) => ({ ...f, [field]: value }))

  const tableContent = (
    <TableContainer sx={{ overflowX: 'auto' }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>When</TableCell>
            <TableCell>Event</TableCell>
            <TableCell>Outcome</TableCell>
            <TableCell>Actor</TableCell>
            <TableCell>Target User</TableCell>
            <TableCell>Target</TableCell>
            <TableCell>Description</TableCell>
            <TableCell>IP</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {logs.map((l) => (
            <TableRow key={l.id} hover>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>
                {l.occurredAt ? new Date(l.occurredAt).toLocaleString() : '–'}
              </TableCell>
              <TableCell>
                <Chip size="small" variant="outlined" label={String(l.eventType).replace(/_/g, ' ')} />
              </TableCell>
              <TableCell>
                <Chip
                  size="small"
                  color={l.outcome === 'SUCCESS' ? 'success' : 'error'}
                  label={l.outcome}
                />
              </TableCell>
              <TableCell>
                <Box>
                  <Box sx={{ fontWeight: 600 }}>{l.actorUserId || 'SYSTEM'}</Box>
                  <Box sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>{l.actorEmail || '–'}</Box>
                </Box>
              </TableCell>
              <TableCell>{l.targetUserId || '–'}</TableCell>
              <TableCell>
                {l.targetType ? `${l.targetType}${l.targetId ? ' #' + l.targetId : ''}` : '–'}
              </TableCell>
              <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {l.description || '–'}
              </TableCell>
              <TableCell>{l.ipAddress || '–'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )

  let paperContent
  if (loading) {
    paperContent = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} height={44} />)}
      </Box>
    )
  } else if (logs.length === 0) {
    paperContent = <EmptyState title="No audit events found" description="Try widening or clearing the filters." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader
        title="Audit Trail"
        subtitle="Who did what, to whom, and whether it succeeded — logins, password resets, payments, exam lifecycle and admin actions"
      />

      <Paper sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2} alignItems="center">
          <Grid item xs={12} sm={3}>
            <TextField
              fullWidth size="small" select label="Event type"
              value={filters.eventType} onChange={(e) => setField('eventType', e.target.value)}
            >
              <MenuItem value="">All</MenuItem>
              {EVENT_TYPES.map((t) => <MenuItem key={t} value={t}>{t.replace(/_/g, ' ')}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={2}>
            <TextField
              fullWidth size="small" select label="Outcome"
              value={filters.outcome} onChange={(e) => setField('outcome', e.target.value)}
            >
              <MenuItem value="">All</MenuItem>
              {OUTCOMES.map((o) => <MenuItem key={o} value={o}>{o}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={3}>
            <TextField
              fullWidth size="small" label="Actor (email or user ID)"
              value={filters.actor} onChange={(e) => setField('actor', e.target.value)}
            />
          </Grid>
          <Grid item xs={12} sm={2}>
            <TextField
              fullWidth size="small" label="Target user ID"
              value={filters.targetUserId} onChange={(e) => setField('targetUserId', e.target.value)}
            />
          </Grid>
          <Grid item xs={12} sm={2}>
            <Button fullWidth variant="contained" onClick={() => load(filters)}>Apply</Button>
          </Grid>
        </Grid>
      </Paper>

      <Paper>
        {paperContent}
      </Paper>

      <Snackbar
        open={Boolean(error)}
        autoHideDuration={4000}
        onClose={() => setError('')}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="error" onClose={() => setError('')}>{error}</Alert>
      </Snackbar>
    </Box>
  )
}

export default AdminAuditLogPage
