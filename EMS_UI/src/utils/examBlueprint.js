// Mirrors com.ems.util.ExamQuestionBlueprint so the admin screen can preview
// the paper it is about to save.
//
// The split has to be worked out somewhere for the preview to say anything
// useful, and the server is the one that will actually build the paper — so
// this file exists to agree with it, not to decide anything of its own. Change
// one and change the other, or the admin will be shown a 9/12/9 paper and get
// a 10/12/8 one.

export const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH']

export const SEVERITY_LABELS = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' }

export const DEFAULT_BLUEPRINT = {
  totalQuestions: 30,
  lowSeverityPercentage: 20,
  mediumSeverityPercentage: 40,
  highSeverityPercentage: 40,
}

export const PERCENTAGE_FIELD = {
  LOW: 'lowSeverityPercentage',
  MEDIUM: 'mediumSeverityPercentage',
  HIGH: 'highSeverityPercentage',
}

/**
 * How many questions each severity contributes, given the mix and the total.
 *
 * Percentages rarely divide a total into whole questions, so the remainder
 * after flooring goes to the severities that lost the most to it — largest
 * fractional part first, ties broken by severity order. The counts therefore
 * always add up to `totalQuestions` exactly, which is the property the preview
 * is claiming when it shows a breakdown.
 */
export const severityCounts = (blueprint) => {
  const total = Math.trunc(Number(blueprint?.totalQuestions))
  if (!Number.isFinite(total) || total <= 0) {
    return { LOW: 0, MEDIUM: 0, HIGH: 0 }
  }

  const counts = {}
  const remainders = {}
  let allocated = 0

  SEVERITIES.forEach((severity) => {
    const percentage = Number(blueprint?.[PERCENTAGE_FIELD[severity]])
    /*
     * Integers only, in ten-thousandths of a question. The share works out to
     * `percentage * total / 100`, and doing that in floating point puts a mix
     * like 70% of 2 questions at 1.3999999999999999 instead of 1.4 -- which is
     * invisible in the count itself but flips the ranking below, handing the
     * spare question to a different severity than the server does. Scaling the
     * percentage to the hundredths the column actually stores keeps every
     * comparison exact.
     */
    const hundredths = Number.isFinite(percentage) ? Math.round(percentage * 100) : 0
    const scaled = hundredths * total
    const floor = Math.floor(scaled / 10000)

    counts[severity] = floor
    remainders[severity] = scaled - floor * 10000
    allocated += floor
  })

  const byRemainder = [...SEVERITIES].sort((a, b) => {
    if (remainders[b] !== remainders[a]) return remainders[b] - remainders[a]
    return SEVERITIES.indexOf(a) - SEVERITIES.indexOf(b)
  })

  for (let i = 0; allocated < total; i += 1, allocated += 1) {
    const severity = byRemainder[i % byRemainder.length]
    counts[severity] += 1
  }

  return counts
}

/** The three shares added up; 100 for a mix the server will accept. */
export const percentageTotal = (blueprint) =>
  SEVERITIES.reduce((sum, severity) => {
    const value = Number(blueprint?.[PERCENTAGE_FIELD[severity]])
    return sum + (Number.isFinite(value) ? value : 0)
  }, 0)

export const isMixBalanced = (blueprint) => Math.abs(percentageTotal(blueprint) - 100) < 0.001
