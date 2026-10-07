// ems_frontend/src/pages/admin/AdminViolationsPage.jsx
import { useEffect, useMemo, useState } from 'react'
import { useSelector } from 'react-redux'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination,
  TableRow, TableSortLabel, Skeleton, Chip, Snackbar, Alert, Grid, TextField, Button,
  Menu, MenuItem, ListItemIcon, ListItemText, Typography
} from '@mui/material'
import DownloadIcon from '@mui/icons-material/DownloadRounded'
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdfRounded'
import TableChartIcon from '@mui/icons-material/TableChartRounded'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'
import PcbSelect from '../../components/common/PcbSelect'
import PcbDateField, { parseFieldValue } from '../../components/common/PcbDateField'
import { downloadViolationCsv, downloadViolationPdf, humanize } from '../../utils/violationReport'

const EMPTY_FILTERS = {
  search: '', violationType: '', certificationLevel: '', sessionStatus: '', actionTaken: '', from: '', to: ''
}

const time = (value) => (value ? new Date(value).getTime() : null)

/*
 * Sortable columns. `value` is what the comparator sees; `asc`/`desc` are how
 * the order reads on the exported report.
 */
const SORTABLE = {
  candidate: { label: 'Candidate', value: (v) => v.candidateName },
  sessionId: { label: 'Session', value: (v) => v.sessionId },
  applicationId: { label: 'Application', value: (v) => v.applicationId },
  exam: { label: 'Exam', value: (v) => v.examCode },
  level: { label: 'Level', value: (v) => v.certificationLevel },
  sessionStatus: { label: 'Session status', value: (v) => v.sessionStatus },
  violationType: { label: 'Type', value: (v) => v.violationType },
  violationLevel: { label: 'Violation level', value: (v) => v.violationLevel },
  actionTaken: { label: 'Action', value: (v) => v.actionTaken },
  detectedAt: { label: 'Detected', value: (v) => time(v.detectedAt), asc: 'oldest first', desc: 'newest first' },
  examTerminated: { label: 'Terminated', value: (v) => (v.examTerminated ? 1 : 0), asc: 'no first', desc: 'yes first' }
}

const DEFAULT_SORT = { orderBy: 'detectedAt', order: 'desc' }

const compareValues = (a, b) => {
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}

// Blanks stay at the bottom in either direction; ties fall back to newest first.
const comparatorFor = ({ orderBy, order }) => {
  const pick = SORTABLE[orderBy].value
  const direction = order === 'asc' ? 1 : -1
  return (a, b) => {
    const left = pick(a)
    const right = pick(b)
    const leftBlank = left == null || left === ''
    const rightBlank = right == null || right === ''
    if (leftBlank !== rightBlank) return leftBlank ? 1 : -1
    const result = leftBlank ? 0 : compareValues(left, right) * direction
    return result || (time(b.detectedAt) ?? 0) - (time(a.detectedAt) ?? 0) || b.violationId - a.violationId
  }
}

const sortLabelOf = (sort) => {
  const column = SORTABLE[sort.orderBy]
  const direction = column[sort.order] || (sort.order === 'asc' ? 'ascending' : 'descending')
  return `${column.label} (${direction})`
}

/** Distinct values present in the data, so the filters never offer an empty choice. */
const optionsFrom = (violations, pick, format = (value) => value) => [
  { value: '', label: 'All' },
  ...[...new Set(violations.map(pick).filter(Boolean))]
    .sort((a, b) => String(a).localeCompare(String(b)))
    .map((value) => ({ value, label: format(value) }))
]

const formatDay = (raw) => parseFieldValue(raw)?.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })

const describeFilters = (filters) => [
  filters.search.trim() && `Search: "${filters.search.trim()}"`,
  filters.violationType && `Type: ${humanize(filters.violationType)}`,
  filters.certificationLevel && `Level: ${filters.certificationLevel}`,
  filters.sessionStatus && `Session status: ${humanize(filters.sessionStatus)}`,
  filters.actionTaken && `Action: ${humanize(filters.actionTaken)}`,
  filters.from && `Detected from: ${formatDay(filters.from)}`,
  filters.to && `Detected to: ${formatDay(filters.to)}`
].filter(Boolean)

const preparedByOf = (user) => {
  if (!user) return 'Administrator'
  const name = [user.firstName, user.lastName].filter(Boolean).join(' ').trim()
  const handle = user.userId || user.email
  if (name && handle) return `${name} (${handle})`
  return name || handle || 'Administrator'
}

const secondaryText = { color: 'text.secondary', fontSize: '0.75rem' }

const AdminViolationsPage = () => {
  const user = useSelector((state) => state.auth.user)
  const [violations, setViolations] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [sort, setSort] = useState(DEFAULT_SORT)
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)
  const [exportAnchor, setExportAnchor] = useState(null)

  const sessionStatusColor = (status) => {
    if (status === 'COMPLETED') return 'success'
    if (status === 'TERMINATED') return 'error'
    if (status === 'IN_PROGRESS') return 'warning'
    return 'default'
  }

  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const res = await adminAPI.getAllViolations()
        if (mounted) setViolations(res.data.data || [])
      } catch (err) {
        if (mounted) setError(err.response?.data?.message || 'Failed to load violations')
      } finally {
        if (mounted) setLoading(false)
      }
    })()
    return () => { mounted = false }
  }, [])

  const filterOptions = useMemo(() => ({
    violationType: optionsFrom(violations, (v) => v.violationType, humanize),
    certificationLevel: optionsFrom(violations, (v) => v.certificationLevel),
    sessionStatus: optionsFrom(violations, (v) => v.sessionStatus, humanize),
    actionTaken: optionsFrom(violations, (v) => v.actionTaken, humanize)
  }), [violations])

  // Filtered and sorted across every page; the exports use this same list.
  const rows = useMemo(() => {
    const needle = filters.search.trim().toLowerCase()
    const from = parseFieldValue(filters.from)
    const to = parseFieldValue(filters.to)
    // `to` is a calendar day, so it runs up to the start of the next one.
    const toExclusive = to && new Date(to.getFullYear(), to.getMonth(), to.getDate() + 1).getTime()

    const filtered = violations.filter((v) => {
      if (filters.violationType && v.violationType !== filters.violationType) return false
      if (filters.certificationLevel && v.certificationLevel !== filters.certificationLevel) return false
      if (filters.sessionStatus && v.sessionStatus !== filters.sessionStatus) return false
      if (filters.actionTaken && v.actionTaken !== filters.actionTaken) return false
      const detected = time(v.detectedAt)
      if (from && (detected == null || detected < from.getTime())) return false
      if (toExclusive && (detected == null || detected >= toExclusive)) return false
      if (!needle) return true
      return [
        v.candidateName,
        v.userId,
        v.candidateEmail,
        v.examCode,
        v.examName,
        v.description,
        humanize(v.violationType),
        `#${v.sessionId}`,
        v.applicationId && `#${v.applicationId}`
      ].some((value) => value && String(value).toLowerCase().includes(needle))
    })
    return filtered.sort(comparatorFor(sort))
  }, [violations, filters, sort])

  const pageCount = Math.max(1, Math.ceil(rows.length / rowsPerPage))
  const currentPage = Math.min(page, pageCount - 1)
  const pageRows = rows.slice(currentPage * rowsPerPage, currentPage * rowsPerPage + rowsPerPage)
  const hasFilters = Object.values(filters).some(Boolean)

  const setFilter = (field, value) => {
    setFilters((prev) => ({ ...prev, [field]: value }))
    setPage(0)
  }

  const handleSort = (orderBy) => {
    setSort((prev) => ({
      orderBy,
      // A fresh column starts newest/highest first for dates and numbers.
      order: prev.orderBy === orderBy ? (prev.order === 'asc' ? 'desc' : 'asc') : (orderBy === 'detectedAt' ? 'desc' : 'asc')
    }))
    setPage(0)
  }

  const handleExport = (format) => {
    setExportAnchor(null)
    try {
      if (format === 'PDF') {
        downloadViolationPdf(rows, {
          preparedBy: preparedByOf(user),
          filters: describeFilters(filters),
          sortLabel: sortLabelOf(sort),
          totalCount: violations.length
        })
      } else {
        downloadViolationCsv(rows)
      }
    } catch {
      setError('Could not generate the violation report')
    }
  }

  const sortableHead = (key, label) => (
    <TableCell sortDirection={sort.orderBy === key ? sort.order : false} sx={{ whiteSpace: 'nowrap' }}>
      <TableSortLabel
        active={sort.orderBy === key}
        direction={sort.orderBy === key ? sort.order : 'asc'}
        onClick={() => handleSort(key)}
      >
        {label}
      </TableSortLabel>
    </TableCell>
  )

  const tableContent = (
    <>
      <TableContainer sx={{ overflowX: 'auto' }}>
        <Table>
          <TableHead>
            <TableRow>
              {sortableHead('candidate', 'Candidate')}
              {sortableHead('sessionId', 'Session')}
              {sortableHead('applicationId', 'Application')}
              {sortableHead('exam', 'Exam')}
              {sortableHead('level', 'Level')}
              {sortableHead('sessionStatus', 'Session Status')}
              {sortableHead('violationType', 'Type')}
              {sortableHead('violationLevel', 'Violation Level')}
              <TableCell>Description</TableCell>
              {sortableHead('actionTaken', 'Action')}
              <TableCell>Policy</TableCell>
              {sortableHead('detectedAt', 'Detected')}
              {sortableHead('examTerminated', 'Terminated')}
            </TableRow>
          </TableHead>
          <TableBody>
            {pageRows.map((v) => (
              <TableRow key={v.violationId} hover>
                <TableCell>
                  <Box>
                    <Box sx={{ fontWeight: 600 }}>{v.candidateName || '–'}</Box>
                    <Box sx={secondaryText}>{v.userId || '–'}</Box>
                    <Box sx={secondaryText}>{v.candidateEmail || '–'}</Box>
                  </Box>
                </TableCell>
                <TableCell>#{v.sessionId}</TableCell>
                <TableCell>{v.applicationId ? `#${v.applicationId}` : '–'}</TableCell>
                <TableCell>
                  <Box>
                    <Box sx={{ fontWeight: 600 }}>{v.examCode || '–'}</Box>
                    <Box sx={secondaryText}>{v.examName || '–'}</Box>
                  </Box>
                </TableCell>
                <TableCell>{v.certificationLevel || '–'}</TableCell>
                <TableCell>
                  <Chip size="small" color={sessionStatusColor(v.sessionStatus)} label={v.sessionStatus || 'UNKNOWN'} />
                </TableCell>
                <TableCell>
                  <Chip size="small" color="warning" variant="outlined" label={humanize(v.violationType)} />
                </TableCell>
                <TableCell>{v.violationLevel}</TableCell>
                <TableCell sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {v.description || '–'}
                </TableCell>
                <TableCell>{v.actionTaken || '–'}</TableCell>
                <TableCell sx={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {v.policyMessage || '–'}
                </TableCell>
                <TableCell>{v.detectedAt ? new Date(v.detectedAt).toLocaleString() : '–'}</TableCell>
                <TableCell>
                  {v.examTerminated
                    ? <Chip size="small" color="error" label="Yes" />
                    : <Chip size="small" label="No" variant="outlined" />}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        component="div"
        count={rows.length}
        page={currentPage}
        onPageChange={(event, next) => setPage(next)}
        rowsPerPage={rowsPerPage}
        onRowsPerPageChange={(event) => { setRowsPerPage(Number(event.target.value)); setPage(0) }}
        rowsPerPageOptions={[10, 25, 50, 100]}
      />
    </>
  )

  let paperContent
  if (loading) {
    paperContent = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} height={52} />)}
      </Box>
    )
  } else if (violations.length === 0) {
    paperContent = <EmptyState title="No violations recorded" description="Proctoring violations will appear here." />
  } else if (rows.length === 0) {
    paperContent = <EmptyState title="No violations match these filters" description="Clear the search or widen the filters." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader
        title="Violation Management"
        subtitle="Monitor proctoring violations across exam sessions"
        action={(
          <>
            <Button
              variant="contained"
              startIcon={<DownloadIcon />}
              disabled={loading || rows.length === 0}
              onClick={(e) => setExportAnchor(e.currentTarget)}
            >
              Export{hasFilters ? ` (${rows.length})` : ''}
            </Button>
            <Menu anchorEl={exportAnchor} open={Boolean(exportAnchor)} onClose={() => setExportAnchor(null)}>
              <MenuItem onClick={() => handleExport('PDF')}>
                <ListItemIcon><PictureAsPdfIcon fontSize="small" /></ListItemIcon>
                <ListItemText primary="PDF report" secondary="Branded, with summary and details" />
              </MenuItem>
              <MenuItem onClick={() => handleExport('CSV')}>
                <ListItemIcon><TableChartIcon fontSize="small" /></ListItemIcon>
                <ListItemText primary="CSV (.csv)" secondary="Opens in Excel" />
              </MenuItem>
            </Menu>
          </>
        )}
      />

      <Paper sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2} alignItems="center">
          <Grid item xs={12} md={6}>
            <TextField
              fullWidth
              size="small"
              label="Search"
              placeholder="Candidate, email, exam, description, #session"
              value={filters.search}
              onChange={(e) => setFilter('search', e.target.value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbSelect
              fullWidth
              size="small"
              label="Violation type"
              placeholder="All"
              options={filterOptions.violationType}
              value={filters.violationType}
              onChange={(value) => setFilter('violationType', value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbSelect
              fullWidth
              size="small"
              label="Action"
              placeholder="All"
              options={filterOptions.actionTaken}
              value={filters.actionTaken}
              onChange={(value) => setFilter('actionTaken', value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbSelect
              fullWidth
              size="small"
              label="Level"
              placeholder="All"
              options={filterOptions.certificationLevel}
              value={filters.certificationLevel}
              onChange={(value) => setFilter('certificationLevel', value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbSelect
              fullWidth
              size="small"
              label="Session status"
              placeholder="All"
              options={filterOptions.sessionStatus}
              value={filters.sessionStatus}
              onChange={(value) => setFilter('sessionStatus', value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbDateField
              fullWidth
              size="small"
              label="Detected from"
              value={filters.from}
              max={filters.to || undefined}
              onChange={(value) => setFilter('from', value)}
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <PcbDateField
              fullWidth
              size="small"
              label="Detected to"
              value={filters.to}
              min={filters.from || undefined}
              onChange={(value) => setFilter('to', value)}
            />
          </Grid>
          <Grid item xs={12} md={10}>
            <Typography variant="caption" color="text.secondary">
              {loading
                ? 'Loading violations…'
                : `Showing ${rows.length} of ${violations.length} violations. Exports include every filtered row in the current sort order, not just this page.`}
            </Typography>
          </Grid>
          <Grid item xs={12} md={2} sx={{ textAlign: { md: 'right' } }}>
            <Button disabled={!hasFilters} onClick={() => { setFilters(EMPTY_FILTERS); setPage(0) }}>
              Clear filters
            </Button>
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

export default AdminViolationsPage
