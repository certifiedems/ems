// ems_frontend/src/pages/certificate/CertificatePage.jsx
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Alert, Box, Button, Card, InputAdornment, Skeleton, Stack, TextField, Typography,
} from '@mui/material'
import ArrowForwardIcon from '@mui/icons-material/ArrowForwardRounded'
import MedalIcon from '@mui/icons-material/WorkspacePremiumRounded'
import SearchIcon from '@mui/icons-material/SearchRounded'
import VerifiedIcon from '@mui/icons-material/VerifiedRounded'
import { certificateAPI } from '../../api/certificateAPI'
import { getBlobApiErrorMessage } from '../../utils/apiError'
import CertificateDocument from '../../components/certificate/CertificateDocument'
import EmptyState from '../../components/common/EmptyState'
import PageHeader from '../../components/common/PageHeader'
import TestimonialPrompt from '../../components/testimonial/TestimonialPrompt'
import { fonts, tiers, tierForLevel, tokens } from '../../styles/tokens'
import { formatCertificateDate, isExpired, nextExpiry, validityOf } from '../../utils/certificateValidity'

const panelLabel = {
  fontFamily: fonts.mono,
  fontSize: '10px',
  letterSpacing: '1.6px',
  textTransform: 'uppercase',
  color: tokens.muted,
}

/**
 * The rail's surface — flatter than the app's `surface` recipe on purpose: these
 * panels sit beside the certificate card and must not compete with it.
 */
const railPanel = {
  p: 2,
  borderRadius: `${tokens.radius}px`,
  background: 'rgba(11,44,34,.55)',
  border: `1px solid ${tokens.line}`,
}

/** "L2" → 2. Verification returns the level enum but not the level index. */
const levelIndexOf = (certificationLevel) =>
  Number(String(certificationLevel || '').replace(/\D/g, '')) || 1

/**
 * Reads the server's filename for the download.
 *
 * The certificate PDF is produced and named by the backend; taking the name off
 * `Content-Disposition` keeps the saved file identical to the issued artefact
 * instead of a name this page guessed.
 */
const fileNameFromResponse = (response, fallback) => {
  const disposition = response.headers?.['content-disposition']
  const match = disposition && /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return match ? decodeURIComponent(match[1].trim()) : fallback
}

/** One earned credential in the wallet rail. */
const WalletRow = ({ certificate, tier, active, onSelect }) => {
  const validity = validityOf(certificate)
  return (
    <Box
      component="button"
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      title={validity.remaining}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        width: '100%',
        textAlign: 'left',
        cursor: 'pointer',
        font: 'inherit',
        color: tokens.ink,
        p: 2,
        borderRadius: `${tokens.radius}px`,
        background: active ? 'rgba(192,138,46,.10)' : 'rgba(11,44,34,.55)',
        border: `1.5px solid ${active ? 'rgba(192,138,46,.40)' : tokens.line}`,
        transition: 'background .16s, border-color .16s',
        '&:hover': { background: active ? 'rgba(192,138,46,.14)' : 'rgba(14,77,60,.35)' },
      }}
    >
      <Box
        sx={{
          flex: 'none',
          width: 38,
          height: 38,
          borderRadius: '10px',
          display: 'grid',
          placeItems: 'center',
          background: 'rgba(192,138,46,.14)',
          border: `1px solid ${tier.edge}`,
          color: tier.b,
        }}
      >
        <MedalIcon sx={{ fontSize: 19 }} />
      </Box>

      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography noWrap sx={{ fontSize: 13, fontWeight: 700, lineHeight: 1.25 }}>
          {certificate.awardTitle || `${certificate.certificationLevel} Certificate`}
        </Typography>
        <Typography
          noWrap
          sx={{ fontFamily: fonts.mono, fontSize: 10, color: tokens.muted, mt: 0.45 }}
        >
          {certificate.tierLine || `Level ${certificate.levelIndex}`}
        </Typography>
      </Box>

      <Box
        aria-hidden="true"
        sx={{
          flex: 'none',
          width: 6,
          height: 6,
          borderRadius: '50%',
          background: validity.expired ? tokens.danger : tokens.greenLt,
        }}
      />
    </Box>
  )
}

/** One figure in the header's summary cluster, with its hairline separator. */
const HeaderStat = ({ label, value, color, first }) => (
  <>
    {!first && <Box sx={{ width: '1px', height: 34, background: tokens.line2 }} />}
    <Box sx={{ textAlign: 'right' }}>
      <Typography sx={{ ...panelLabel, fontSize: '9.5px', letterSpacing: '.6px', mb: 0.5 }}>
        {label}
      </Typography>
      <Typography sx={{ fontSize: 15, fontWeight: 700, color, lineHeight: 1.2 }}>
        {value}
      </Typography>
    </Box>
  </>
)

const CertificatePage = () => {
  const [certificates, setCertificates] = useState([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState('')
  const [downloading, setDownloading] = useState('')
  const [downloadError, setDownloadError] = useState('')

  // Verification panel
  const [verifyNumber, setVerifyNumber] = useState('')
  const [verifying, setVerifying] = useState(false)
  const [verifyResult, setVerifyResult] = useState(null)
  const [verifyError, setVerifyError] = useState('')

  useEffect(() => {
    let mounted = true
    ;(async () => {
      try {
        const res = await certificateAPI.getCertificates()
        if (mounted) setCertificates(res.data.data || [])
      } catch (err) {
        console.error('Failed to load certificates', err)
      } finally {
        if (mounted) setLoading(false)
      }
    })()
    return () => {
      mounted = false
    }
  }, [])

  // Highest level first: the wallet reads top-down as the ladder descends.
  const ordered = useMemo(
    () => [...certificates].sort((a, b) => (b.levelIndex || 0) - (a.levelIndex || 0)),
    [certificates]
  )

  // Selection is held by certificate number rather than index so it survives a
  // reordered or refreshed list, and falls back when the held one disappears.
  const active = ordered.find((c) => c.certificateNumber === selected) || ordered[0] || null

  const highestHeld = ordered[0]?.levelIndex || 0
  const soonest = useMemo(() => nextExpiry(certificates), [certificates])
  const soonestExpired = soonest ? isExpired(soonest.expiryDate) : false

  const handleDownload = async (certNumber) => {
    setDownloading(certNumber)
    setDownloadError('')
    try {
      const res = await certificateAPI.downloadCertificate(certNumber)
      const url = window.URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }))
      const link = document.createElement('a')
      link.href = url
      link.setAttribute('download', fileNameFromResponse(res, `${certNumber}.pdf`))
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.URL.revokeObjectURL(url)
    } catch (err) {
      console.error('Download failed', err)
      setDownloadError(
        await getBlobApiErrorMessage(err, 'Could not download this certificate. Please try again.')
      )
    } finally {
      setDownloading('')
    }
  }

  const handleVerify = async () => {
    if (!verifyNumber.trim()) return
    setVerifying(true)
    setVerifyError('')
    setVerifyResult(null)
    try {
      const res = await certificateAPI.verifyCertificate(verifyNumber.trim())
      setVerifyResult(res.data.data)
    } catch (err) {
      setVerifyError(err.response?.data?.message || 'No certificate is registered under that number.')
    } finally {
      setVerifying(false)
    }
  }

  const headerMeta = !loading && ordered.length > 0 && (
    <Stack direction="row" spacing={3.5} alignItems="center">
      <HeaderStat
        first
        label="Earned"
        value={ordered.length}
        color={tokens.ink}
      />
      <HeaderStat
        label="Highest tier"
        value={tierForLevel(highestHeld).name}
        color={tierForLevel(highestHeld).b}
      />
      {soonest && (
        <HeaderStat
          label="Next expiry"
          value={formatCertificateDate(soonest.expiryDate)}
          color={soonestExpired ? tokens.danger : '#C9DBD1'}
        />
      )}
    </Stack>
  )

  return (
    <Box>
      <PageHeader
        title="My Certificates"
        subtitle="Download your certificates and verify authenticity."
        action={headerMeta}
      />

      {downloadError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setDownloadError('')}>
          {downloadError}
        </Alert>
      )}

      {!loading && ordered.length > 0 && <TestimonialPrompt />}

      {loading ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '280px minmax(0,1fr)' }, gap: 2.5 }}>
          <Stack spacing={1.25}>
            <Skeleton variant="rounded" height={70} />
            <Skeleton variant="rounded" height={70} />
            <Skeleton variant="rounded" height={190} />
          </Stack>
          <Skeleton variant="rounded" height={420} />
        </Box>
      ) : ordered.length === 0 ? (
        <Card>
          <EmptyState
            icon={<MedalIcon fontSize="large" />}
            title="No certificates yet"
            description="Pass a certification exam to earn your first certificate."
          />
        </Card>
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: '280px minmax(0,1fr)' },
            gap: 2.5,
            alignItems: 'start',
          }}
        >
          {/* Rail — the wallet, then the two jobs that sit beside it. */}
          <Stack spacing={1.25} sx={{ minWidth: 0 }}>
            {/* Below the split the wallet becomes a strip, so the certificate
                card stays the first thing on screen rather than being pushed
                under a stack of rows. */}
            <Box
              sx={{
                display: 'flex',
                flexDirection: { xs: 'row', lg: 'column' },
                gap: 1.25,
                overflowX: { xs: 'auto', lg: 'visible' },
                pb: { xs: 0.5, lg: 0 },
                '& > *': { flex: { xs: '0 0 240px', lg: 'none' } },
              }}
            >
              {ordered.map((certificate) => (
                <WalletRow
                  key={certificate.certificateNumber}
                  certificate={certificate}
                  tier={tierForLevel(certificate.levelIndex)}
                  active={certificate.certificateNumber === active.certificateNumber}
                  onSelect={() => setSelected(certificate.certificateNumber)}
                />
              ))}
            </Box>

            {/* Verification — a different job from the wallet: checking someone
                else's credential. */}
            <Box sx={{ ...railPanel, mt: 0.75 }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.25 }}>
                <VerifiedIcon sx={{ fontSize: 15, color: tokens.greenLt }} />
                <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: tokens.ink }}>
                  Verify a certificate
                </Typography>
              </Stack>

              <TextField
                fullWidth
                size="small"
                placeholder="Certificate number"
                value={verifyNumber}
                onChange={(e) => setVerifyNumber(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleVerify()
                }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon sx={{ fontSize: 15 }} />
                    </InputAdornment>
                  ),
                  sx: { height: 38, borderRadius: '11px', fontFamily: fonts.mono, fontSize: 12.5 },
                }}
              />

              <Button
                fullWidth
                variant="outlined"
                disabled={verifying || !verifyNumber.trim()}
                onClick={handleVerify}
                sx={{
                  mt: 1.25,
                  height: 38,
                  borderRadius: '9px',
                  fontSize: 12,
                  fontWeight: 700,
                  color: '#CFE2D8',
                  background: 'rgba(95,174,146,.10)',
                  borderColor: 'rgba(150,195,172,.28)',
                  borderWidth: 1.5,
                }}
              >
                {verifying ? 'Verifying…' : 'Verify'}
              </Button>

              {verifyError && <VerificationNotice ok={false}>{verifyError}</VerificationNotice>}
              {verifyResult && <VerificationResult result={verifyResult} />}
            </Box>

            <Box sx={railPanel}>
              <Typography sx={{ ...panelLabel, mb: 1.5 }}>Wallet summary</Typography>

              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography sx={{ fontSize: 12.5, color: '#CFE2D8' }}>Total earned</Typography>
                <Typography sx={{ fontFamily: fonts.mono, fontSize: 15, fontWeight: 700, color: tokens.ink }}>
                  {ordered.length}
                </Typography>
              </Stack>

              {soonest && (
                <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mt: 1.5 }}>
                  <Typography sx={{ fontSize: 12.5, color: '#CFE2D8' }}>Next expiry</Typography>
                  <Typography
                    sx={{
                      fontFamily: fonts.mono,
                      fontSize: 12,
                      color: soonestExpired ? tokens.danger : tokens.copper,
                    }}
                  >
                    {formatCertificateDate(soonest.expiryDate)}
                  </Typography>
                </Stack>
              )}

              <Box
                component={Link}
                to="/certifications"
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.75,
                  mt: 1.75,
                  fontSize: 12,
                  fontWeight: 600,
                  color: tokens.copperLt,
                  textDecoration: 'none',
                  '&:hover': { color: '#F3DCAE' },
                }}
              >
                View all certifications
                <ArrowForwardIcon sx={{ fontSize: 13 }} />
              </Box>
            </Box>
          </Stack>

          <CertificateDocument
            certificate={active}
            tier={tierForLevel(active.levelIndex)}
            downloading={downloading === active.certificateNumber}
            onDownload={handleDownload}
          />
        </Box>
      )}
    </Box>
  )
}

/** The tinted box a verification answers into — valid or not. */
const VerificationNotice = ({ ok, children }) => (
  <Box
    sx={{
      mt: 1.5,
      p: '10px 12px',
      borderRadius: '9px',
      background: ok ? 'rgba(63,211,160,.08)' : 'rgba(248,113,113,.08)',
      border: `1px solid ${ok ? 'rgba(63,211,160,.35)' : 'rgba(248,113,113,.35)'}`,
      fontSize: 11.5,
      color: ok ? '#CFE2D8' : '#FFCFCF',
    }}
  >
    {children}
  </Box>
)

/**
 * A verified certificate.
 *
 * Only the fields the verification endpoint actually returns are rendered — it
 * answers with the holder, level and dates but no award title, so none is
 * invented here.
 */
const VerificationResult = ({ result }) => {
  const valid = result.verificationStatus === 'VALID'
  const tier = valid ? tierForLevel(levelIndexOf(result.certificationLevel)) : tiers.silver

  return (
    <VerificationNotice ok={valid}>
      <Stack direction="row" spacing={0.875} alignItems="center" sx={{ mb: 0.75 }}>
        <Box
          sx={{
            width: 5,
            height: 5,
            borderRadius: '50%',
            background: valid ? tokens.greenGlow : tokens.danger,
          }}
        />
        <Typography
          component="span"
          sx={{
            fontFamily: fonts.mono,
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '.6px',
            textTransform: 'uppercase',
            color: valid ? tokens.greenGlow : tokens.danger,
          }}
        >
          {String(result.verificationStatus).replace(/_/g, ' ')}
        </Typography>
      </Stack>

      <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: tokens.ink }}>
        {result.candidateName}
      </Typography>

      <Typography sx={{ fontFamily: fonts.mono, fontSize: 10, color: tokens.body, lineHeight: 1.7, mt: 0.4 }}>
        {tier.name} · {result.certificationLevel}
        <br />
        {result.certificateNumber}
        {result.expiryDate && (
          <>
            <br />
            Issued {formatCertificateDate(result.issueDate)} · Valid through{' '}
            {formatCertificateDate(result.expiryDate)}
          </>
        )}
      </Typography>

      {result.message && (
        <Typography sx={{ fontSize: 11.5, color: tokens.body, mt: 0.75 }}>{result.message}</Typography>
      )}
    </VerificationNotice>
  )
}

export default CertificatePage
