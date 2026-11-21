import { Box, Button, MenuItem, TextField } from '@mui/material';
import { useEffect, useState } from 'react';
import { Filters } from '../api';
import { useFilters } from './FilterContext';

const ACCOUNTING = ['COMPLETED', 'PENDING', 'MISSING', 'FAILED', 'NOT_CHECKED'];
const TXN_STATUS = ['SUCCESS', 'FAILED', 'PENDING', 'USER_DROPPED', 'AUTHORIZED', 'FEE_SUCCESS', 'FEE_FAILED'];

/** One row of global filters above every screen. Applying them re-runs every query. */
export default function FilterBar() {
  const { filters, setFilters, meta } = useFilters();
  const [draft, setDraft] = useState<Filters>(filters);
  useEffect(() => setDraft(filters), [filters]);

  const field = (k: keyof Filters, label: string, opts?: string[], width = 150) => (
    <TextField
      key={k}
      size="small"
      label={label}
      select={!!opts}
      value={draft[k] ?? ''}
      onChange={(e) => setDraft({ ...draft, [k]: e.target.value || undefined })}
      sx={{ width }}
      type={k === 'from' || k === 'to' ? 'date' : 'text'}
      InputLabelProps={k === 'from' || k === 'to' ? { shrink: true } : undefined}
    >
      {opts && [<MenuItem key="" value="">All</MenuItem>, ...opts.map((o) => <MenuItem key={o} value={o}>{o}</MenuItem>)]}
    </TextField>
  );

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 2 }}>
      {field('from', `From${meta ? '' : ''}`, undefined, 150)}
      {field('to', 'To', undefined, 150)}
      {field('processor', 'Processor', meta?.processors.map((p) => p.code))}
      {field('scheme', 'Scheme', ['VISA', 'MC', 'RUPAY', 'UPI_NPCI'])}
      {field('channel', 'Channel', meta?.channels)}
      {field('merchant', 'Merchant (MID/name)')}
      {field('txnStatus', 'txnStatus', TXN_STATUS)}
      {field('accountingStatus', 'Accounting', ACCOUNTING)}
      {field('ttCode', 'TT code', undefined, 110)}
      {field('fileType', 'File type', undefined, 130)}
      <Button variant="contained" onClick={() => setFilters(draft)}>Apply</Button>
      <Button onClick={() => setFilters({})}>Reset</Button>
    </Box>
  );
}
