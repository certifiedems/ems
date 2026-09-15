// ems_frontend/src/pages/admin/AdminUsersPage.jsx
import { useEffect, useState, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TextField, InputAdornment, Chip, Stack, Button,
  Skeleton, Snackbar, Alert, Avatar, Typography
} from '@mui/material'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'
import SearchIcon from '@mui/icons-material/SearchRounded'
import LockIcon from '@mui/icons-material/LockRounded'
import LockOpenIcon from '@mui/icons-material/LockOpenRounded'

const AdminUsersPage = () => {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async (term) => {
    setLoading(true)
    try {
      const res = await adminAPI.getAllUsers(term ? { search: term } : undefined)
      setUsers(res.data.data || [])
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to load users')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    const t = setTimeout(() => load(search), 400)
    return () => clearTimeout(t)
  }, [search, load])

  const toggleEnabled = async (user) => {
    try {
      await adminAPI.setUserEnabled(user.id, !user.enabled)
      load(search)
    } catch (err) {
      setError(err.response?.data?.message || 'Action failed')
    }
  }

  const toggleLock = async (user) => {
    try {
      await adminAPI.setUserLocked(user.id, user.accountNonLocked) // if currently unlocked -> lock
      load(search)
    } catch (err) {
      setError(err.response?.data?.message || 'Action failed')
    }
  }

  const tableContent = (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>User</TableCell>
            <TableCell>User ID</TableCell>
            <TableCell>Skill</TableCell>
            <TableCell>Created</TableCell>
            <TableCell>Last Login</TableCell>
            <TableCell>Status</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {users.map((u) => (
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
              <TableCell>{u.userId}</TableCell>
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
                <Stack direction="row" spacing={1} justifyContent="flex-end">
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
  )

  let paperContent
  if (loading) {
    paperContent = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} height={52} />)}
      </Box>
    )
  } else if (users.length === 0) {
    paperContent = <EmptyState title="No users found" description="Try a different search term." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader title="User Management" subtitle="View and manage platform users" />

      <Paper sx={{ p: 2, mb: 2 }}>
        <TextField
          fullWidth
          size="small"
          placeholder="Search by name, email or user ID"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon fontSize="small" />
              </InputAdornment>
            )
          }}
        />
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

export default AdminUsersPage
