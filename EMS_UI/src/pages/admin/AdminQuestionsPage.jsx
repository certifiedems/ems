// ems_frontend/src/pages/admin/AdminQuestionsPage.jsx
import { useEffect, useState, useCallback } from 'react'
import {
  Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, Button, Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, MenuItem, Grid, Skeleton, Snackbar, Alert, Stack,
  Checkbox, FormControlLabel, IconButton, Chip, Typography
} from '@mui/material'
import { adminAPI } from '../../api/adminAPI'
import PageHeader from '../../components/common/PageHeader'
import EmptyState from '../../components/common/EmptyState'
import AddIcon from '@mui/icons-material/AddRounded'
import DeleteIcon from '@mui/icons-material/DeleteRounded'
import UploadFileIcon from '@mui/icons-material/UploadFileRounded'
import DownloadIcon from '@mui/icons-material/DownloadRounded'

const LEVELS = ['L1', 'L2', 'L3']
const CATEGORIES = ['TECHNICAL', 'FUNCTIONAL', 'COMPLIANCE', 'GENERAL']
const TYPES = ['SINGLE_CHOICE', 'MULTIPLE_CHOICE']
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH']

// Same columns the bulk upload reads, so an export can be edited and uploaded back.
const QUESTION_FILE_COLUMNS = ['quesID', 'question', 'option1', 'option2', 'option3', 'option4', 'answer', 'severity', 'category', 'marks', 'Level']

const emptyForm = {
  questionCode: '',
  certificationLevel: 'L1',
  questionCategory: 'TECHNICAL',
  questionType: 'SINGLE_CHOICE',
  questionText: '',
  severity: 'MEDIUM',
  marks: 1,
  options: ['', '', '', ''],
  correct: [false, false, false, false]
}

const AdminQuestionsPage = () => {
  const [questions, setQuestions] = useState([])
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState(emptyForm)
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [selectedIds, setSelectedIds] = useState([])
  const [bulkDeleteOpen, setBulkDeleteOpen] = useState(false)
  const [bulkDeleting, setBulkDeleting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadResult, setUploadResult] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminAPI.getAdminQuestions()
      setQuestions(res.data.data || [])
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to load questions' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const setField = (key, value) => setForm((f) => ({ ...f, [key]: value }))
  const setOption = (i, value) => setForm((f) => {
    const options = [...f.options]; options[i] = value; return { ...f, options }
  })
  const setCorrect = (i, checked) => setForm((f) => {
    let correct = [...f.correct]
    if (f.questionType === 'SINGLE_CHOICE') correct = correct.map(() => false)
    correct[i] = checked
    return { ...f, correct }
  })

  const handleCreate = async () => {
    const options = form.options.map((o) => o.trim()).filter(Boolean)
    const correctOptions = form.options.filter((_, i) => form.correct[i]).map((o) => o.trim()).filter(Boolean)
    if (options.length < 2 || correctOptions.length === 0) {
      setFeedback({ severity: 'error', msg: 'Provide at least 2 options and mark the correct answer(s).' })
      return
    }
    setSaving(true)
    try {
      await adminAPI.createQuestion({
        questionCode: form.questionCode,
        certificationLevel: form.certificationLevel,
        questionCategory: form.questionCategory,
        questionType: form.questionType,
        questionText: form.questionText,
        options,
        correctOptions,
        severity: form.severity,
        marks: Number(form.marks),
        active: true
      })
      setFeedback({ severity: 'success', msg: 'Question created' })
      setOpen(false)
      setForm(emptyForm)
      load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to create question' })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id) => {
    try {
      await adminAPI.deleteQuestion(id)
      setFeedback({ severity: 'success', msg: 'Question deleted' })
      setSelectedIds((ids) => ids.filter((i) => i !== id))
      load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Failed to delete' })
    }
  }

  const toggleSelectOne = (id, checked) => {
    setSelectedIds((ids) => (checked ? [...ids, id] : ids.filter((i) => i !== id)))
  }

  const toggleSelectAll = (checked) => {
    setSelectedIds(checked ? questions.map((q) => q.id) : [])
  }

  const handleBulkDelete = async () => {
    setBulkDeleting(true)
    try {
      const res = await adminAPI.bulkDeleteQuestions(selectedIds)
      const { deletedCount, failedCount, errors } = res.data.data
      setFeedback({
        severity: failedCount > 0 ? 'warning' : 'success',
        msg: failedCount > 0
          ? `Deleted ${deletedCount}, failed ${failedCount}: ${errors.join('; ')}`
          : `Deleted ${deletedCount} question(s)`
      })
      setSelectedIds([])
      setBulkDeleteOpen(false)
      load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Bulk delete failed' })
    } finally {
      setBulkDeleting(false)
    }
  }

  const csvEscape = (value) => {
    const str = String(value ?? '')
    return /[",\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str
  }

  const handleExport = () => {
    const rows = selectedIds.length > 0
      ? questions.filter((q) => selectedIds.includes(q.id))
      : questions
    if (rows.length === 0) {
      setFeedback({ severity: 'error', msg: 'No questions to export' })
      return
    }

    const lines = [QUESTION_FILE_COLUMNS.join(',')]
    rows.forEach((q) => {
      const options = [...(q.options || [])]
      while (options.length < 4) options.push('')
      lines.push([
        q.questionCode,
        q.questionText,
        options[0], options[1], options[2], options[3],
        (q.correctOptions || []).join('|'),
        q.severity,
        q.questionCategory,
        q.marks,
        q.certificationLevel
      ].map(csvEscape).join(','))
    })

    const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `questions-export-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }

  const handleBulkUpload = async (e) => {
    const input = e.target
    const file = input.files?.[0]
    if (!file) return
    setUploading(true)
    try {
      const res = await adminAPI.bulkUploadQuestions(file)
      const result = res.data.data
      setUploadResult(result)
      if (result.importedRows > 0) load()
    } catch (err) {
      setFeedback({ severity: 'error', msg: err.response?.data?.message || 'Bulk upload failed' })
    } finally {
      setUploading(false)
      input.value = ''
    }
  }

  const tableContent = (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell padding="checkbox">
              <Checkbox
                indeterminate={selectedIds.length > 0 && selectedIds.length < questions.length}
                checked={questions.length > 0 && selectedIds.length === questions.length}
                onChange={(e) => toggleSelectAll(e.target.checked)}
              />
            </TableCell>
            <TableCell>Code</TableCell>
            <TableCell>Question</TableCell>
            <TableCell>Level</TableCell>
            <TableCell>Type</TableCell>
            <TableCell>Severity</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {questions.map((q) => (
            <TableRow key={q.id} hover selected={selectedIds.includes(q.id)}>
              <TableCell padding="checkbox">
                <Checkbox
                  checked={selectedIds.includes(q.id)}
                  onChange={(e) => toggleSelectOne(q.id, e.target.checked)}
                />
              </TableCell>
              <TableCell>{q.questionCode}</TableCell>
              <TableCell sx={{ maxWidth: 340, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {q.questionText}
              </TableCell>
              <TableCell>{q.certificationLevel}</TableCell>
              <TableCell>
                <Chip size="small" label={q.questionType?.replace('_', ' ')} variant="outlined" />
              </TableCell>
              <TableCell>{q.severity}</TableCell>
              <TableCell align="right">
                <IconButton color="error" size="small" onClick={() => handleDelete(q.id)}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )

  let paperContent
  if (loading) {
    paperContent = (
      <Box sx={{ p: 2 }}>
        {[1, 2, 3, 4].map((i) => <Skeleton key={i} height={52} />)}
      </Box>
    )
  } else if (questions.length === 0) {
    paperContent = <EmptyState title="No questions yet" description="Create questions or bulk upload a file." />
  } else {
    paperContent = tableContent
  }

  return (
    <Box>
      <PageHeader
        title="Question Management"
        subtitle="Create, import and manage exam questions"
        action={
          <Stack direction="row" spacing={1}>
            {selectedIds.length > 0 && (
              <Button
                color="error"
                variant="outlined"
                startIcon={<DeleteIcon />}
                onClick={() => setBulkDeleteOpen(true)}
              >
                Delete selected ({selectedIds.length})
              </Button>
            )}
            <Button
              variant="outlined"
              startIcon={<DownloadIcon />}
              onClick={handleExport}
              disabled={questions.length === 0}
            >
              Export{selectedIds.length > 0 ? ` (${selectedIds.length})` : ''}
            </Button>
            <Button variant="outlined" component="label" startIcon={<UploadFileIcon />} disabled={uploading}>
              {uploading ? 'Uploading…' : 'Bulk upload'}
              <input type="file" hidden accept=".xlsx,.xls,.csv" onChange={handleBulkUpload} />
            </Button>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => { setForm(emptyForm); setOpen(true) }}>
              New question
            </Button>
          </Stack>
        }
      />

      <Paper>
        {paperContent}
      </Paper>

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>New Question</DialogTitle>
        <DialogContent dividers>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Question Code"
                value={form.questionCode}
                onChange={(e) => setField('questionCode', e.target.value)}
                helperText="e.g. Q001L — last letter is the severity (L/M/H)"
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth select label="Level" value={form.certificationLevel} onChange={(e) => setField('certificationLevel', e.target.value)}>
                {LEVELS.map((l) => <MenuItem key={l} value={l}>{l}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField fullWidth select label="Category" value={form.questionCategory} onChange={(e) => setField('questionCategory', e.target.value)}>
                {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField fullWidth select label="Type" value={form.questionType} onChange={(e) => setField('questionType', e.target.value)}>
                {TYPES.map((t) => <MenuItem key={t} value={t}>{t.replace('_', ' ')}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField fullWidth select label="Severity" value={form.severity} onChange={(e) => setField('severity', e.target.value)}>
                {SEVERITIES.map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth multiline rows={2} label="Question Text" value={form.questionText} onChange={(e) => setField('questionText', e.target.value)} />
            </Grid>
            {form.options.map((opt, i) => (
              <Grid item xs={12} key={i}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <TextField
                    fullWidth
                    label={`Option ${i + 1}`}
                    value={opt}
                    onChange={(e) => setOption(i, e.target.value)}
                  />
                  <FormControlLabel
                    control={<Checkbox checked={form.correct[i]} onChange={(e) => setCorrect(i, e.target.checked)} />}
                    label="Correct"
                  />
                </Stack>
              </Grid>
            ))}
            <Grid item xs={12} sm={4}>
              <TextField fullWidth type="number" label="Marks" value={form.marks} onChange={(e) => setField('marks', e.target.value)} />
            </Grid>
          </Grid>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={handleCreate} disabled={saving}>
            {saving ? 'Creating…' : 'Create'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={bulkDeleteOpen} onClose={() => setBulkDeleteOpen(false)}>
        <DialogTitle>Delete {selectedIds.length} question(s)?</DialogTitle>
        <DialogContent>
          This will permanently delete the selected question(s). This action cannot be undone.
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setBulkDeleteOpen(false)} disabled={bulkDeleting}>Cancel</Button>
          <Button color="error" variant="contained" onClick={handleBulkDelete} disabled={bulkDeleting}>
            {bulkDeleting ? 'Deleting…' : 'Delete'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={Boolean(uploadResult)} onClose={() => setUploadResult(null)} maxWidth="md" fullWidth>
        <DialogTitle>Bulk upload result</DialogTitle>
        <DialogContent dividers>
          {uploadResult && (
            <Stack spacing={2}>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Chip label={`Rows read: ${uploadResult.totalRows}`} />
                <Chip color="success" variant="outlined" label={`Created: ${uploadResult.createdRows}`} />
                <Chip color="info" variant="outlined" label={`Updated: ${uploadResult.updatedRows}`} />
                <Chip
                  color={uploadResult.failedRows > 0 ? 'error' : 'default'}
                  variant="outlined"
                  label={`Failed: ${uploadResult.failedRows}`}
                />
              </Stack>
              {uploadResult.failedRows > 0 ? (
                <>
                  <Alert severity={uploadResult.importedRows > 0 ? 'warning' : 'error'}>
                    Fix the rows below and upload the file again — rows that already imported are updated, not duplicated.
                  </Alert>
                  <Box component="ul" sx={{ m: 0, pl: 2.5, maxHeight: 320, overflowY: 'auto' }}>
                    {uploadResult.errors.map((error) => (
                      <Typography component="li" variant="body2" key={error} sx={{ py: 0.25 }}>
                        {error}
                      </Typography>
                    ))}
                  </Box>
                  <Typography variant="caption" color="text.secondary">
                    Expected columns: {QUESTION_FILE_COLUMNS.join(', ')}
                  </Typography>
                </>
              ) : (
                <Alert severity="success">All {uploadResult.importedRows} question(s) imported.</Alert>
              )}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setUploadResult(null)}>Close</Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={Boolean(feedback)}
        autoHideDuration={4000}
        onClose={() => setFeedback(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {feedback && (
          <Alert severity={feedback.severity} onClose={() => setFeedback(null)}>
            {feedback.msg}
          </Alert>
        )}
      </Snackbar>
    </Box>
  )
}

export default AdminQuestionsPage
