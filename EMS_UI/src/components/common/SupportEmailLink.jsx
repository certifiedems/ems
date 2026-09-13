// ems_frontend/src/components/common/SupportEmailLink.jsx
import PropTypes from 'prop-types'
import { Box } from '@mui/material'
import { SUPPORT_EMAIL, supportMailto } from '../../config/support'
import { tokens } from '../../styles/tokens'

/**
 * The support address as a copper `mailto:` link. Takes its size from the text
 * around it so it can sit inside a sentence; `subject` pre-fills the email so
 * it arrives already saying what it is about.
 */
const SupportEmailLink = ({ subject, sx }) => (
  <Box
    component="a"
    href={supportMailto(subject)}
    sx={{
      fontWeight: 700,
      color: tokens.copperLt,
      textDecoration: 'none',
      // An address has no spaces to break at; without this it pushes the narrow
      // sidebar or a phone-width card wider than its container.
      overflowWrap: 'anywhere',
      '&:hover': { textDecoration: 'underline' },
      ...sx,
    }}
  >
    {SUPPORT_EMAIL}
  </Box>
)

SupportEmailLink.propTypes = {
  subject: PropTypes.string,
  sx: PropTypes.object,
}

export default SupportEmailLink
