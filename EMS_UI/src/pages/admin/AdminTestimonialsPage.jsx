// ems_frontend/src/pages/admin/AdminTestimonialsPage.jsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import PropTypes from 'prop-types'
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, Paper, Rating,
  Skeleton, Stack, Tab, Tabs, Typography,
} from '@mui/material'
import CheckIcon from '@mui/icons-material/CheckRounded'
import BlockIcon from '@mui/icons-material/BlockRounded'
import EditIcon from '@mui/icons-material/EditRounded'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import StatusChip from '../../components/common/StatusChip'
import PcbField from '../../components/common/PcbField'
import { QUOTE_MAX } from '../../components/testimonial/TestimonialDialog'
import { getApiErrorMessage } from '../../utils/apiError'
import { tokens, fonts, tone } from '../../styles/tokens'

const TABS = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Published' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All' },
]

const formatDate = (value) => (value ? new Date(value).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' }) : '—')

/** "Priya S., Line Technician, Acme" — the attribution line the public card shows. */
const attribution = (t) =>
  [t.publicName || 'Anonymous', t.jobTitle, t.company].filter(Boolean).join(', ')

const TestimonialCard = ({ t, busy, onApprove, onReject, onEdit }) => (
  <Paper sx={{ p: 2.5 }}>
    <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap' }}>
      <Box sx={{ minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Typography sx={{ fontWeight: 700, color: tokens.ink }}>{t.candidateName}</Typography>
          <Typography sx={{ fontFamily: fonts.mono, fontSize: 12, color: tokens.muted }}>{t.candidateUserId}</Typography>
          <Typography sx={{ fontFamily: fonts.mono, fontSize: 12.5, color: tokens.copperLt }}>{t.certificationLevel}</Typography>
          <StatusChip status={t.status} />
        </Box>
        <Typography sx={{ mt: 0.25, fontSize: 12.5, color: tokens.muted }}>
          {t.candidateEmail} · submitted {formatDate(t.submittedAt)}
        </Typography>
      </Box>
      <Rating value={t.rating} readOnly size="small" sx={{ color: tokens.copperLt, '& .MuiRating-iconEmpty': { color: tokens.line2 } }} />
    </Box>

    {t.certificationStatus === 'REVOKED' && (
      <Alert severity="warning" sx={{ mt: 1.5 }}>
        This candidate&apos;s {t.certificationLevel} certification was revoked, so this quote is never shown publicly.
      </Alert>
    )}

    <Typography sx={{ mt: 1.75, fontSize: 15, lineHeight: 1.65, fontStyle: 'italic', color: '#CFE2D8' }}>
      &ldquo;{t.editedQuote || t.quote}&rdquo;
    </Typography>
    {t.editedQuote && (
      <Typography sx={{ mt: 0.75, fontSize: 12.5, color: tokens.muted }}>
        Original: &ldquo;{t.quote}&rdquo;
      </Typography>
    )}
    <Typography sx={{ mt: 1, fontSize: 13, color: tokens.body }}>— {attribution(t)}</Typography>

    {(t.reviewNote || t.reviewedBy) && (
      <Typography sx={{ mt: 1.25, fontSize: 12.5, color: tokens.muted }}>
        {t.reviewedBy && <>Reviewed by {t.reviewedBy} on {formatDate(t.reviewedAt)}. </>}
        {t.reviewNote && <>Note to candidate: {t.reviewNote}</>}
      </Typography>
    )}

    <Stack direction="row" spacing={1} sx={{ mt: 2, flexWrap: 'wrap', rowGap: 1 }}>
      {t.status !== 'APPROVED' && (
        <Button size="small" variant="contained" startIcon={<CheckIcon />} disabled={busy} onClick={() => onApprove(t)} sx={{ textTransform: 'none' }}>
          Approve
        </Button>
      )}
      {t.status !== 'REJECTED' && (
        <Button size="small" variant="outlined" color="error" startIcon={<BlockIcon />} disabled={busy} onClick={() => onReject(t)} sx={{ textTransform: 'none' }}>
          {t.status === 'APPROVED' ? 'Unpublish' : 'Reject'}
        </Button>
      )}
      <Button size="small" startIcon={<EditIcon />} disabled={busy} onClick={() => onEdit(t)} sx={{ textTransform: 'none' }}>
        Fix wording
      </Button>
    </Stack>
  </Paper>
)

TestimonialCard.propTypes = {
  t: PropTypes.object.isRequired,
  busy: PropTypes.bool.isRequired,
  onApprove: PropTypes.func.isRequired,
  onReject: PropTypes.func.isRequired,
  onEdit: PropTypes.func.isRequired,
}

/**
 * The review queue for candidate testimonials. Approving publishes a quote to
 * the sign-in screen; rejecting or unpublishing takes it off. "Fix wording" is
 * for typos only: the candidate's original is kept and shown alongside.
 */
const AdminTestimonialsPage = () => {
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState('PENDING')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyId, setBusyId] = useState(null)
  const [rejecting, setRejecting] = useState(null)
  const [rejectNote, setRejectNote] = useState('')
  const [editing, setEditing] = useState(null)
  const [editText, setEditText] = useState('')

  const load = useCallback(async () => {
    try {
      const response = await adminAPI.getTestimonials()
      setItems(response.data?.data || [])
    } catch (err) {
      setError(getApiErrorMessage(err, 'Could not load testimonials.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const counts = useMemo(() => items.reduce((acc, t) => ({ ...acc, [t.status]: (acc[t.status] || 0) + 1 }), {}), [items])
  const visible = tab === 'ALL' ? items : items.filter((t) => t.status === tab)

  const replace = (updated) => setItems((current) => current.map((t) => (t.id === updated.id ? updated : t)))

  const run = async (id, request) => {
    setBusyId(id)
    setError('')
    try {
      const response = await request()
      replace(response.data.data)
      setNotice(response.data.message)
      return true
    } catch (err) {
      setError(getApiErrorMessage(err, 'That did not work. Please try again.'))
      return false
    } finally {
      setBusyId(null)
    }
  }

  const approve = (t) => run(t.id, () => adminAPI.reviewTestimonial(t.id, { status: 'APPROVED' }))

  const confirmReject = async () => {
    const ok = await run(rejecting.id, () =>
      adminAPI.reviewTestimonial(rejecting.id, { status: 'REJECTED', reviewNote: rejectNote.trim() || null }))
    if (ok) setRejecting(null)
  }

  const saveEdit = async (text) => {
    const ok = await run(editing.id, () => adminAPI.editTestimonialQuote(editing.id, text))
    if (ok) setEditing(null)
  }

  const editLength = editText.trim().length

  return (
    <Box>
      <PageHeader
        title="Testimonials"
        subtitle="Review what certified candidates say before it appears on the sign-in screen."
      />

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>{error}</Alert>}
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice('')}>{notice}</Alert>}

      <Tabs value={tab} onChange={(_, value) => setTab(value)} sx={{ mb: 2 }} variant="scrollable" allowScrollButtonsMobile>
        {TABS.map((t) => (
          <Tab
            key={t.value}
            value={t.value}
            sx={{ textTransform: 'none' }}
            label={`${t.label} (${t.value === 'ALL' ? items.length : counts[t.value] || 0})`}
          />
        ))}
      </Tabs>

      {loading ? (
        <Stack spacing={1.5}>
          <Skeleton variant="rounded" height={170} />
          <Skeleton variant="rounded" height={170} />
        </Stack>
      ) : visible.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography sx={{ color: tokens.body }}>
            {tab === 'PENDING' ? 'Nothing waiting for review.' : 'No testimonials here yet.'}
          </Typography>
        </Paper>
      ) : (
        <Stack spacing={1.5}>
          {visible.map((t) => (
            <TestimonialCard
              key={t.id}
              t={t}
              busy={busyId === t.id}
              onApprove={approve}
              onReject={(item) => { setRejecting(item); setRejectNote(item.reviewNote || '') }}
              onEdit={(item) => { setEditing(item); setEditText(item.editedQuote || item.quote) }}
            />
          ))}
        </Stack>
      )}

      <Dialog open={Boolean(rejecting)} onClose={() => !busyId && setRejecting(null)} fullWidth PaperProps={{ sx: { maxWidth: 480 } }}>
        <DialogContent sx={{ pt: 3 }}>
          <Typography sx={{ fontSize: 17, fontWeight: 800, mb: 0.5 }}>
            {rejecting?.status === 'APPROVED' ? 'Unpublish this testimonial?' : 'Reject this testimonial?'}
          </Typography>
          <Typography sx={{ fontSize: 13, color: tokens.body, mb: 2 }}>
            It will not appear on the site. The candidate sees the note below on their profile.
          </Typography>
          <PcbField
            label="Note to candidate (optional)"
            multiline
            rows={3}
            maxLength={300}
            placeholder="e.g. Please remove the phone number from your quote and submit again."
            value={rejectNote}
            onChange={(e) => setRejectNote(e.target.value)}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setRejecting(null)} disabled={Boolean(busyId)} sx={{ textTransform: 'none' }}>Cancel</Button>
          <Button color="error" variant="contained" onClick={confirmReject} disabled={Boolean(busyId)} sx={{ textTransform: 'none' }}>
            {busyId ? <CircularProgress size={16} color="inherit" /> : rejecting?.status === 'APPROVED' ? 'Unpublish' : 'Reject'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={Boolean(editing)} onClose={() => !busyId && setEditing(null)} fullWidth PaperProps={{ sx: { maxWidth: 560 } }}>
        <DialogContent sx={{ pt: 3 }}>
          <Typography sx={{ fontSize: 17, fontWeight: 800, mb: 0.5 }}>Fix wording</Typography>
          <Typography sx={{ fontSize: 13, color: tokens.body, mb: 2 }}>
            Correct spelling and grammar only. Never change what the candidate meant. Their original is kept.
          </Typography>
          <Box sx={{ mb: 2, p: 1.5, borderRadius: '12px', background: tone.neutral.bg, border: `1px solid ${tone.neutral.border}` }}>
            <Typography sx={{ fontFamily: fonts.mono, fontSize: 10.5, letterSpacing: '1.2px', color: tokens.muted, mb: 0.5 }}>ORIGINAL</Typography>
            <Typography sx={{ fontSize: 13.5, color: '#CFE2D8' }}>{editing?.quote}</Typography>
          </Box>
          <PcbField
            label="Published wording"
            multiline
            rows={4}
            maxLength={QUOTE_MAX}
            value={editText}
            onChange={(e) => setEditText(e.target.value)}
          />
          <Typography sx={{ mt: -1, textAlign: 'right', fontFamily: fonts.mono, fontSize: 11, color: editLength > QUOTE_MAX ? tokens.danger : tokens.muted }}>
            {editLength} / {QUOTE_MAX}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          {editing?.editedQuote && (
            <Button onClick={() => saveEdit(null)} disabled={Boolean(busyId)} sx={{ mr: 'auto', textTransform: 'none' }}>
              Restore original
            </Button>
          )}
          <Button onClick={() => setEditing(null)} disabled={Boolean(busyId)} sx={{ textTransform: 'none' }}>Cancel</Button>
          <Button
            variant="contained"
            onClick={() => saveEdit(editText.trim())}
            disabled={Boolean(busyId) || editLength === 0 || editLength > QUOTE_MAX}
            sx={{ textTransform: 'none' }}
          >
            {busyId ? <CircularProgress size={16} color="inherit" /> : 'Save wording'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

export default AdminTestimonialsPage
