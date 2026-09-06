// ems_frontend/src/components/layout/LayoutWrapper.jsx
import { useCallback, useEffect, useState } from 'react'
import {
  Box, AppBar, Toolbar, IconButton, Menu, MenuItem, Drawer, List,
  ListItemButton, ListItemIcon, ListItemText, Divider, Typography,
  Avatar, Tooltip, Chip, ListSubheader, Snackbar, Alert
} from '@mui/material'
import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { useDispatch, useSelector } from 'react-redux'
import PropTypes from 'prop-types'
import MenuIcon from '@mui/icons-material/Menu'
import LogoutIcon from '@mui/icons-material/Logout'
import PersonOutlineIcon from '@mui/icons-material/PersonOutline'
import DashboardIcon from '@mui/icons-material/SpaceDashboardRounded'
import SchoolIcon from '@mui/icons-material/SchoolRounded'
import AssignmentIcon from '@mui/icons-material/AssignmentRounded'
import CardGiftcardIcon from '@mui/icons-material/WorkspacePremiumRounded'
import AnalyticsIcon from '@mui/icons-material/InsightsRounded'
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettingsRounded'
import GroupIcon from '@mui/icons-material/GroupRounded'
import QuizIcon from '@mui/icons-material/QuizRounded'
import GavelIcon from '@mui/icons-material/GavelRounded'
import EventNoteIcon from '@mui/icons-material/EventNoteRounded'
import DateRangeIcon from '@mui/icons-material/DateRangeRounded'
import TableChartIcon from '@mui/icons-material/TableChartRounded'
import PaidIcon from '@mui/icons-material/PaidRounded'
import HistoryRounded from '@mui/icons-material/HistoryRounded'
import { logout } from '../../store/slices/authSlice'
import { authAPI } from '../../api/authAPI'
import { useIdleTimeout, clearIdleStamp } from '../../hooks/useIdleTimeout'
import { useProfilePhoto } from '../../contexts/ProfilePhotoContext'
import SessionTimeoutDialog from '../common/SessionTimeoutDialog'
import PcbBackdrop from '../brand/PcbBackdrop'
import { ChipLogo } from '../brand/BrandMark'
import { tokens, fonts, gradients } from '../../styles/tokens'

const DRAWER_WIDTH = 236
const HEADER_TOOLBAR_HEIGHT = 48

const LayoutWrapper = ({ children }) => {
  const navigate = useNavigate()
  const location = useLocation()
  const dispatch = useDispatch()
  const { user, refreshToken } = useSelector((state) => state.auth)
  const { profile } = useSelector((state) => state.user)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [anchorEl, setAnchorEl] = useState(null)
  const [globalAlert, setGlobalAlert] = useState({ open: false, severity: 'info', message: '' })

  const isAdmin = user?.role === 'ADMIN' || user?.roles?.includes('ADMIN')

  const userMenu = [
    { label: 'Dashboard', icon: <DashboardIcon />, path: '/dashboard' },
    { label: 'Certifications', icon: <SchoolIcon />, path: '/certifications' },
    { label: 'Exams', icon: <AssignmentIcon />, path: '/exams' },
    { label: 'Certificates', icon: <CardGiftcardIcon />, path: '/certificates' },
    { label: 'Exam Reports', icon: <TableChartIcon />, path: '/exam-reports' },
    // { label: 'Reports', icon: <AnalyticsIcon />, path: '/reports' }
  ]

  const adminMenu = [
    { label: 'Overview', icon: <AnalyticsIcon />, path: '/admin/dashboard' },
    { label: 'Users', icon: <GroupIcon />, path: '/admin/users' },
    { label: 'Exams', icon: <EventNoteIcon />, path: '/admin/exams' },
    // Sits directly under Exams: it is the same subject seen from the other
    // side — what an exam is, then when it can be sat.
    { label: 'Booking Windows', icon: <DateRangeIcon />, path: '/admin/booking-windows' },
    { label: 'Questions', icon: <QuizIcon />, path: '/admin/questions' },
    { label: 'Payments', icon: <PaidIcon />, path: '/admin/payments' },
    { label: 'Exam Reports', icon: <TableChartIcon />, path: '/admin/exam-reports' },
    { label: 'Violations', icon: <GavelIcon />, path: '/admin/violations' },
    { label: 'Audit Log', icon: <HistoryRounded />, path: '/admin/audit-log' },
    { label: 'Reports', icon: <AnalyticsIcon />, path: '/admin/reports' }
  ]

  const primaryMenu = isAdmin ? adminMenu : userMenu

  const handleNavigate = (path) => {
    navigate(path)
    setMobileOpen(false)
  }

  /**
   * Ends the session on both sides: revokes the refresh token server-side, then
   * clears local state.
   *
   * The revocation is fire-and-forget. `/auth/logout` is permitAll and reads
   * only the token in its body, so it does not race the teardown below — and
   * awaiting it would leave the user looking at a live session for up to the
   * request timeout whenever the backend is slow or unreachable. A signed-out
   * user must be signed out locally whether or not the server was listening.
   */
  const endSession = useCallback(
    (notice) => {
      if (refreshToken) {
        authAPI.logout(refreshToken).catch(() => {
          /* best effort: the token still expires on its own schedule */
        })
      }
      clearIdleStamp()
      dispatch(logout())
      navigate('/', { replace: true, state: notice ? { authNotice: notice } : undefined })
    },
    [dispatch, navigate, refreshToken]
  )

  const handleLogout = () => endSession()

  /*
   * Idle expiry is armed here, in the app shell, which means the live exam at
   * `/exam/:applicationId` is exempt: that route renders outside this wrapper.
   * That exemption is the point rather than an oversight — a candidate reading
   * a long question produces no input for minutes at a stretch, and ending the
   * session under them would discard the attempt. The exam has its own timer
   * and its own proctoring; this clock covers the portal around it.
   */
  const { warningMsLeft, stayActive } = useIdleTimeout({
    onTimeout: () => endSession('You were signed out after 10 minutes of inactivity.')
  })

  const isActive = (path) =>
    location.pathname === path ||
    (path !== '/dashboard' && location.pathname.startsWith(path))

  const displayFirstName = profile?.firstName || user?.firstName || ''
  const displayLastName = profile?.lastName || user?.lastName || ''
  const displayEmail = profile?.email || user?.email || ''
  // Not `profile.profilePhotoUrl`: that is an authenticated endpoint, and an
  // <img> pointed straight at it 401s. The provider holds the fetched bytes.
  const { photoUrl: displayProfilePhoto } = useProfilePhoto()
  const initials = (`${displayFirstName?.[0] || ''}${displayLastName?.[0] || ''}` || 'U').toUpperCase()

  useEffect(() => {
    const nextAlert = location.state?.globalAlert
    if (!nextAlert?.message) {
      return
    }

    setGlobalAlert({
      open: true,
      severity: nextAlert.severity || 'info',
      message: nextAlert.message
    })

    // Clear one-time navigation state so the alert does not repeat on refresh.
    navigate(
      {
        pathname: location.pathname,
        search: location.search,
        hash: location.hash
      },
      { replace: true, state: {} }
    )
  }, [location, navigate])

  const drawerContent = (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      {/* copper rail running down the board edge */}
      <Box
        aria-hidden="true"
        sx={{
          position: 'absolute',
          top: 0,
          bottom: 0,
          right: 0,
          width: '1px',
          background: `linear-gradient(180deg, transparent, ${tokens.copper}, transparent)`,
          opacity: 0.5,
        }}
      />

      <Box
        onClick={() => handleNavigate(isAdmin ? '/admin/dashboard' : '/dashboard')}
        sx={{ px: 2, py: 1.5, display: 'flex', alignItems: 'center', gap: 1.25, cursor: 'pointer' }}
      >
        <ChipLogo size={26} />
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 12, fontWeight: 800, letterSpacing: '1.6px', lineHeight: 1.1 }}>
            CERTIFIED EMS
          </Typography>
          <Typography
            sx={{ fontFamily: fonts.mono, fontSize: 8.5, letterSpacing: '1.6px', color: tokens.copper }}
          >
            ENGINEER BOARD
          </Typography>
        </Box>
      </Box>
      <Divider />

      <ListSubheader
        sx={{
          bgcolor: 'transparent',
          fontFamily: fonts.mono,
          fontSize: 9.5,
          fontWeight: 500,
          letterSpacing: '2px',
          color: tokens.muted,
          lineHeight: '34px',
          px: 2,
        }}
      >
        {isAdmin ? 'ADMINISTRATION' : 'CERTIFICATION'}
      </ListSubheader>

      <List sx={{ px: 0, py: 0.5 }}>
        {primaryMenu.map((item) => {
          const active = isActive(item.path)
          return (
            <ListItemButton
              key={item.path}
              selected={active}
              onClick={() => handleNavigate(item.path)}
              sx={{
                my: 0.3,
                position: 'relative',
                '&::before': active
                  ? {
                      content: '""',
                      position: 'absolute',
                      left: 0,
                      top: 8,
                      bottom: 8,
                      width: 3,
                      borderRadius: '0 3px 3px 0',
                      background: tokens.copperLt,
                      boxShadow: `0 0 10px ${tokens.copper}`,
                    }
                  : undefined,
              }}
            >
              <ListItemIcon sx={{ minWidth: 38 }}>{item.icon}</ListItemIcon>
              <ListItemText
                primary={item.label}
                primaryTypographyProps={{ fontWeight: 600, fontSize: '0.875rem' }}
              />
            </ListItemButton>
          )
        })}
      </List>

      <Box sx={{ flexGrow: 1 }} />
      <Divider />
      <Box sx={{ p: 1.5, display: 'flex', alignItems: 'center', gap: 1.25 }}>
        <Avatar
          variant="rounded"
          src={displayProfilePhoto || undefined}
          sx={{
            flex: 'none',
            width: 34,
            height: 34,
            borderRadius: '10px',
            fontFamily: fonts.mono,
            fontSize: 12,
            fontWeight: 500,
            color: tokens.copperLt,
            background: 'rgba(192,138,46,.16)',
            border: '1px solid rgba(192,138,46,.4)',
          }}
        >
          {initials}
        </Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="caption" fontWeight={700} noWrap sx={{ display: 'block' }}>
            {displayFirstName} {displayLastName}
          </Typography>
          <Typography
            variant="caption"
            color="text.secondary"
            noWrap
            sx={{ display: 'block', fontSize: '0.68rem' }}
          >
            {displayEmail}
          </Typography>
        </Box>
      </Box>
    </Box>
  )

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <PcbBackdrop intensity="subtle" />

      <AppBar
        position="fixed"
        elevation={0}
        sx={{
          zIndex: (t) => t.zIndex.drawer + 1,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          ml: { md: `${DRAWER_WIDTH}px` },
          minHeight: `${HEADER_TOOLBAR_HEIGHT}px`,
          background: 'rgba(6,26,19,.78)',
          backgroundImage: 'none',
          backdropFilter: 'blur(10px)',
          borderRadius: 0,
          borderBottom: `1px solid ${tokens.line}`,
        }}
      >
        <Toolbar
          variant="dense"
          sx={{
            minHeight: { xs: `${HEADER_TOOLBAR_HEIGHT}px`, sm: `${HEADER_TOOLBAR_HEIGHT}px` },
            height: `${HEADER_TOOLBAR_HEIGHT}px`,
            py: 0,
            px: 1.5,
          }}
        >
          <IconButton
            edge="start"
            onClick={() => setMobileOpen(true)}
            sx={{ mr: 1, display: { md: 'none' } }}
          >
            <MenuIcon />
          </IconButton>

          {/* Session lamp — a copper pill, matching the status pills on the board. */}
          <Box
            sx={{
              display: { xs: 'none', sm: 'inline-flex' },
              alignItems: 'center',
              gap: 1.125,
              py: '6px',
              pl: '11px',
              pr: 2,
              borderRadius: '999px',
              fontFamily: fonts.mono,
              fontSize: 11,
              fontWeight: 600,
              letterSpacing: '1.2px',
              color: '#DCC79A',
              background: 'rgba(192,138,46,.12)',
              border: '1px solid rgba(192,138,46,.36)',
            }}
          >
            <Box
              component="span"
              sx={{
                width: 7,
                height: 7,
                borderRadius: '50%',
                background: tokens.greenGlow,
                boxShadow: `0 0 9px ${tokens.greenGlow}`,
                '@keyframes railBlink': { '0%,100%': { opacity: 1 }, '50%': { opacity: 0.3 } },
                animation: 'railBlink 2.4s ease-in-out infinite',
              }}
            />
            SESSION LIVE
          </Box>

          <Box sx={{ flexGrow: 1 }} />

          {isAdmin && (
            <Chip
              size="small"
              color="primary"
              variant="outlined"
              icon={<AdminPanelSettingsIcon />}
              label="Admin"
              sx={{
                mr: 2,
                display: { xs: 'none', sm: 'flex' },
                borderColor: 'rgba(192,138,46,.4)',
                color: tokens.copperLt,
              }}
            />
          )}

          <Tooltip title="Account">
            <IconButton onClick={(e) => setAnchorEl(e.currentTarget)} size="small" sx={{ p: 0.25 }}>
              <Avatar
                variant="rounded"
                src={displayProfilePhoto || undefined}
                sx={{
                  width: 38,
                  height: 38,
                  borderRadius: '11px',
                  fontFamily: fonts.mono,
                  fontSize: 13,
                  fontWeight: 500,
                  color: tokens.copperLt,
                  background: 'rgba(192,138,46,.14)',
                  border: '1px solid rgba(192,138,46,.4)',
                }}
              >
                {initials}
              </Avatar>
            </IconButton>
          </Tooltip>

          <Menu
            anchorEl={anchorEl}
            open={Boolean(anchorEl)}
            onClose={() => setAnchorEl(null)}
            transformOrigin={{ horizontal: 'right', vertical: 'top' }}
            anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
          >
            <Box sx={{ px: 2, py: 1 }}>
              <Typography variant="subtitle2" fontWeight={700}>
                {displayFirstName} {displayLastName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {displayEmail}
              </Typography>
            </Box>
            <Divider />
            <MenuItem onClick={() => { navigate('/profile'); setAnchorEl(null) }}>
              <ListItemIcon><PersonOutlineIcon fontSize="small" /></ListItemIcon>
              Profile
            </MenuItem>
            <MenuItem onClick={handleLogout}>
              <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>
              Logout
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>

      <Box
        component="nav"
        // `position` is what makes the z-index bite: without it the rail sits in
        // the root stacking context and the fixed backdrop draws its traces
        // straight across the navigation.
        sx={{
          position: 'relative',
          zIndex: (t) => t.zIndex.drawer,
          width: { md: DRAWER_WIDTH },
          flexShrink: { md: 0 },
        }}
      >
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{
            display: { xs: 'block', md: 'none' },
            // Restated here rather than relying on the theme's MuiDrawer slot
            // winning over the blanket MuiPaper override.
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
              borderRadius: 0,
              borderRight: `1px solid ${tokens.line}`,
              // Shorthand last — a `backgroundImage` after it would wipe the
              // gradient and let the board's traces show through the rail.
              background: gradients.rail,
            }
          }}
        >
          {drawerContent}
        </Drawer>
        <Drawer
          variant="permanent"
          open
          sx={{
            display: { xs: 'none', md: 'block' },
            // Restated here rather than relying on the theme's MuiDrawer slot
            // winning over the blanket MuiPaper override.
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
              borderRadius: 0,
              borderRight: `1px solid ${tokens.line}`,
              // Shorthand last — a `backgroundImage` after it would wipe the
              // gradient and let the board's traces show through the rail.
              background: gradients.rail,
            }
          }}
        >
          {drawerContent}
        </Drawer>
      </Box>

      <Box
        component="main"
        sx={{
          position: 'relative',
          zIndex: 1,
          flexGrow: 1,
          minWidth: 0,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` }
        }}
      >
        <Toolbar
          variant="dense"
          sx={{ minHeight: { xs: `${HEADER_TOOLBAR_HEIGHT}px`, sm: `${HEADER_TOOLBAR_HEIGHT}px` } }}
        />
        <Box sx={{ p: { xs: 1.5, sm: 2, md: 2.5 } }}>{children || <Outlet />}</Box>
        <Snackbar
          open={globalAlert.open}
          autoHideDuration={6000}
          onClose={() => setGlobalAlert((prev) => ({ ...prev, open: false }))}
          anchorOrigin={{ vertical: 'top', horizontal: 'center' }}
        >
          <Alert
            severity={globalAlert.severity}
            variant="standard"
            onClose={() => setGlobalAlert((prev) => ({ ...prev, open: false }))}
            sx={{ width: '100%' }}
          >
            {globalAlert.message}
          </Alert>
        </Snackbar>

        <SessionTimeoutDialog
          msLeft={warningMsLeft}
          onStay={stayActive}
          onLogout={() => endSession()}
        />
      </Box>
    </Box>
  )
}

LayoutWrapper.propTypes = {
  children: PropTypes.node
}

export default LayoutWrapper
