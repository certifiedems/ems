// ems_frontend/src/utils/violationReport.js
import { jsPDF } from 'jspdf'
import { toAscii } from './syllabusPdf'

/*
 * Violation Management exports. Both formats are built from the rows the admin
 * is looking at (filtered and sorted, every page of them), so a download is
 * always exactly the table on screen.
 */

export const APP_NAME = 'Certified EMS Engineers'
const APP_URL = 'www.certifiedemsengineers.com'
const REPORT_TITLE = 'Violation Report'

const PAGE = { width: 841.89, height: 595.28 } // A4 landscape, points
const MARGIN = 32
const CONTENT_WIDTH = PAGE.width - MARGIN * 2
const FOOTER_Y = PAGE.height - 18
const BODY_BOTTOM = PAGE.height - 40

// The PCB palette from src/styles/tokens.js, lightened where it has to sit on
// white paper.
const COLORS = {
  board: [6, 26, 19],
  green: [14, 77, 60],
  greenLt: [95, 174, 146],
  copper: [192, 138, 46],
  copperLt: [232, 192, 113],
  ink: [17, 24, 39],
  body: [55, 65, 81],
  muted: [107, 114, 128],
  rule: [222, 230, 226],
  zebra: [245, 249, 247],
  tile: [241, 247, 244],
  track: [229, 237, 233],
  danger: [190, 40, 40],
  dangerBg: [253, 228, 228],
  warn: [168, 110, 16],
  warnBg: [253, 243, 221],
  success: [21, 128, 61],
  successBg: [220, 245, 230],
  neutralBg: [236, 239, 243],
  white: [255, 255, 255]
}

export const humanize = (value) => (value ? String(value).replace(/_/g, ' ') : '')

const formatDate = (value) =>
  new Date(value).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })

const formatTime = (value) =>
  new Date(value).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })

const formatDateTime = (value) => (value ? `${formatDate(value)} ${formatTime(value)}` : '')

const fileStamp = () => {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const statusTone = (status) => {
  if (status === 'COMPLETED' || status === 'PASSED') return { fill: COLORS.successBg, color: COLORS.success }
  if (status === 'FAILED' || status === 'INVALIDATED' || status === 'TERMINATED') {
    return { fill: COLORS.dangerBg, color: COLORS.danger }
  }
  if (status === 'IN_PROGRESS') return { fill: COLORS.warnBg, color: COLORS.warn }
  return { fill: COLORS.neutralBg, color: COLORS.body }
}

const actionTone = (action) => {
  if (action === 'EXAM_TERMINATED') return { fill: COLORS.dangerBg, color: COLORS.danger }
  if (action === 'FLAGGED_FOR_REVIEW') return { fill: COLORS.warnBg, color: COLORS.warn }
  return { fill: COLORS.neutralBg, color: COLORS.body }
}

// — Table model

const PAD_X = 5
const PAD_Y = 5
const LINE_HEIGHT = 9.4
const BADGE_HEIGHT = 12
const MAX_DESCRIPTION_LINES = 8

const text = (value, style = 'normal') => ({ text: value == null || value === '' ? '-' : toAscii(value), style })

const COLUMNS = [
  { label: '#', width: 22, cell: (v, i) => [text(i + 1, 'muted')] },
  {
    label: 'Detected',
    width: 66,
    cell: (v) => (v.detectedAt ? [text(formatDate(v.detectedAt), 'bold'), text(formatTime(v.detectedAt), 'muted')] : [text('')])
  },
  {
    label: 'Candidate',
    width: 124,
    cell: (v) => [text(v.candidateName, 'bold'), text(v.userId, 'muted'), text(v.candidateEmail, 'muted')]
  },
  {
    label: 'Exam',
    width: 104,
    cell: (v) => [
      text(v.examCode, 'bold'),
      text(v.examName),
      text(v.certificationLevel ? `Level ${v.certificationLevel}` : '', 'muted')
    ]
  },
  {
    label: 'Session',
    width: 56,
    cell: (v) => [text(`#${v.sessionId}`, 'bold'), text(v.applicationId ? `App #${v.applicationId}` : 'No app', 'muted')]
  },
  { label: 'Status', width: 60, badge: (v) => ({ label: humanize(v.sessionStatus) || 'UNKNOWN', ...statusTone(v.sessionStatus) }) },
  {
    label: 'Violation',
    width: 96,
    cell: (v) => [text(humanize(v.violationType), 'bold'), text(v.violationLevel == null ? '' : `Level ${v.violationLevel}`, 'muted')]
  },
  {
    label: 'Description & policy',
    width: null, // takes whatever the fixed columns leave
    cell: (v) => [text(v.description), text(v.policyMessage, 'muted')],
    maxLines: MAX_DESCRIPTION_LINES
  },
  { label: 'Action', width: 86, badge: (v) => ({ label: humanize(v.actionTaken) || '-', ...actionTone(v.actionTaken) }) }
]

const fixedWidth = COLUMNS.reduce((sum, column) => sum + (column.width || 0), 0)
COLUMNS.forEach((column) => {
  if (!column.width) column.width = CONTENT_WIDTH - fixedWidth
})

const FONT = {
  bold: { style: 'bold', size: 7.8, color: COLORS.ink },
  normal: { style: 'normal', size: 7.4, color: COLORS.body },
  muted: { style: 'normal', size: 7, color: COLORS.muted }
}

const applyFont = (doc, key) => {
  const font = FONT[key]
  doc.setFont('helvetica', font.style)
  doc.setFontSize(font.size)
  doc.setTextColor(...font.color)
}

/** Wraps every cell of a row without drawing, so a row never splits across pages. */
const layoutRow = (doc, violation, index) => {
  let tallest = BADGE_HEIGHT
  const cells = COLUMNS.map((column) => {
    if (column.badge) return { badge: column.badge(violation) }
    const innerWidth = column.width - PAD_X * 2
    let lines = []
    column.cell(violation, index).forEach((part) => {
      applyFont(doc, part.style)
      doc.splitTextToSize(part.text, innerWidth).forEach((wrapped) => lines.push({ text: wrapped, style: part.style }))
    })
    if (column.maxLines && lines.length > column.maxLines) {
      lines = lines.slice(0, column.maxLines)
      const last = lines[lines.length - 1]
      last.text = `${last.text.replace(/\s*\S{0,3}$/, '')}...`
    }
    tallest = Math.max(tallest, lines.length * LINE_HEIGHT)
    return { lines }
  })
  return { cells, height: tallest + PAD_Y * 2 }
}

const drawBadge = (doc, badge, x, y, maxWidth) => {
  const label = toAscii(badge.label)
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(6.6)
  // Long labels shrink to fit one line rather than wrapping out of the pill.
  const natural = doc.getTextWidth(label)
  if (natural + 10 > maxWidth) doc.setFontSize((6.6 * (maxWidth - 10)) / natural)
  const width = Math.min(doc.getTextWidth(label) + 10, maxWidth)
  doc.setFillColor(...badge.fill)
  doc.roundedRect(x, y, width, BADGE_HEIGHT, 6, 6, 'F')
  doc.setTextColor(...badge.color)
  doc.text(label, x + width / 2, y + 8.3, { align: 'center' })
}

const drawRow = (doc, layout, y, striped) => {
  if (striped) {
    doc.setFillColor(...COLORS.zebra)
    doc.rect(MARGIN, y, CONTENT_WIDTH, layout.height, 'F')
  }
  let x = MARGIN
  layout.cells.forEach((cell, i) => {
    const column = COLUMNS[i]
    if (cell.badge) {
      drawBadge(doc, cell.badge, x + PAD_X, y + PAD_Y - 1, column.width - PAD_X * 2)
    } else {
      cell.lines.forEach((line, n) => {
        applyFont(doc, line.style)
        doc.text(line.text, x + PAD_X, y + PAD_Y + 6.8 + n * LINE_HEIGHT)
      })
    }
    x += column.width
  })
  doc.setDrawColor(...COLORS.rule)
  doc.setLineWidth(0.5)
  doc.line(MARGIN, y + layout.height, MARGIN + CONTENT_WIDTH, y + layout.height)
}

const drawTableHeader = (doc, y) => {
  const height = 20
  doc.setFillColor(...COLORS.green)
  doc.rect(MARGIN, y, CONTENT_WIDTH, height, 'F')
  doc.setFillColor(...COLORS.copper)
  doc.rect(MARGIN, y + height, CONTENT_WIDTH, 1.5, 'F')

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(7)
  doc.setTextColor(...COLORS.white)
  let x = MARGIN
  COLUMNS.forEach((column) => {
    doc.text(column.label.toUpperCase(), x + PAD_X, y + 13)
    x += column.width
  })
  return y + height + 1.5
}

// — Branding

/** The chip-package brand mark, redrawn with vector primitives. */
const drawLogo = (doc, x, y, size) => {
  const s = size / 120
  doc.setFillColor(...COLORS.copper)
  ;[26, 50, 74].forEach((offset) => {
    doc.roundedRect(x + offset * s, y, 9 * s, 15 * s, 1, 1, 'F')
    doc.roundedRect(x + offset * s, y + 105 * s, 9 * s, 15 * s, 1, 1, 'F')
    doc.roundedRect(x - 6 * s, y + offset * s + 4 * s, 15 * s, 9 * s, 1, 1, 'F')
    doc.roundedRect(x + 100 * s, y + offset * s + 4 * s, 15 * s, 9 * s, 1, 1, 'F')
  })
  doc.setFillColor(...COLORS.green)
  doc.setDrawColor(...COLORS.greenLt)
  doc.setLineWidth(1)
  doc.roundedRect(x + 7 * s, y + 13 * s, 95 * s, 94 * s, 11 * s, 11 * s, 'FD')
  doc.setDrawColor(...COLORS.copperLt)
  doc.setLineWidth(8 * s)
  doc.setLineCap('square')
  doc.line(x + 30 * s, y + 62 * s, x + 46 * s, y + 78 * s)
  doc.line(x + 46 * s, y + 78 * s, x + 78 * s, y + 40 * s)
  doc.setLineCap('butt')
  doc.setFillColor(...COLORS.copper)
  doc.circle(x + 22 * s, y + 27 * s, 5 * s, 'F')
}

/** Full masthead on the first page. Returns the y cursor below it. */
const drawMasthead = (doc, meta) => {
  const height = 84
  doc.setFillColor(...COLORS.board)
  doc.rect(0, 0, PAGE.width, height, 'F')
  doc.setFillColor(...COLORS.copper)
  doc.rect(0, height, PAGE.width, 3, 'F')

  drawLogo(doc, MARGIN + 4, 18, 46)

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(16)
  doc.setTextColor(...COLORS.copperLt)
  doc.text(APP_NAME.toUpperCase(), MARGIN + 62, 38, { charSpace: 0.6 })
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(7.5)
  doc.setTextColor(...COLORS.greenLt)
  doc.text(APP_URL.toUpperCase(), MARGIN + 62, 51, { charSpace: 1 })
  doc.setFontSize(8)
  doc.setTextColor(...COLORS.white)
  doc.text('Proctoring integrity - admin console', MARGIN + 62, 64)

  const right = PAGE.width - MARGIN
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(17)
  doc.setTextColor(...COLORS.white)
  doc.text(REPORT_TITLE, right, 34, { align: 'right' })
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(8.5)
  doc.setTextColor(...COLORS.greenLt)
  doc.text(toAscii(`Prepared by: ${meta.preparedBy}`), right, 50, { align: 'right' })
  doc.text(toAscii(`Generated: ${formatDateTime(meta.generatedAt)}`), right, 63, { align: 'right' })

  return height + 3 + 18
}

/** Slim masthead on continuation pages. Returns the y cursor below it. */
const drawRunningHead = (doc) => {
  const height = 34
  doc.setFillColor(...COLORS.board)
  doc.rect(0, 0, PAGE.width, height, 'F')
  doc.setFillColor(...COLORS.copper)
  doc.rect(0, height, PAGE.width, 2, 'F')
  drawLogo(doc, MARGIN + 2, 7, 20)
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(10)
  doc.setTextColor(...COLORS.copperLt)
  doc.text(APP_NAME.toUpperCase(), MARGIN + 30, 21.5, { charSpace: 0.4 })
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(8.5)
  doc.setTextColor(...COLORS.white)
  doc.text(`${REPORT_TITLE} - continued`, PAGE.width - MARGIN, 21.5, { align: 'right' })
  return height + 2 + 14
}

const drawSectionTitle = (doc, label, y) => {
  doc.setFillColor(...COLORS.copper)
  doc.rect(MARGIN, y - 7, 3, 9, 'F')
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(8.5)
  doc.setTextColor(...COLORS.green)
  doc.text(label.toUpperCase(), MARGIN + 9, y, { charSpace: 0.8 })
  return y + 10
}

// — Overview sections

const drawScope = (doc, meta, y) => {
  y = drawSectionTitle(doc, 'Report scope', y)
  const lines = [
    ['Filters', meta.filters.length ? meta.filters.join('   /   ') : 'None - every recorded violation'],
    ['Sorted by', meta.sortLabel],
    ['Records', `${meta.rowCount} of ${meta.totalCount} recorded violations`]
  ]
  const valueX = MARGIN + 70
  const valueWidth = CONTENT_WIDTH - 82
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(8)
  const wrapped = lines.map(([label, value]) => [label, doc.splitTextToSize(toAscii(value), valueWidth)])
  const height = wrapped.reduce((sum, [, value]) => sum + value.length * 11, 0) + 12

  doc.setFillColor(...COLORS.tile)
  doc.roundedRect(MARGIN, y, CONTENT_WIDTH, height, 4, 4, 'F')
  let cursor = y + 14
  wrapped.forEach(([label, value]) => {
    doc.setFont('helvetica', 'bold')
    doc.setTextColor(...COLORS.muted)
    doc.text(label.toUpperCase(), MARGIN + 10, cursor)
    doc.setFont('helvetica', 'normal')
    doc.setTextColor(...COLORS.ink)
    doc.text(value, valueX, cursor)
    cursor += value.length * 11
  })
  return y + height + 16
}

const summarise = (rows) => {
  const distinct = (pick) => new Set(rows.map(pick).filter((value) => value != null)).size
  const byAction = (action) => rows.filter((v) => v.actionTaken === action).length
  return [
    { label: 'Violations', value: rows.length, color: COLORS.ink, accent: COLORS.copper },
    { label: 'Exams terminated', value: byAction('EXAM_TERMINATED'), color: COLORS.danger, accent: COLORS.danger },
    { label: 'Flagged for review', value: byAction('FLAGGED_FOR_REVIEW'), color: COLORS.warn, accent: COLORS.warn },
    { label: 'Warnings issued', value: byAction('WARNING'), color: COLORS.ink, accent: COLORS.greenLt },
    { label: 'Candidates', value: distinct((v) => v.userId), color: COLORS.ink, accent: COLORS.green },
    { label: 'Exam sessions', value: distinct((v) => v.sessionId), color: COLORS.ink, accent: COLORS.green }
  ]
}

const drawSummaryTiles = (doc, rows, y) => {
  y = drawSectionTitle(doc, 'Summary', y)
  const tiles = summarise(rows)
  const gap = 8
  const width = (CONTENT_WIDTH - gap * (tiles.length - 1)) / tiles.length
  const height = 44
  tiles.forEach((tile, i) => {
    const x = MARGIN + i * (width + gap)
    doc.setFillColor(...COLORS.tile)
    doc.roundedRect(x, y, width, height, 4, 4, 'F')
    doc.setFillColor(...tile.accent)
    doc.rect(x, y + 6, 2.5, height - 12, 'F')
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(6.8)
    doc.setTextColor(...COLORS.muted)
    doc.text(tile.label.toUpperCase(), x + 10, y + 14, { charSpace: 0.4 })
    doc.setFontSize(17)
    doc.setTextColor(...tile.color)
    doc.text(String(tile.value), x + 10, y + 35)
  })
  return y + height + 18
}

const TYPE_ROWS_PER_COLUMN = 4

const drawTypeBreakdown = (doc, rows, y) => {
  if (rows.length === 0) return y
  y = drawSectionTitle(doc, 'Violations by type', y)

  const counts = rows.reduce((acc, v) => {
    const key = humanize(v.violationType) || 'Unknown'
    acc.set(key, (acc.get(key) || 0) + 1)
    return acc
  }, new Map())
  let entries = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  const slots = TYPE_ROWS_PER_COLUMN * 2
  if (entries.length > slots) {
    const other = entries.slice(slots - 1).reduce((sum, [, count]) => sum + count, 0)
    entries = [...entries.slice(0, slots - 1), [`Other (${entries.length - slots + 1} types)`, other]]
  }
  // "Other" can outnumber any single type, so scale to the largest bar drawn.
  const max = Math.max(...entries.map(([, count]) => count))
  const columnWidth = (CONTENT_WIDTH - 24) / 2
  const labelWidth = 150
  const countWidth = 58
  const trackWidth = columnWidth - labelWidth - countWidth

  entries.forEach(([label, count], i) => {
    const x = MARGIN + (i >= TYPE_ROWS_PER_COLUMN ? columnWidth + 24 : 0)
    const rowY = y + (i % TYPE_ROWS_PER_COLUMN) * 15
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(7.6)
    doc.setTextColor(...COLORS.body)
    doc.text(doc.splitTextToSize(toAscii(label), labelWidth - 8)[0], x, rowY + 7.5)
    doc.setFillColor(...COLORS.track)
    doc.roundedRect(x + labelWidth, rowY + 2, trackWidth, 7, 3.5, 3.5, 'F')
    doc.setFillColor(...COLORS.copper)
    doc.roundedRect(x + labelWidth, rowY + 2, Math.max(7, (count / max) * trackWidth), 7, 3.5, 3.5, 'F')
    doc.setFont('helvetica', 'bold')
    doc.setTextColor(...COLORS.ink)
    const share = Math.round((count / rows.length) * 100)
    doc.text(`${count}  (${share}%)`, x + columnWidth, rowY + 7.5, { align: 'right' })
  })
  return y + Math.min(entries.length, TYPE_ROWS_PER_COLUMN) * 15 + 14
}

const drawFooters = (doc) => {
  const total = doc.getNumberOfPages()
  for (let page = 1; page <= total; page += 1) {
    doc.setPage(page)
    doc.setDrawColor(...COLORS.copper)
    doc.setLineWidth(0.8)
    doc.line(MARGIN, FOOTER_Y - 10, PAGE.width - MARGIN, FOOTER_Y - 10)
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(7.2)
    doc.setTextColor(...COLORS.muted)
    doc.text(
      `${APP_NAME}  -  ${REPORT_TITLE}  -  Confidential: for authorised administrators only`,
      MARGIN,
      FOOTER_Y
    )
    doc.text(`Page ${page} of ${total}`, PAGE.width - MARGIN, FOOTER_Y, { align: 'right' })
  }
}

/**
 * @param rows        violations in display order (already filtered and sorted)
 * @param meta.preparedBy  the signed-in admin, as it should read on the report
 * @param meta.filters     human-readable filter descriptions, e.g. "Level: L2"
 * @param meta.sortLabel   human-readable sort order
 * @param meta.totalCount  violations before filtering
 */
export const buildViolationPdf = (rows, meta) => {
  const generatedAt = new Date()
  const doc = new jsPDF({ unit: 'pt', format: 'a4', orientation: 'landscape', compress: true })
  doc.setProperties({
    title: `${APP_NAME} - ${REPORT_TITLE}`,
    subject: 'Proctoring violations',
    author: toAscii(meta.preparedBy),
    creator: APP_NAME
  })

  const context = { ...meta, generatedAt, rowCount: rows.length }
  let y = drawMasthead(doc, context)
  y = drawScope(doc, context, y)
  y = drawSummaryTiles(doc, rows, y)
  y = drawTypeBreakdown(doc, rows, y)
  y = drawSectionTitle(doc, 'Detailed records', y)
  y = drawTableHeader(doc, y)

  if (rows.length === 0) {
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(9)
    doc.setTextColor(...COLORS.muted)
    doc.text('No violations match the selected filters.', MARGIN + PAD_X, y + 18)
  }

  rows.forEach((violation, index) => {
    const layout = layoutRow(doc, violation, index)
    if (y + layout.height > BODY_BOTTOM) {
      doc.addPage()
      y = drawTableHeader(doc, drawRunningHead(doc))
    }
    drawRow(doc, layout, y, index % 2 === 1)
    y += layout.height
  })

  drawFooters(doc)
  return doc
}

export const downloadViolationPdf = (rows, meta) => {
  buildViolationPdf(rows, meta).save(`violation-report-${fileStamp()}.pdf`)
}

// — CSV

const CSV_COLUMNS = [
  ['Violation ID', (v) => v.violationId],
  ['Detected At', (v) => formatDateTime(v.detectedAt)],
  ['Candidate', (v) => v.candidateName],
  ['User ID', (v) => v.userId],
  ['Email', (v) => v.candidateEmail],
  ['Session ID', (v) => v.sessionId],
  ['Application ID', (v) => v.applicationId],
  ['Exam Code', (v) => v.examCode],
  ['Exam Name', (v) => v.examName],
  ['Level', (v) => v.certificationLevel],
  ['Session Status', (v) => v.sessionStatus],
  ['Violation Type', (v) => humanize(v.violationType)],
  ['Violation Level', (v) => v.violationLevel],
  ['Description', (v) => v.description],
  ['Action', (v) => humanize(v.actionTaken)],
  ['Policy', (v) => v.policyMessage],
  ['Exam Terminated', (v) => (v.examTerminated ? 'Yes' : 'No')]
]

// Candidate-supplied text must not be able to run as a spreadsheet formula.
const csvCell = (value) => {
  let cell = value == null ? '' : String(value)
  if (cell && '=+-@\t\r'.includes(cell[0])) cell = `'${cell}`
  return /[",\n\r]/.test(cell) ? `"${cell.replace(/"/g, '""')}"` : cell
}

export const downloadViolationCsv = (rows) => {
  const lines = [
    CSV_COLUMNS.map(([label]) => csvCell(label)).join(','),
    ...rows.map((v) => CSV_COLUMNS.map(([, pick]) => csvCell(pick(v))).join(','))
  ]
  // BOM so Excel opens it as UTF-8 rather than mangling non-ASCII names.
  const blob = new Blob([`﻿${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = globalThis.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `violation-report-${fileStamp()}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  globalThis.URL.revokeObjectURL(url)
}
