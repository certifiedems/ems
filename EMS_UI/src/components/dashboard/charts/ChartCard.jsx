// ems_frontend/src/components/dashboard/charts/ChartCard.jsx
import { useState } from 'react'
import PropTypes from 'prop-types'
import {
  Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  ToggleButton, ToggleButtonGroup, Tooltip, Typography
} from '@mui/material'
import BarChartIcon from '@mui/icons-material/BarChartRounded'
import TableRowsIcon from '@mui/icons-material/TableRowsRounded'
import { tokens, surface } from '../../../styles/tokens'

const toggleSx = {
  '& .MuiToggleButton-root': {
    px: 1,
    py: 0.5,
    border: `1px solid ${tokens.line2}`,
    color: tokens.muted,
    '&.Mui-selected': {
      background: 'rgba(192,138,46,.18)',
      color: tokens.copperLt,
      '&:hover': { background: 'rgba(192,138,46,.24)' },
    },
  },
}

/**
 * A chart with its title, and a table of the same figures one click away.
 *
 * The table is not an extra: it is how every value stays readable without
 * hovering, and what gets read out when the chart itself cannot be seen.
 * `busy` holds the previous render at reduced opacity while new figures load,
 * rather than flashing a skeleton over a chart that is about to come back.
 */
const ChartCard = ({ title, subtitle, table, footer, busy = false, children }) => {
  const [view, setView] = useState('chart')

  return (
    <Box
      component="figure"
      sx={{
        m: 0,
        ...surface,
        p: { xs: 2, md: 2.5 },
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        gap: 1.75,
        opacity: busy ? 0.55 : 1,
        transition: 'opacity .2s',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 2 }}>
        <Box sx={{ minWidth: 0 }}>
          <Typography component="figcaption" sx={{ fontSize: 15, fontWeight: 700, color: tokens.ink }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography sx={{ mt: 0.25, fontSize: 12.5, color: tokens.body }}>{subtitle}</Typography>
          )}
        </Box>
        {table && (
          <ToggleButtonGroup
            exclusive
            size="small"
            value={view}
            onChange={(event, next) => next && setView(next)}
            aria-label={`${title}: chart or table`}
            sx={toggleSx}
          >
            <ToggleButton value="chart" aria-label="Show chart">
              <Tooltip title="Chart"><BarChartIcon sx={{ fontSize: 17 }} /></Tooltip>
            </ToggleButton>
            <ToggleButton value="table" aria-label="Show table">
              <Tooltip title="Table"><TableRowsIcon sx={{ fontSize: 17 }} /></Tooltip>
            </ToggleButton>
          </ToggleButtonGroup>
        )}
      </Box>

      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        {view === 'table' && table ? (
          <TableContainer sx={{ maxHeight: 260, overflow: 'auto' }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  {table.columns.map((column) => (
                    <TableCell key={column.key} align={column.align || 'left'} sx={{ background: tokens.sub2 }}>
                      {column.label}
                    </TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {table.rows.map((row) => (
                  <TableRow key={row.key} hover>
                    {table.columns.map((column) => (
                      <TableCell
                        key={column.key}
                        align={column.align || 'left'}
                        sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}
                      >
                        {row[column.key]}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        ) : (
          children
        )}
      </Box>

      {footer}
    </Box>
  )
}

ChartCard.propTypes = {
  title: PropTypes.string.isRequired,
  subtitle: PropTypes.node,
  table: PropTypes.shape({
    columns: PropTypes.arrayOf(
      PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.string.isRequired,
        align: PropTypes.oneOf(['left', 'right', 'center']),
      })
    ).isRequired,
    rows: PropTypes.arrayOf(PropTypes.object).isRequired,
  }),
  footer: PropTypes.node,
  busy: PropTypes.bool,
  children: PropTypes.node,
}

export default ChartCard
