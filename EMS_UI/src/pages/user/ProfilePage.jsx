// ems_frontend/src/pages/user/ProfilePage.jsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import {
  Box, TextField, Button, Typography, Avatar, Alert, CircularProgress,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  InputAdornment, OutlinedInput, Select, MenuItem, IconButton, Tooltip
} from '@mui/material'
import { useForm } from 'react-hook-form'
import PhotoCameraIcon from '@mui/icons-material/PhotoCameraRounded'
import FileDownloadIcon from '@mui/icons-material/FileDownloadRounded'
import SearchIcon from '@mui/icons-material/SearchRounded'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftRounded'
import ChevronRightIcon from '@mui/icons-material/ChevronRightRounded'
import { userAPI } from '../../api/userAPI'
import { paymentAPI } from '../../api/paymentAPI'
import { certificateAPI } from '../../api/certificateAPI'
import { fetchProfileSuccess, updateProfileSuccess, clearSuccess } from '../../store/slices/userSlice'
import { useProfilePhoto } from '../../contexts/ProfilePhotoContext'
import PageHeader from '../../components/common/PageHeader'
import PcbSelect from '../../components/common/PcbSelect'
import ProfilePhotoDialog from '../../components/profile/ProfilePhotoDialog'
import MyTestimonials from '../../components/testimonial/MyTestimonials'
import { tokens, fonts, ctaButton } from '../../styles/tokens'

const SKILL_LEVELS = ['L1', 'L2', 'L3']
const ROWS_PER_PAGE_OPTIONS = [5, 10, 20]

/** Every control on this page sits on the same 44px rule. */
const fieldSx = {
  '& .MuiOutlinedInput-root': {
    borderRadius: '10px',
    backgroundColor: 'rgba(3,16,11,.8)',
    fontSize: 13,
    fontWeight: 500,
  },
  '& .MuiOutlinedInput-input': { height: 44, boxSizing: 'border-box', py: 0 },
  '& .MuiOutlinedInput-input:is(textarea)': { height: 'auto', py: 1.5 },
}

/**
 * A dropdown on that same 44px rule, measured on the root instead of the inner
 * box. MUI's select resets `height: auto` on the value box with a selector
 * deliberately built to outrank the input's own height, so `fieldSx` — which
 * sizes the input — leaves a select at its ~23px minimum. Sizing the root is
 * what the exam report's filter dropdowns do, and it holds for both controls.
 */
const selectFieldSx = {
  ...fieldSx,
  '& .MuiOutlinedInput-root': { ...fieldSx['& .MuiOutlinedInput-root'], height: 44 },
  '& .MuiOutlinedInput-input': { py: 0 },
}

const labelSx = {
  display: 'block',
  mb: 0.75,
  fontFamily: fonts.mono,
  fontSize: 9.5,
  letterSpacing: '.5px',
  textTransform: 'uppercase',
  color: '#93AC9E',
}

const thSx = {
  p: '11px 18px',
  fontSize: 11,
  fontWeight: 600,
  color: '#93AC9E',
  whiteSpace: 'nowrap',
  borderBottom: `1px solid ${tokens.line}`,
}

const tdSx = {
  p: '13px 18px',
  whiteSpace: 'nowrap',
  borderBottom: '1px solid rgba(150,195,172,.08)',
}

/**
 * A payment's presentation.
 *
 * Only a settled payment reads as affirmative green — a refund or failure is
 * coloured so the row is never mistaken for proof of a completed purchase.
 */
const STATUS_TONE = {
  SUCCESS: { label: 'PAID', fg: tokens.greenLt, bg: 'rgba(95,174,146,.14)', border: 'rgba(95,174,146,.4)' },
  REFUNDED: { label: 'REFUNDED', fg: tokens.copperLt, bg: 'rgba(192,138,46,.14)', border: 'rgba(192,138,46,.4)' },
  FAILED: { label: 'FAILED', fg: '#FFB0B0', bg: 'rgba(224,101,101,.14)', border: 'rgba(224,101,101,.4)' },
  PENDING: { label: 'PENDING', fg: '#93AC9E', bg: 'rgba(150,195,172,.1)', border: 'rgba(150,195,172,.28)' },
}

const toneFor = (status) => STATUS_TONE[status] || STATUS_TONE.PENDING

const formatAmount = (amount, currency) => {
  if (amount === null || amount === undefined) return '—'
  const figure = Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return currency ? `${currency} ${figure}` : figure
}

const formatDate = (value) => (value ? new Date(value).toLocaleDateString('en-GB') : '—')

const Field = ({ label, children }) => (
  <Box>
    <Typography component="label" sx={labelSx}>{label}</Typography>
    {children}
  </Box>
)

const ProfilePage = () => {
  const dispatch = useDispatch()
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(true)
  const [payments, setPayments] = useState([])
  const [paymentsLoading, setPaymentsLoading] = useState(true)
  const [certificates, setCertificates] = useState([])
  const [receiptBusy, setReceiptBusy] = useState(null)
  const [pageError, setPageError] = useState('')
  const [photoNotice, setPhotoNotice] = useState('')
  const [photoDialogOpen, setPhotoDialogOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(ROWS_PER_PAGE_OPTIONS[0])
  const { profile, success, error } = useSelector((state) => state.user)
  const { register, handleSubmit, reset, watch, setValue } = useForm()
  const { photoUrl, uploading: photoUploading, error: photoError, uploadPhoto, clearError } = useProfilePhoto()

  // The skill dropdown is a custom control rather than an <input>, so it is
  // registered by hand and written back through setValue; `reset()` on the
  // fetched profile still seeds it like every other field.
  useEffect(() => {
    register('currentSkillLevel')
  }, [register])
  const skillLevel = watch('currentSkillLevel') || ''

  useEffect(() => {
    const fetchProfile = async () => {
      try {
        const response = await userAPI.getProfile()
        dispatch(fetchProfileSuccess(response.data.data))
        reset(response.data.data)
      } catch (err) {
        console.error('Failed to fetch profile:', err)
        setPageError('Could not load your profile. Please refresh to try again.')
      } finally {
        setLoading(false)
      }
    }
    fetchProfile()
  }, [dispatch, reset])

  useEffect(() => {
    const fetchPayments = async () => {
      try {
        const response = await paymentAPI.getPaymentHistory()
        setPayments(response.data.data || [])
      } catch (err) {
        console.error('Failed to fetch payment history:', err)
      } finally {
        setPaymentsLoading(false)
      }
    }
    fetchPayments()
  }, [])

  // Certificates back the level badge: what the holder has passed, as opposed
  // to the level they picked for themselves in the form below.
  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const response = await certificateAPI.getCertificates()
        if (mounted) setCertificates(response.data.data || [])
      } catch (err) {
        console.error('Failed to fetch certificates:', err)
      }
    })()
    return () => {
      mounted = false
    }
  }, [])

  // The photo saves from its own dialog rather than with the form: it is edited
  // from the avatar, nowhere near the Save button, so holding it back until the
  // form is submitted would leave a cropped image unsaved with nothing said.
  const handlePhotoSave = async (croppedFile) => {
    setPhotoNotice('')
    const uploaded = await uploadPhoto(croppedFile)
    if (uploaded) {
      setPhotoDialogOpen(false)
      setPhotoNotice('Profile photo updated.')
      setTimeout(() => setPhotoNotice(''), 3000)
    }
  }

  const handlePhotoDialogOpen = () => {
    clearError()
    setPhotoDialogOpen(true)
  }

  const onSubmit = async (data) => {
    setSaving(true)
    setPageError('')
    try {
      const payload = {
        firstName: data.firstName,
        lastName: data.lastName,
        mobileNumber: data.mobileNumber,
        address: data.address,
        yearsOfExperience: data.yearsOfExperience ? Number(data.yearsOfExperience) : null,
        currentSkillLevel: data.currentSkillLevel,
        currentOrganization: data.currentOrganization,
        qualification: data.qualification,
        fatherName: data.fatherName
      }
      const response = await userAPI.updateProfile(payload)
      dispatch(updateProfileSuccess(response.data.data))
      setTimeout(() => dispatch(clearSuccess()), 3000)
    } catch (err) {
      console.error('Failed to update profile:', err)
      setPageError(err?.response?.data?.message || 'Could not save your changes. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  const handleDownloadReceipt = useCallback(async (transactionId) => {
    setReceiptBusy(transactionId)
    setPageError('')
    try {
      const res = await paymentAPI.downloadReceipt(transactionId)
      const url = window.URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }))
      const link = document.createElement('a')
      link.href = url
      link.setAttribute('download', `receipt-${transactionId}.pdf`)
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.URL.revokeObjectURL(url)
    } catch (err) {
      console.error('Failed to download receipt:', err)
      setPageError('Could not download that receipt. Please try again.')
    } finally {
      setReceiptBusy(null)
    }
  }, [])

  const initials = `${profile?.firstName?.[0] || ''}${profile?.lastName?.[0] || ''}`.toUpperCase()

  // The highest level actually certified. Expiry is deliberately ignored: a
  // lapsed certificate still records that the exam was passed, and the wallet
  // on the Certificates screen is where validity is tracked.
  const earnedLevel = useMemo(
    () => certificates.reduce((highest, c) => Math.max(highest, c.levelIndex || 0), 0),
    [certificates]
  )

  const settled = payments.filter((p) => p.paymentStatus === 'SUCCESS')
  const totalPaid = settled.reduce((sum, p) => sum + Number(p.amount || 0), 0)
  // Only label the total when everything in it is the same currency — a mixed
  // set would otherwise print one symbol against a meaningless sum.
  const currencies = new Set(settled.map((p) => p.currency).filter(Boolean))
  const totalCurrency = currencies.size === 1 ? [...currencies][0] : null

  // One box across the whole row: the columns are matched as they are printed,
  // so a search for "PAID" or "15/08/2026" finds what the reader can see
  // rather than the raw status enum or ISO timestamp behind it.
  const filteredPayments = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return payments
    return payments.filter((payment) =>
      [
        payment.transactionId,
        payment.description,
        formatDate(payment.paymentDate),
        formatAmount(payment.amount, payment.currency),
        toneFor(payment.paymentStatus).label,
      ]
        .filter(Boolean)
        .some((field) => String(field).toLowerCase().includes(term))
    )
  }, [payments, search])

  // Derived, not stored: filtering to fewer rows than the current offset would
  // otherwise leave an empty table under a "11-15 of 3" label.
  const pageCount = Math.max(1, Math.ceil(filteredPayments.length / rowsPerPage))
  const currentPage = Math.min(page, pageCount - 1)
  const pageStart = currentPage * rowsPerPage
  const visiblePayments = filteredPayments.slice(pageStart, pageStart + rowsPerPage)

  if (loading) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: 300 }}>
        <CircularProgress />
      </Box>
    )
  }

  // The identity sits in the banner's action slot rather than in a card of its
  // own — one masthead for the screen, the way every other page opens.
  const identity = (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 2.25, flexWrap: 'wrap' }}>
      {/* The photo is edited from the photo — a camera badge on the avatar
          rather than a button elsewhere in the banner. */}
      <Box sx={{ position: 'relative', flex: 'none' }}>
        <Avatar
          src={photoUrl || undefined}
          sx={{
            width: 64,
            height: 64,
            background: 'linear-gradient(160deg,#0E4D3C,#3B4A22)',
            border: '2px solid rgba(192,138,46,.4)',
            fontFamily: fonts.mono,
            fontSize: 18,
            fontWeight: 700,
            color: tokens.copperLt,
            opacity: photoUploading ? 0.45 : 1,
          }}
        >
          {initials}
        </Avatar>

        {photoUploading && (
          <CircularProgress
            size={26}
            sx={{ position: 'absolute', top: 19, left: 19, color: tokens.copperLt }}
          />
        )}

        <Tooltip title={photoUrl ? 'Change photo' : 'Add photo'}>
          <IconButton
            onClick={handlePhotoDialogOpen}
            disabled={photoUploading}
            aria-label={photoUrl ? 'Change profile photo' : 'Add profile photo'}
            sx={{
              position: 'absolute',
              right: -4,
              bottom: -4,
              width: 26,
              height: 26,
              color: '#062017',
              background: 'linear-gradient(96deg,#B07C24 0%,#E8C071 48%,#C08A2E 100%)',
              border: '2px solid #0B2C22',
              '&:hover': { background: 'linear-gradient(96deg,#C08A2E 0%,#F3DCAE 48%,#B07C24 100%)' },
            }}
          >
            <PhotoCameraIcon sx={{ fontSize: 13 }} />
          </IconButton>
        </Tooltip>
      </Box>

      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 15.5, fontWeight: 700, color: tokens.ink }}>
          {profile?.firstName} {profile?.lastName}
        </Typography>
        <Typography sx={{ mt: 0.25, fontSize: 12.5, color: '#C9DBD1' }}>{profile?.email}</Typography>
        {earnedLevel > 0 && (
          <Box
            component="span"
            sx={{
              display: 'inline-flex',
              mt: 0.75,
              px: 1.125,
              py: 0.375,
              borderRadius: '4px',
              fontFamily: fonts.mono,
              fontSize: 10.5,
              fontWeight: 700,
              letterSpacing: '.03em',
              color: tokens.copperLt,
              background: 'rgba(192,138,46,.14)',
              border: '1px solid rgba(192,138,46,.4)',
            }}
          >
            {`CERTIFIED L${earnedLevel}`}
          </Box>
        )}
      </Box>
    </Box>
  )

  return (
    <Box>
      <PageHeader
        title="My Profile"
        subtitle="Manage your personal information."
        action={identity}
      />

      <ProfilePhotoDialog
        open={photoDialogOpen}
        onClose={() => setPhotoDialogOpen(false)}
        currentPhotoUrl={photoUrl}
        onSave={handlePhotoSave}
        saving={photoUploading}
        uploadError={photoError}
      />

      {success && <Alert severity="success" sx={{ mb: 2 }}>Profile updated successfully!</Alert>}
      {photoNotice && <Alert severity="success" sx={{ mb: 2 }}>{photoNotice}</Alert>}
      {(pageError || error) && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setPageError('')}>
          {pageError || error}
        </Alert>
      )}

      {/* Details form */}
      <Box
        component="form"
        onSubmit={handleSubmit(onSubmit)}
        sx={{
          mb: 3,
          p: '24px 26px',
          borderRadius: '16px',
          background: 'rgba(11,44,34,.5)',
          border: `1px solid ${tokens.line}`,
        }}
      >
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit,minmax(220px,1fr))',
            gap: 2,
          }}
        >
          <Field label="First name">
            <TextField fullWidth sx={fieldSx} {...register('firstName')} />
          </Field>
          <Field label="Last name">
            <TextField fullWidth sx={fieldSx} {...register('lastName')} />
          </Field>
          <Field label="Email">
            <TextField fullWidth sx={fieldSx} type="email" value={profile?.email || ''} disabled />
          </Field>
          <Field label="Mobile number">
            <TextField fullWidth sx={fieldSx} type="tel" {...register('mobileNumber')} />
          </Field>
          <Field label="Years of experience">
            <TextField fullWidth sx={fieldSx} type="number" {...register('yearsOfExperience')} />
          </Field>
          <Field label="Current skill level">
            <PcbSelect
              fullWidth
              sx={selectFieldSx}
              options={SKILL_LEVELS}
              placeholder="Select level"
              value={skillLevel}
              onChange={(value) => setValue('currentSkillLevel', value, { shouldDirty: true })}
            />
          </Field>
          <Field label="Qualification">
            <TextField fullWidth sx={fieldSx} {...register('qualification')} />
          </Field>
          <Field label="Organization">
            <TextField fullWidth sx={fieldSx} {...register('currentOrganization')} />
          </Field>
          <Field label="Father's name">
            <TextField fullWidth sx={fieldSx} {...register('fatherName')} />
          </Field>
          <Box sx={{ gridColumn: '1/-1' }}>
            <Typography component="label" sx={labelSx}>Address</Typography>
            <TextField fullWidth sx={fieldSx} multiline rows={3} {...register('address')} />
          </Box>
        </Box>

        <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2.5 }}>
          <Button
            type="submit"
            disabled={saving}
            startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
            sx={{
              // Keeps the shared CTA's copper sweep, but sized as a compact
              // form action rather than the full-width auth-screen button.
              ...ctaButton,
              width: 'auto',
              height: 44,
              px: 2.75,
              borderRadius: '11px',
              fontSize: 12.5,
              letterSpacing: '.2px',
              textTransform: 'none',
              color: '#062017',
              background: 'linear-gradient(96deg,#B07C24 0%,#E8C071 48%,#C08A2E 100%)',
              boxShadow: '0 12px 26px -12px rgba(192,138,46,.75)',
              '&:hover': { background: 'linear-gradient(96deg,#C08A2E 0%,#F3DCAE 48%,#B07C24 100%)' },
            }}
          >
            {saving ? 'Saving…' : 'Save changes'}
          </Button>
        </Box>
      </Box>

      {/* Payment history */}
      <Box
        sx={{
          display: 'flex',
          alignItems: { xs: 'flex-start', sm: 'center' },
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 1.5,
          mb: 1.5,
        }}
      >
        <Typography sx={{ fontSize: 16, fontWeight: 700, letterSpacing: '-.2px', color: tokens.ink }}>
          Payment History
        </Typography>

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75, flexWrap: 'wrap' }}>
          <OutlinedInput
            size="small"
            placeholder="Search payments…"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0) }}
            startAdornment={
              <InputAdornment position="start">
                <SearchIcon sx={{ fontSize: 17, color: tokens.muted }} />
              </InputAdornment>
            }
            sx={{ width: { xs: '100%', sm: 240 }, height: 40, borderRadius: '11px', fontSize: 13 }}
          />
          <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: '#93AC9E' }}>
            Total paid: {formatAmount(totalPaid, totalCurrency)}
          </Typography>
        </Box>
      </Box>

      <Box
        sx={{
          borderRadius: '14px',
          background: 'rgba(11,44,34,.4)',
          border: `1px solid ${tokens.line}`,
          overflow: 'hidden',
        }}
      >
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table sx={{ minWidth: 760 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={thSx}>Invoice</TableCell>
                <TableCell sx={thSx}>Description</TableCell>
                <TableCell sx={thSx}>Date</TableCell>
                <TableCell sx={{ ...thSx, textAlign: 'right' }}>Amount</TableCell>
                <TableCell sx={thSx}>Status</TableCell>
                <TableCell sx={{ ...thSx, textAlign: 'right' }}>Receipt</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {paymentsLoading && (
                <TableRow>
                  <TableCell colSpan={6} sx={{ textAlign: 'center', py: 4, border: 0 }}>
                    <CircularProgress size={22} />
                  </TableCell>
                </TableRow>
              )}
              {!paymentsLoading && filteredPayments.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} sx={{ textAlign: 'center', py: '36px', border: 0, color: tokens.muted, fontSize: 13 }}>
                    {payments.length === 0
                      ? 'No payments recorded yet.'
                      : `No payments match “${search.trim()}”.`}
                  </TableCell>
                </TableRow>
              )}
              {!paymentsLoading && visiblePayments.map((payment) => {
                const tone = toneFor(payment.paymentStatus)
                // The server only issues a receipt once a payment has settled.
                const receiptable = payment.paymentStatus !== 'PENDING'
                return (
                  <TableRow key={payment.transactionId}>
                    <TableCell sx={{ ...tdSx, fontFamily: fonts.mono, fontSize: 12, fontWeight: 700, color: tokens.copperLt }}>
                      {payment.transactionId}
                    </TableCell>
                    <TableCell sx={{ ...tdSx, fontSize: 12.5, color: '#CFE2D8' }}>
                      {payment.description || '—'}
                    </TableCell>
                    <TableCell sx={{ ...tdSx, fontSize: 12.5, color: '#CFE2D8' }}>
                      {formatDate(payment.paymentDate)}
                    </TableCell>
                    <TableCell sx={{ ...tdSx, fontFamily: fonts.mono, fontSize: 12.5, color: tokens.ink, textAlign: 'right' }}>
                      {formatAmount(payment.amount, payment.currency)}
                    </TableCell>
                    <TableCell sx={tdSx}>
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-flex',
                          px: 1.125,
                          py: 0.375,
                          borderRadius: '4px',
                          fontSize: 10.5,
                          fontWeight: 700,
                          letterSpacing: '.03em',
                          color: tone.fg,
                          background: tone.bg,
                          border: `1px solid ${tone.border}`,
                        }}
                      >
                        {tone.label}
                      </Box>
                    </TableCell>
                    <TableCell sx={{ ...tdSx, textAlign: 'right' }}>
                      <Button
                        size="small"
                        disabled={!receiptable || receiptBusy === payment.transactionId}
                        onClick={() => handleDownloadReceipt(payment.transactionId)}
                        startIcon={
                          receiptBusy === payment.transactionId
                            ? <CircularProgress size={12} color="inherit" />
                            : <FileDownloadIcon sx={{ fontSize: 12 }} />
                        }
                        sx={{
                          height: 30,
                          px: 1.5,
                          borderRadius: '8px',
                          fontSize: 11.5,
                          fontWeight: 600,
                          textTransform: 'none',
                          color: '#CFE2D8',
                          background: 'rgba(95,174,146,.08)',
                          border: '1px solid rgba(150,195,172,.24)',
                          '&.Mui-disabled': { color: tokens.muted, opacity: 0.5 },
                        }}
                      >
                        Receipt
                      </Button>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>

        {/* --- pagination --- */}
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1.25,
            flexWrap: 'wrap',
            p: '14px 20px',
            borderTop: `1px solid ${tokens.line}`,
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75, flexWrap: 'wrap' }}>
            <Typography sx={{ fontFamily: fonts.mono, fontSize: 11, color: tokens.muted }}>
              {filteredPayments.length === 0
                ? 'No results'
                : `Showing ${pageStart + 1}-${Math.min(pageStart + rowsPerPage, filteredPayments.length)} of ${filteredPayments.length}`}
            </Typography>
            <Box component="label" sx={{ display: 'flex', alignItems: 'center', gap: 0.9, fontSize: 11.5, color: tokens.muted }}>
              Rows
              <Select
                size="small"
                value={rowsPerPage}
                onChange={(e) => { setRowsPerPage(Number(e.target.value)); setPage(0) }}
                sx={{ height: 28, fontSize: 11.5, fontWeight: 600, borderRadius: '7px' }}
              >
                {ROWS_PER_PAGE_OPTIONS.map((option) => (
                  <MenuItem key={option} value={option}>{option}</MenuItem>
                ))}
              </Select>
            </Box>
          </Box>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <IconButton
              size="small"
              disabled={currentPage === 0}
              onClick={() => setPage(currentPage - 1)}
              aria-label="Previous page"
              sx={{ border: `1.5px solid ${tokens.line2}`, borderRadius: '8px' }}
            >
              <ChevronLeftIcon sx={{ fontSize: 18 }} />
            </IconButton>
            {Array.from({ length: pageCount }, (_, index) => index).map((index) => (
              <Button
                key={index}
                onClick={() => setPage(index)}
                sx={{
                  minWidth: 30,
                  height: 30,
                  p: 0,
                  borderRadius: '8px',
                  fontFamily: fonts.mono,
                  fontSize: 11.5,
                  border: `1.5px solid ${index === currentPage ? 'rgba(192,138,46,.4)' : tokens.line2}`,
                  background: index === currentPage ? 'rgba(192,138,46,.18)' : 'transparent',
                  color: index === currentPage ? tokens.copperLt : tokens.body,
                }}
              >
                {index + 1}
              </Button>
            ))}
            <IconButton
              size="small"
              disabled={currentPage >= pageCount - 1}
              onClick={() => setPage(currentPage + 1)}
              aria-label="Next page"
              sx={{ border: `1.5px solid ${tokens.line2}`, borderRadius: '8px' }}
            >
              <ChevronRightIcon sx={{ fontSize: 18 }} />
            </IconButton>
          </Box>
        </Box>
      </Box>

      <MyTestimonials />
    </Box>
  )
}

export default ProfilePage
