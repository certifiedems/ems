// ems_frontend/src/pages/admin/AdminUsersPage.jsx
import { useEffect, useState, useCallback, useMemo } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TablePagination, TextField, InputAdornment, Chip, Stack, Button,
  Skeleton, Snackbar, Alert, Avatar, Typography
} from '@mui/material'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'
import PcbSelect from '../../components/common/PcbSelect'
import { tokens } from '../../styles/tokens'
import SearchIcon from '@mui/icons-material/SearchRounded'
import LockIcon from '@mui/icons-material/LockRounded'
import LockOpenIcon from '@mui/icons-material/LockOpenRounded'

const SEARCH_DEBOUNCE_MS = 400

/** Maps straight onto the `enabled` query parameter the endpoint accepts. */
const STATUS_FILTERS = [
  { value: '', label: 'All statuses' },
  { value: 'true', label: 'Enabled' },
  { value: 'false', label: 'Disabled' }
]

const LOCK_FILTERS = [
  { value: '', label: 'Any lock state' },
  { value: 'LOCKED', label: 'Locked' },
  { value: 'UNLOCKED', label: 'Unlocked' }
]

const SKILL_FILTERS = [
  { value: '', label: 'All levels' },
  { value: 'L1', label: 'L1' },
  { value: 'L2', label: 'L2' },
  { value: 'L3', label: 'L3' },
  { value: 'NONE', label: 'Not set' }
]

const AdminUsersPage = () => {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const [status, setStatus] = useState('')
  const [lockState, setLockState] = useState('')
  const [skill, setSkill] = useState('')

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(25)

  // Typing settles before it reaches the server; the status filter is a
  // parameter the endpoint understands, so it is applied there too.
  useEffect(() => {
    const t = setTimeout(() => setAppliedSearch(search.trim()), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [search])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const params = {}
      if (appliedSearch) params.search = appliedSearch
      if (status) params.enabled = status
      const res = await adminAPI.getAllUsers(Object.keys(params).length ? params : undefined)
      setUsers(res.data.data || [])
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to load users')
    } finally {
      setLoading(false)
    }
  }, [appliedSearch, status])

  useEffect(() => {
    load()
  }, [load])

  // Lock state and skill level have no server-side parameter, so they narrow
  // the fetched page in place.
  const filteredUsers = useMemo(() => users.filter((u) => {
    if (lockState === 'LOCKED' && u.accountNonLocked) return false
    if (lockState === 'UNLOCKED' && !u.accountNonLocked) return false
    if (skill === 'NONE' && u.currentSkillLevel) return false
    if (skill && skill !== 'NONE' && u.currentSkillLevel !== skill) return false
    return true
  }), [users, lockState, skill])

  // A narrowed list can leave the reader stranded past the last page.
  const lastPage = Math.max(0, Math.ceil(filteredUsers.length / rowsPerPage) - 1)
  const safePage = Math.min(page, lastPage)
  const pageUsers = filteredUsers.slice(safePage * rowsPerPage, (safePage + 1) * rowsPerPage)

  const filtersActive = Boolean(search.trim() || status || lockState || skill)

  const clearFilters = () => {
    setSearch('')
    setStatus('')
    setLockState('')
    setSkill('')
    setPage(0)
  }

  const toggleEnabled = async (user) => {
    try {
      await adminAPI.setUserEnabled(user.id, !user.enabled)
      load()
    } catch (err) {
      setError(err.response?.data?.message || 'Action failed')
    }
  }

  const toggleLock = async (user) => {
    try {
      await adminAPI.setUserLocked(user.id, user.accountNonLocked) // if currently unlocked -> lock
      load()
    } catch (err) {
      setError(err.response?.data?.message || 'Action failed')
    }
  }

  const tableContent = (
    <>
      {/* Seven columns with a two-button action cell do not fit a laptop at
          full width; the table keeps its natural width and scrolls sideways
          rather than wrapping every cell into an unreadable column. */}
      <TableContainer sx={{ overflowX: 'auto' }}>
        <Table sx={{ minWidth: 1100 }}>
          <TableHead>
            <TableRow>
              <TableCell sx={{ minWidth: 240 }}>User</TableCell>
              <TableCell sx={{ minWidth: 180 }}>User ID</TableCell>
              <TableCell>Skill</TableCell>
              <TableCell>Created</TableCell>
              <TableCell>Last Login</TableCell>
              <TableCell sx={{ minWidth: 150 }}>Status</TableCell>
              <TableCell align="right" sx={{ minWidth: 200 }}>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {pageUsers.map((u) => (
              <TableRow key={u.id} hover>
                <TableCell>
                  <Stack direction="row" spacing={1.5} alignItems="center">
                    <Avatar sx={{ width: 36, height: 36, fontSize: 14 }}>
                      {u.firstName?.[0]}{u.lastName?.[0]}
                    </Avatar>
                    <Box>
                      <Typography variant="body2" fontWeight={600}>
                        {u.firstName} {u.lastName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {u.email}
                      </Typography>
                    </Box>
                  </Stack>
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{u.userId}</TableCell>
                <TableCell>{u.currentSkillLevel || '–'}</TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {u.createdAt ? new Date(u.createdAt).toLocaleDateString() : '–'}
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : '–'}
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    <Chip
                      size="small"
                      label={u.enabled ? 'Enabled' : 'Disabled'}
                      color={u.enabled ? 'success' : 'default'}
                      variant="outlined"
                    />
                    {!u.accountNonLocked && (
                      <Chip size="small" label="Locked" color="error" variant="outlined" />
                    )}
                  </Stack>
                </TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" variant="outlined" onClick={() => toggleEnabled(u)}>
                      {u.enabled ? 'Disable' : 'Enable'}
                    </Button>
                    <Button
                      size="small"
                      variant="outlined"
                      color={u.accountNonLocked ? 'error' : 'success'}
                      startIcon={u.accountNonLocked ? <LockIcon /> : <LockOpenIcon />}
                      onClick={() => toggleLock(u)}
                    >
                      {u.accountNonLocked ? 'Lock' : 'Unlock'}
                    </Button>
                  </Stack>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination
        component="div"
        count={filteredUsers.length}
        page={safePage}
        onPageChange={(event, next) => setPage(next)}
        rowsPerPage={rowsPerPage}
        onRowsPerPageChange={(event) => { setRowsPerPage(Number(event.target.value)); setPage(0) }}
        rowsPerPageOptions={[10, 25, 50, 100]}
        sx={{ borderTop: `1px solid ${tokens.line}` }}
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
  } else if (filteredUsers.length === 0) {
    paperContent = (
      <EmptyState
        title="No users found"
        description={filtersActive
          ? 'No account matches the current search and filters.'
          : 'No accounts have been registered yet.'}
        action={filtersActive
          ? <Button variant="outlined" onClick={clearFilters}>Clear filters</Button>
          : undefined}
      />
    )
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader title="User Management" subtitle="View and manage platform users" />

      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1.5 }}>
          <TextField
            size="small"
            placeholder="Search by name, email or user ID"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0) }}
            sx={{ flex: '1 1 280px', minWidth: 240 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              )
            }}
          />
          <PcbSelect
            size="small"
            label="Status"
            value={status}
            onChange={(value) => { setStatus(value); setPage(0) }}
            options={STATUS_FILTERS}
            placeholder="All statuses"
            sx={{ minWidth: 160 }}
          />
          <PcbSelect
            size="small"
            label="Lock"
            value={lockState}
            onChange={(value) => { setLockState(value); setPage(0) }}
            options={LOCK_FILTERS}
            placeholder="Any lock state"
            sx={{ minWidth: 170 }}
          />
          <PcbSelect
            size="small"
            label="Level"
            value={skill}
            onChange={(value) => { setSkill(value); setPage(0) }}
            options={SKILL_FILTERS}
            placeholder="All levels"
            sx={{ minWidth: 150 }}
          />
          {filtersActive && (
            <Button size="small" onClick={clearFilters} sx={{ color: tokens.copperLt }}>
              Clear
            </Button>
          )}
        </Box>
      </Paper>

      <Paper sx={{ overflow: 'hidden' }}>
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

export default AdminUsersPage
