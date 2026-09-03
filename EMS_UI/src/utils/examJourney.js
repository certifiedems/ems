// ems_frontend/src/utils/examJourney.js
//
// One answer to "what does this application need next", shared by every screen
// that offers to move a candidate along.
//
// There were three copies of this rule — the applications list, the dashboard
// table, and the dashboard's journey rail — and they disagreed. Two of them
// read only the application status, which cannot tell a candidate who has just
// paid from one who has paid and booked, because both are IN_PROGRESS. The
// third sent every open application to the payment screen outright, so an
// application that was already paid for offered to charge for it again.

/** Statuses that close an application; the way forward is a re-apply. */
export const CLOSED_STATUSES = ['FAILED', 'TERMINATED', 'EXPIRED', 'REJECTED']

export const isClosedStatus = (status) => CLOSED_STATUSES.includes(status)

/** Statuses that mean the application is still being worked through. */
const OPEN_STATUSES = ['APPLIED', 'ELIGIBLE', 'IN_PROGRESS']

/**
 * Where a booking stands relative to the slot it was made for.
 *
 * The bounds come from the server, which sends them alongside the booked time
 * precisely so this does not have to hold its own copy of the grace period.
 * A row from an older response that carries no bounds is treated as open: the
 * server still has the final say at start, and guessing "closed" here would
 * hide the button from a candidate whose slot is genuinely live.
 *
 * @returns {'OPEN'|'EARLY'|'MISSED'|'UNSCHEDULED'}
 */
export const examWindowState = (app) => {
  if (!app?.scheduledExamTime) {
    return 'UNSCHEDULED'
  }
  const opensAt = app.examWindowStart ? new Date(app.examWindowStart).getTime() : null
  const closesAt = app.examWindowEnd ? new Date(app.examWindowEnd).getTime() : null
  const now = Date.now()

  if (opensAt && now < opensAt) {
    return 'EARLY'
  }
  if (closesAt && now > closesAt) {
    return 'MISSED'
  }
  return 'OPEN'
}

/**
 * Whether the exam itself will still take a booking.
 *
 * The exam carries its own window — the stretch of calendar time in which any
 * slot at all may be booked — which is a different thing from the ten minutes
 * either side of a slot already booked. Once it has closed there is no time
 * left to move to, so every screen that offers to reschedule has to stop
 * offering it: the picker would refuse every date the candidate tried, and the
 * refusals name the date they picked, so it reads as their choice being wrong
 * rather than the window being gone.
 *
 * A row with no bound is treated as open, matching `examWindowState`: the
 * server has the final say, and guessing "closed" here would take the button
 * away from a candidate who could still use it.
 */
export const bookingWindowClosed = (app) => {
  if (!app?.bookingClosesAt) {
    return false
  }
  const closesAt = new Date(app.bookingClosesAt).getTime()
  return Number.isFinite(closesAt) && Date.now() > closesAt
}

/**
 * The single step an application is waiting on.
 *
 * @param {object} app a dashboard `examStatuses` row
 * @returns {{route: string, label: string}|null} null when nothing is pending —
 *          the application has passed, or it is closed and needs a re-apply
 */
export const nextStep = (app) => {
  if (!app) {
    return null
  }
  const { applicationStatus, paymentStatus, applicationId, attemptInProgress, scheduledExamTime } = app

  /*
   * A running attempt goes back to the exam, not to scheduling. The server
   * refuses to schedule once an attempt exists, so sending a candidate who is
   * mid-exam down that path produced an error where they expected their
   * questions back.
   */
  if (attemptInProgress) {
    return { route: `/exam/${applicationId}`, label: 'Resume Exam' }
  }

  if (!OPEN_STATUSES.includes(applicationStatus)) {
    return null
  }

  // Paid is paid, for the life of the application. Nothing here sends a
  // candidate back through payment once this is SUCCESS; only a re-apply, which
  // creates a different application id, starts that step again.
  if (paymentStatus !== 'SUCCESS') {
    return { route: `/exam/payment/${applicationId}`, label: 'Pay & Continue' }
  }

  if (!scheduledExamTime) {
    return { route: `/exam/schedule/${applicationId}`, label: 'Schedule Exam' }
  }

  /*
   * A booking can only be sat inside its window, so outside it the honest next
   * step is the booking itself, not the exam. Offering "Start Exam" for a slot
   * three days out sends the candidate to the launch screen to be refused by
   * the server, when what they can actually do from here is see their slot or
   * move it.
   */
  switch (examWindowState(app)) {
    case 'EARLY':
      return { route: `/exam/schedule/${applicationId}`, label: 'View Booking' }
    case 'MISSED':
      /*
       * "Reschedule Exam" is a promise that a new slot can be had. With the
       * exam's booking window shut it cannot, so the label drops the promise
       * and the screen it leads to explains what happened — the candidate still
       * needs somewhere to go and something to read, just not a button that
       * sends them into a picker with no valid answer in it.
       */
      return bookingWindowClosed(app)
        ? { route: `/exam/schedule/${applicationId}`, label: 'View Booking' }
        : { route: `/exam/schedule/${applicationId}`, label: 'Reschedule Exam' }
    default:
      return { route: `/exam/${applicationId}`, label: 'Start Exam' }
  }
}

/** A window bound as time-of-day only, for "you can start between X and Y". */
export const formatExamClock = (isoString) => {
  if (!isoString) {
    return ''
  }
  const date = new Date(isoString)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

/**
 * A gap in milliseconds as "3 days", "2 hr 5 min", "4 min".
 *
 * Rounded up, matching how the server words the same wait. A screen counting
 * down to the moment its button unlocks must never show a smaller number than
 * the refusal the server would give at that instant, or the candidate clicks on
 * zero and is turned away.
 */
export const formatCountdown = (milliseconds) => {
  const minutes = Math.max(1, Math.ceil(milliseconds / 60000))
  if (minutes < 60) {
    return `${minutes} min`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    const remainder = minutes % 60
    return remainder === 0 ? `${hours} hr` : `${hours} hr ${remainder} min`
  }
  const days = Math.floor(hours / 24)
  const remainingHours = hours % 24
  return remainingHours === 0 ? `${days} day${days === 1 ? '' : 's'}` : `${days}d ${remainingHours}h`
}

/** A booked slot as a short local-time line. */
export const formatExamSlot = (isoString) => {
  if (!isoString) {
    return null
  }
  const date = new Date(isoString)
  return Number.isNaN(date.getTime())
    ? null
    : date.toLocaleString(undefined, {
      day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
    })
}
