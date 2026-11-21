import { Alert, Box, Button, Checkbox, FormControlLabel, Grid, Typography } from '@mui/material';
import { useState } from 'react';
import { get, post, Row } from '../api';
import { ProcessorLines } from '../components/Charts';
import { DataTable, JsonDialog, KpiCard, Loadable, useAsync } from '../components/Common';
import { asParams, useFilters } from '../components/FilterContext';
import { useCharts } from './Overview';
import { Filters, Kpi } from "../api";

export function Processors() {
  const { meta, filters, version } = useFilters();
  const charts = useCharts();
  const procs = meta?.processors ?? [];
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>Processors</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Discovered from platformType values in C&amp;S data and kept in the processor_config table (not hardcoded).
      </Typography>
      {procs.length === 0 && <Alert severity="info">No processors discovered yet - run a TRANSACTIONS collection.</Alert>}
      <Grid container spacing={2}>
        {procs.map((p) => <Grid item xs={12} key={p.code}><ProcessorPanel code={p.code} filters={filters} version={version} /></Grid>)}
      </Grid>
      <Loadable state={charts}>
        {charts.data && <ProcessorLines rows={charts.data.volumeByDayProcessor} suffix="_count" title="Daily volume by processor"
          allProcessors={procs.map((p) => p.code)} />}
      </Loadable>
    </Box>
  );
}

function ProcessorPanel({ code, filters, version }: { code: string; filters: Filters; version: number }) {
  const k = useAsync(() => get<Kpi[]>("/kpis", { ...asParams(filters), processor: code }), [version, code]);
  const pick = ['txn.count', 'txn.amount', 'txn.settlementStatus.SETTLED', 'txn.settlementStatus.SCHEME_REJECT', 'accounting.status'];
  return (
    <Box>
      <Typography variant="h6">{code}</Typography>
      <Loadable state={k}>
        <Grid container spacing={2}>
          {k.data?.filter((x) => pick.includes(x.key)).map((x) => <Grid item xs={12} sm={6} md={4} lg key={x.key}><KpiCard k={x} /></Grid>)}
        </Grid>
      </Loadable>
    </Box>
  );
}

export function Recon() {
  const { filters, version } = useFilters();
  const r = useAsync(() => get<Row[]>('/recon/payu', asParams(filters)), [version, JSON.stringify(filters)]);
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Reconciliation - PayU</Typography>
      <Loadable state={r}>
        <DataTable title="PayU recon vs C&S (R16)" rows={r.data ?? []} columns={['outcome', 'subjectKey', 'message', 'businessDate']}
          exportList="payu" exportParams={asParams(filters)} />
      </Loadable>
    </Box>
  );
}

export function Runs() {
  const { version, refresh, meta, filters } = useFilters();
  const runs = useAsync(() => get<Row[]>('/runs'), [version]);
  const alerts = useAsync(() => get<Row[]>('/alerts'), [version]);
  const [detail, setDetail] = useState<Row>();
  const [groups, setGroups] = useState<string[]>(['TRANSACTIONS', 'FILES', 'LEDGER']);
  const [msg, setMsg] = useState<{ ok: boolean; text: string }>();

  const trigger = async () => {
    try {
      const r = await post<{ runId: number }>('/runs', { groups, from: filters.from, to: filters.to });
      setMsg({ ok: true, text: `Run ${r.runId} started.` });
      setTimeout(refresh, 3000);
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message });
    }
  };

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Runs &amp; Alerts</Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        {['TRANSACTIONS', 'FILES', 'LEDGER'].map((g) => (
          <FormControlLabel key={g} label={g} control={<Checkbox checked={groups.includes(g)}
            onChange={(e) => setGroups(e.target.checked ? [...groups, g] : groups.filter((x) => x !== g))} />} />
        ))}
        <Button variant="contained" disabled={meta?.running || groups.length === 0} onClick={trigger}>Run validation</Button>
        <Button onClick={refresh}>Refresh</Button>
        <Typography variant="caption" color="text.secondary">Schedule: transactions every 15 min, files hourly, ledger daily (configurable; enable with MONITOR_SCHEDULE_ENABLED).</Typography>
      </Box>
      {msg && <Alert severity={msg.ok ? 'success' : 'error'} sx={{ mb: 2 }}>{msg.text}</Alert>}
      <Loadable state={runs}>
        <DataTable title="Runs" rows={runs.data ?? []} exportList="runs" exportParams={{}}
          onRow={(r) => get<Row>(`/runs/${r.id}`).then(setDetail)} />
      </Loadable>
      <Loadable state={alerts}>
        <DataTable title="Alerts" rows={alerts.data ?? []} columns={['severity', 'type', 'ruleId', 'subject', 'message', 'processor', 'at']}
          exportList="alerts" exportParams={{}} />
      </Loadable>
      <JsonDialog value={detail} onClose={() => setDetail(undefined)} title="Run detail, datasets and network log" />
    </Box>
  );
}

export function DataQuality() {
  const { version } = useFilters();
  const d = useAsync(() => get<Record<string, Row[]>>('/data-quality'), [version]);
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Data Quality</Typography>
      <Loadable state={d}>
        <DataTable title="'Decryption failed' values (R07)" rows={d.data?.decryptionFailures ?? []} columns={['subjectKey', 'message', 'evaluatedAt']} />
        <DataTable title="Source errors (incl. HTTP 200 + success:false / NOTUSED)" rows={d.data?.sourceErrors ?? []} />
        <DataTable title="Datasets currently failing" rows={d.data?.datasetErrors ?? []} />
        <DataTable title="UI label vs API field mismatches" rows={d.data?.labelMismatches ?? []} />
        <DataTable title="Date-format anomalies" rows={d.data?.dateFormats ?? []} />
      </Loadable>
    </Box>
  );
}
