// ems_frontend/src/config/support.js
//
// The inbox candidates are pointed at whenever they need a person. Kept in one
// place so every screen that says "contact support" names the same address.

export const SUPPORT_EMAIL = 'info@certifiedemsengineers.com'

/** `mailto:` link to the support inbox, with the subject line pre-filled. */
export const supportMailto = (subject = 'Certified EMS support request') =>
  `mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent(subject)}`
