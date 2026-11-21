import { Box, MenuItem, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { get, Row } from '../api';
import { DataTable, Loadable, useAsync } from '../components/Common';
import { useFilters } from '../components/FilterContext';

export default function Ledger() {
  const { version, filters } = useFilters();
  const [cat, setCat] = useState('group');
  const tb = useAsync(() => get<Row[]>('/ledger/trial-balance', { category: cat }), [version, cat]);
  const tbRule = useAsync(() => get<Row[]>('/validation/results', { ...filters, ruleId: 'R12' }), [version]);
  const adj = useAsync(() => get<Row[]>('/ledger/adjustments'), [version]);
  const adjRule = useAsync(() => get<Row[]>('/validation/results', { ruleId: 'R13' }), [version]);
  const periods = useAsync(() => get<{ periods: Row[]; fiscalYears: Row[] }>('/ledger/periods'), [version]);
  const perRule = useAsync(() => get<Row[]>('/validation/results', { ruleId: 'R14' }), [version]);
  const balRule = useAsync(() => get<Row[]>('/validation/results', { ruleId: 'R15' }), [version]);

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Ledger Health</Typography>
      <Loadable state={tbRule}><DataTable title="Trial balance checks (R12: DR = CR)" rows={tbRule.data ?? []}
        columns={['outcome', 'subjectKey', 'message', 'evaluatedAt']} /></Loadable>
      <TextField select size="small" label="Trial balance by" value={cat} onChange={(e) => setCat(e.target.value)} sx={{ width: 200, mb: 1 }}>
        <MenuItem value="group">Group</MenuItem><MenuItem value="subgroup">Sub Group</MenuItem><MenuItem value="ledger">Ledger</MenuItem>
      </TextField>
      <Loadable state={tb}><DataTable title="Trial balance rows" rows={tb.data ?? []} exportList="trial-balance" exportParams={{ tbCategory: cat }} /></Loadable>
      <Loadable state={adjRule}><DataTable title="Ledger adjustment checks (R13)" rows={adjRule.data ?? []}
        columns={['outcome', 'subjectKey', 'message']} /></Loadable>
      <Loadable state={adj}><DataTable title="Ledger adjustments" rows={adj.data ?? []} exportList="adjustments" exportParams={{}} /></Loadable>
      <Loadable state={perRule}><DataTable title="Period checks (R14)" rows={perRule.data ?? []} columns={['outcome', 'subjectKey', 'message']} /></Loadable>
      <Loadable state={periods}>
        <DataTable title="Periods" rows={periods.data?.periods ?? []} exportList="periods" exportParams={{}} />
        <DataTable title="Fiscal years" rows={periods.data?.fiscalYears ?? []} />
      </Loadable>
      <Loadable state={balRule}><DataTable title="Running balance checks (R15)" rows={balRule.data ?? []} columns={['outcome', 'subjectKey', 'ledger', 'message']} /></Loadable>
    </Box>
  );
}
