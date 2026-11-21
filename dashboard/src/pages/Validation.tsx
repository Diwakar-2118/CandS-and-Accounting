import { Alert, Box, Button, MenuItem, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { get, post, Row } from '../api';
import { DataTable, Loadable, useAsync } from '../components/Common';
import { asParams, useFilters } from '../components/FilterContext';

export default function Validation() {
  const { filters, version, meta, refresh } = useFilters();
  const [tab, setTab] = useState(0);
  const [rule, setRule] = useState('');
  const [outcome, setOutcome] = useState('');
  const summary = useAsync(() => get<Row[]>('/validation/summary', asParams(filters)), [version, JSON.stringify(filters)]);
  const results = useAsync(() => get<Row[]>('/validation/results', { ...asParams(filters), ruleId: rule, outcome }),
    [version, JSON.stringify(filters), rule, outcome]);
  const matrix = useAsync(() => get<Row[]>('/validation/matrix', asParams(filters)), [version, JSON.stringify(filters)]);
  const mappings = useAsync(() => get<Row[]>('/mappings'), [version]);
  const [err, setErr] = useState<string>();

  const setMapping = async (code: string, confirm: boolean) => {
    try {
      const note = window.prompt(`${confirm ? 'Confirm' : 'Un-confirm'} ${code}. Note (e.g. compared with business brief):`) ?? undefined;
      await post(`/mappings/${encodeURIComponent(code)}/${confirm ? 'confirm' : 'unconfirm'}`, { note });
      refresh();
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Accounting Validation</Typography>
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab label="Rules" /><Tab label="Failures" /><Tab label="TT × ledger × date" /><Tab label="TT mappings" />
      </Tabs>
      {tab === 0 && <Loadable state={summary}><DataTable title="Latest result per rule" rows={summary.data ?? []}
        columns={['ruleId', 'ruleName', 'category', 'outcome', 'message', 'evaluatedAt']} exportList="validation"
        exportParams={{ ...asParams(filters), summary: 'true' }} /></Loadable>}
      {tab === 1 && (
        <>
          <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
            <TextField select size="small" label="Rule" value={rule} onChange={(e) => setRule(e.target.value)} sx={{ width: 320 }}>
              <MenuItem value="">All</MenuItem>
              {meta?.rules.map((r) => <MenuItem key={r.id} value={r.id}>{r.id} {r.name}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} sx={{ width: 180 }}>
              {['', 'FAIL', 'MISSING', 'PENDING', 'WARN', 'NOT_EVALUATED', 'PASS'].map((o) => <MenuItem key={o} value={o}>{o || 'All'}</MenuItem>)}
            </TextField>
          </Box>
          <Loadable state={results}><DataTable title="Results with evidence" rows={results.data ?? []}
            columns={['ruleId', 'outcome', 'subjectKey', 'message', 'processor', 'businessDate', 'ttCode', 'ledger']}
            exportList="validation" exportParams={{ ...asParams(filters), ruleId: rule, outcome }} /></Loadable>
        </>
      )}
      {tab === 2 && (
        <>
          <Alert severity="info" sx={{ mb: 2 }}>
            Built from per-RRN postings (/api/accounting/searchAllEntries). The TT column shows the posting's subModule
            until the TT-code field is confirmed (monitor.fields.posting.ttCode).
          </Alert>
          <Loadable state={matrix}><DataTable title="Postings matrix" rows={matrix.data ?? []} exportList="matrix" exportParams={asParams(filters)} /></Loadable>
        </>
      )}
      {tab === 3 && (
        <>
          {err && <Alert severity="error" onClose={() => setErr(undefined)}>{err}</Alert>}
          <Alert severity="info" sx={{ mb: 2 }}>
            Loaded from Accounting Transaction Types. Codes stay "Mapping not confirmed" (and are not validated by R09) until an operator confirms them.
          </Alert>
          <Loadable state={mappings}>
            <DataTable title="Transaction-type mappings" rows={(mappings.data ?? []).map((m) => ({
              ...m,
              action: m.status === 'CONFIRMED' ? 'confirmed' : 'unconfirmed',
            }))} columns={['code', 'name', 'glLines', 'displayStatus', 'confirmedBy', 'confirmedAt', 'note']}
              exportList="mappings" exportParams={{}}
              onRow={(r) => setMapping(String(r.code), r.status !== 'CONFIRMED')} />
          </Loadable>
          <Typography variant="caption" color="text.secondary">"View details" on a row toggles its confirmation (OPERATOR role).</Typography>
          <Button sx={{ display: 'none' }} />
        </>
      )}
    </Box>
  );
}
