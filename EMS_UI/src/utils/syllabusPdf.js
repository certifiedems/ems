// ems_frontend/src/utils/syllabusPdf.js
import { jsPDF } from 'jspdf'
import { getSyllabus } from '../data/syllabus'

/*
 * The PDF is generated from the same objects the screen renders rather than
 * shipping a pre-built file in /public, so the download can never drift from
 * what the candidate was shown.
 */

const PAGE = { width: 595.28, height: 841.89 } // A4 portrait, points
const MARGIN = 48
const CONTENT_WIDTH = PAGE.width - MARGIN * 2

// Column geometry for the module table.
const REF_COL_WIDTH = 74
const TEXT_COL_X = MARGIN + REF_COL_WIDTH
const TEXT_COL_WIDTH = CONTENT_WIDTH - REF_COL_WIDTH

const COLORS = {
  green: [15, 76, 58],
  greenText: [17, 94, 71],
  gold: [184, 148, 62],
  body: [55, 65, 81],
  muted: [107, 114, 128],
  rule: [226, 232, 240],
  white: [255, 255, 255]
}

/*
 * jsPDF's built-in Helvetica is a WinAnsi font, so characters outside that
 * encoding (the en/em dashes and curly quotes used in the syllabus copy) would
 * come out as mojibake. Folding them to ASCII equivalents keeps the generated
 * document readable without embedding a full Unicode font.
 */
export const toAscii = (value) =>
  String(value)
    .replace(/[–—]/g, '-')
    .replace(/[‘’]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/…/g, '...')
    .replace(/·/g, '-')
    .replace(/[\u00A0\u202F]/g, ' ')

/** Draws the page title block. Returns the y cursor below it. */
const drawTitle = (doc, syllabus) => {
  let y = MARGIN + 8

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(18)
  doc.setTextColor(...COLORS.green)
  doc.text(toAscii(syllabus.documentTitle), MARGIN, y)

  y += 10
  doc.setDrawColor(...COLORS.gold)
  doc.setLineWidth(1.5)
  doc.line(MARGIN, y, PAGE.width - MARGIN, y)

  y += 18
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(9.5)
  doc.setTextColor(...COLORS.body)
  const noteLines = doc.splitTextToSize(toAscii(syllabus.note), CONTENT_WIDTH)
  doc.text(noteLines, MARGIN, y)
  y += noteLines.length * 12 + 8

  return y
}

/** Draws the dark table header band. Returns the y cursor below it. */
const drawTableHeader = (doc, y) => {
  const height = 22

  doc.setFillColor(...COLORS.green)
  doc.rect(MARGIN, y, CONTENT_WIDTH, height, 'F')

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(8)
  doc.setTextColor(...COLORS.white)
  doc.text('REF', MARGIN + 10, y + 14.5)
  doc.text('MODULE AND INDICATIVE CONTENT', TEXT_COL_X, y + 14.5)

  return y + height + 12
}

/**
 * Measures a module row without drawing it, so a row that would straddle the
 * page break can be moved to the next page whole.
 */
const measureRow = (doc, module) => {
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(9)
  const bodyLines = doc.splitTextToSize(toAscii(module.content), TEXT_COL_WIDTH)
  // Title line + body lines + separator gap.
  return { bodyLines, height: 15 + bodyLines.length * 11.5 + 14 }
}

const drawRow = (doc, module, y, bodyLines) => {
  doc.setFont('courier', 'normal')
  doc.setFontSize(8)
  doc.setTextColor(...COLORS.greenText)
  doc.text(toAscii(module.ref), MARGIN + 10, y + 1)

  doc.setFont('helvetica', 'bold')
  doc.setFontSize(10.5)
  doc.setTextColor(...COLORS.green)
  doc.text(toAscii(module.title), TEXT_COL_X, y + 2)

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(9)
  doc.setTextColor(...COLORS.body)
  doc.text(bodyLines, TEXT_COL_X, y + 16)

  const bottom = y + 16 + bodyLines.length * 11.5 - 2
  doc.setDrawColor(...COLORS.rule)
  doc.setLineWidth(0.5)
  doc.line(MARGIN, bottom, PAGE.width - MARGIN, bottom)
}

/** Stamps "Page n of m" plus the generation date onto every page. */
const drawFooters = (doc, syllabus) => {
  const total = doc.getNumberOfPages()
  const generated = new Date().toLocaleDateString()

  for (let page = 1; page <= total; page += 1) {
    doc.setPage(page)
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(8)
    doc.setTextColor(...COLORS.muted)
    doc.text(
      toAscii(`${syllabus.documentTitle}    Generated ${generated}`),
      MARGIN,
      PAGE.height - 28
    )
    doc.text(`Page ${page} of ${total}`, PAGE.width - MARGIN, PAGE.height - 28, { align: 'right' })
  }
}

/** Builds the syllabus PDF document for a level. Returns null for unknown levels. */
export const buildSyllabusPdf = (level) => {
  const syllabus = getSyllabus(level)
  if (!syllabus) return null

  const doc = new jsPDF({ unit: 'pt', format: 'a4', compress: true })
  doc.setProperties({
    title: syllabus.documentTitle,
    subject: `${syllabus.level} certification syllabus`,
    creator: 'Examination Management System'
  })

  let y = drawTitle(doc, syllabus)
  y = drawTableHeader(doc, y)

  syllabus.modules.forEach((module) => {
    const { bodyLines, height } = measureRow(doc, module)

    if (y + height > PAGE.height - MARGIN - 20) {
      doc.addPage()
      y = drawTableHeader(doc, MARGIN + 8)
    }

    drawRow(doc, module, y, bodyLines)
    y += height
  })

  drawFooters(doc, syllabus)
  return doc
}

export const syllabusFileName = (level) => `${level}_Syllabus.pdf`

/**
 * Triggers a browser download of the level's syllabus.
 * Returns true when the document was produced, false for an unknown level.
 */
export const downloadSyllabusPdf = (level) => {
  const doc = buildSyllabusPdf(level)
  if (!doc) return false
  doc.save(syllabusFileName(level))
  return true
}
