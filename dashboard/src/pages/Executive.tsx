import { Alert, Box, Button, Card, Chip, Grid, LinearProgress, MenuItem, TextField, Tooltip, Typography, useMediaQuery } from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import ReportProblemIcon from '@mui/icons-material/ReportProblem';
import { ReactNode, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get, Kpi, Row } from '../api';
import { useAsync } from '../components/Common';
import { useFilters } from '../components/FilterContext';
import { inr, Sparkwave, WaveChart } from '../components/Waves';
import { seriesColors, STATUS } from '../palette';

interface Channel {
  key: string; label: string; total: number; amount: number; approved: number; declined: number; refunded: number;
  other: number; successRate: number | null; net: number; mappingNote?: string | null;
}
interface Volume {
  available: boolean; reason?: string; from: string; to: string; granularity: 'hour' | 'day';
  channels: Channel[]; total: Channel & { unclassified: Record<string, number> }; series: Row[];
  topMerchants: { merchantName: string; merchantNumber: string; count: number; amount: number }[];
  source: { lastSuccessAt?: string };
}

const PERIODS: [string, string][] = [['today', 'Today'], ['yesterday', 'Yesterday'], ['last7', 'Last 7 days'], ['month', 'This month']];

const pct = (a: number, b: number) => (b > 0 ? Math.round((1000 * a) / b) / 10 : null);

function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <Box sx={{ mb: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1.5 }}>
        <Box sx={{ width: 4, height: 18, bgcolor: '#eb6834', borderRadius: 1, mr: 1.25 }} />
        <Typography variant="overline" fontWeight={800} letterSpacing={1.2} sx={{ flex: 1, lineHeight: 1.4 }}>{title}</Typography>
        {action}
      </Box>
      {children}
    </Box>
  );
}

const panel = { p: 2.5, borderRadius: 3, height: '100%', boxShadow: '0 1px 3px rgba(0,0,0,0.06)' } as const;

function Headline({ label, value, sub, help }: { label: string; value: ReactNode; sub?: ReactNode; help?: string }) {
  return (
    <Card variant="outlined" sx={panel}>
      <Tooltip title={help ?? ''}><Typography variant="body2" color="text.secondary" fontWeight={600}>{label}</Typography></Tooltip>
      <Typography variant="h4" fontWeight={800} sx={{ my: 0.5 }}>{value}</Typography>
      {sub && <Typography variant="body2" color="text.secondary">{sub}</Typography>}
    </Card>
  );
}

interface Issue { level: 'high' | 'medium'; text: string; link: string }

/** Turns KPI and alert data into short sentences a manager can act on. */
function issuesFrom(kpis: Kpi[], alerts: Row[]): Issue[] {
  const out: Issue[] = [];
  const k = (key: string) => kpis.find((x) => x.key === key);
  const failingSources = alerts.filter((a) => a.type === 'SOURCE_UNAVAILABLE');
  if (failingSources.length) {
    out.push({ level: 'high', link: '/data-quality',
      text: `${failingSources.length} data source(s) could not be read from UAT (${failingSources.map((a) => String(a.subject).replace(/^cs\.|^acc\./, '').replace(/_/g, ' ')).join(', ')}). Figures that depend on them are shown as "Not available".` });
  }
  const tb = k('tb.difference');
  if (tb?.available && Array.isArray(tb.value)) {
    (tb.value as Row[]).filter((r) => r.outcome === 'FAIL').forEach((r) => out.push({ level: 'high', link: '/ledger',
      text: `Accounting books do not balance for ${String(r.range).replace('group ', '')}: debits exceed credits by ${inr(r.difference, false)}.` }));
  }
  const adj = k('adjustments.unbalanced');
  if (adj?.available && Number(adj.value) > 0) {
    out.push({ level: 'high', link: '/ledger', text: `${adj.value} manual ledger adjustment(s) are one-sided (debit ≠ credit).` });
  }
  const per = k('periods.health');
  if (per?.available && per.value !== 'OK') out.push({ level: 'medium', link: '/ledger', text: `Accounting periods: ${per.value} (e.g. no open period for today, gaps between periods).` });
  const acc = k('accounting.status');
  if (acc?.available && acc.value && typeof acc.value === 'object') {
    const a = acc.value as Record<string, number>;
    if (a.MISSING) out.push({ level: 'high', link: '/transactions?accountingStatus=MISSING', text: `${a.MISSING} transaction(s) never reached Accounting within the agreed time.` });
    if (a.FAILED) out.push({ level: 'high', link: '/transactions?accountingStatus=FAILED', text: `${a.FAILED} transaction(s) were posted to Accounting with unbalanced entries.` });
  }
  const fails = alerts.filter((a) => a.type === 'FAIL' || a.type === 'MISSING');
  const byRule: Record<string, number> = {};
  fails.forEach((a) => { byRule[String(a.ruleId)] = (byRule[String(a.ruleId)] ?? 0) + 1; });
  const plain: Record<string, string> = {
    R03: 'released transactions have no matching payment record', R04: 'journal vouchers are overdue',
    R05: 'clearing files failed or are missing', R06: 'UPI payout files were not generated (BDF)',
    R07: 'records show "Decryption failed" in bank/account fields', R11: 'transactions were posted twice in Accounting',
    R15: 'ledger running balances do not add up',
  };
  Object.entries(byRule).filter(([r]) => plain[r]).forEach(([r, n]) => out.push({ level: 'medium', link: '/validation', text: `${n} ${plain[r]}.` }));
  return out;
}

export default function Executive() {
  const nav = useNavigate();
  const { meta, version } = useFilters();
  const dark = useMediaQuery('(prefers-color-scheme: dark)');
  const colors = seriesColors(dark);
  const [period, setPeriod] = useState('last7');
  const [processor, setProcessor] = useState('');
  const [mode, setMode] = useState('');

  const vol = useAsync(() => get<Volume>('/volume', { frequency: period, processor, channel: mode }), [period, processor, mode, version]);
  const range = vol.data ? { from: vol.data.from, to: vol.data.to } : undefined;
  const kpis = useAsync(() => (range ? get<Kpi[]>('/kpis', { ...range, processor, channel: mode }) : Promise.resolve([] as Kpi[])),
    [range?.from, range?.to, processor, mode, version]);
  const alerts = useAsync(() => get<Row[]>('/alerts'), [version]);

  const v = vol.data;
  const K = (key: string) => kpis.data?.find((x) => x.key === key);
  const num = (key: string) => (K(key)?.available ? Number(K(key)!.value) : null);
  const issues = useMemo(() => issuesFrom(kpis.data ?? [], alerts.data ?? []), [kpis.data, alerts.data]);
  const high = issues.filter((i) => i.level === 'high').length;

  const acc = K('accounting.status');
  const accMap = acc?.available ? (acc.value as Record<string, number>) : null;
  const checked = accMap ? Object.entries(accMap).filter(([s]) => s !== 'NOT_CHECKED').reduce((a, [, n]) => a + n, 0) : 0;
  const reconciled = accMap?.COMPLETED ?? 0;

  const channelSeries = (v?.channels ?? []).map((c) => ({ key: c.key, label: c.label }));
  const periodLabel = PERIODS.find((p) => p[0] === period)?.[1];
  const sub = `${periodLabel} · ${processor || 'All processors'} · ${mode ? v?.channels.find((c) => c.key === mode)?.label ?? mode : 'All payment modes'}`;

  const journey = v && [
    { label: 'Transactions received', value: v.total.total, note: inr(v.total.amount) },
    { label: 'Approved', value: v.total.approved, note: `${pct(v.total.approved, v.total.total) ?? '—'}% of received` },
    { label: 'Settled with scheme', value: num('txn.settlementStatus.SETTLED'), note: 'settlementStatus = SETTLED' },
    { label: 'Paid out to merchants', value: num('txn.paymentRelease.COMPLETED'), note: 'payment release completed' },
    { label: 'Confirmed in Accounting', value: accMap ? reconciled : null, note: accMap ? `${checked} checked so far` : 'not checked yet' },
  ];

  return (
    <Box sx={{ maxWidth: 1400, mx: 'auto' }}>
      <Card variant="outlined" sx={{ ...panel, mb: 2 }}>
        <Typography variant="h5" fontWeight={800}>Executive Summary</Typography>
        <Typography variant="body2" color="text.secondary">
          Payments volume, how much reached settlement and Accounting, and what needs attention, from Toucan UAT C&amp;S and Accounting.
        </Typography>
      </Card>

      <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center', mb: 3 }}>
        <TextField select size="small" label="Period" value={period} onChange={(e) => setPeriod(e.target.value)} sx={{ minWidth: 160 }}>
          {PERIODS.map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Processor" value={processor} onChange={(e) => setProcessor(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {meta?.processors.map((p) => <MenuItem key={p.code} value={p.code}>{p.displayName}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Payment mode" value={mode} onChange={(e) => setMode(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {(v?.channels ?? []).map((c) => <MenuItem key={c.key} value={c.key}>{c.label}</MenuItem>)}
        </TextField>
        <Button variant="outlined" color="warning" onClick={() => { setPeriod('last7'); setProcessor(''); setMode(''); }}>Reset</Button>
        <Box sx={{ flex: 1 }} />
        {v?.source?.lastSuccessAt && <Typography variant="caption" color="text.secondary">Data as of {new Date(v.source.lastSuccessAt).toLocaleString('en-IN')}</Typography>}
      </Box>

      {(vol.loading || kpis.loading) && <LinearProgress sx={{ mb: 2 }} />}
      {vol.error && <Alert severity="error">Not available: {vol.error}</Alert>}
      {v && !v.available && <Alert severity="warning">Transactions are not available: {v.reason}</Alert>}

      {v?.available && (
        <>
          {/* Health headline */}
          <Card variant="outlined" sx={{ ...panel, mb: 3, borderLeft: `6px solid ${high ? STATUS.critical : issues.length ? STATUS.warning : STATUS.good}` }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              {high ? <ErrorIcon sx={{ color: STATUS.critical }} /> : issues.length ? <ReportProblemIcon sx={{ color: STATUS.warning }} /> : <CheckCircleIcon sx={{ color: STATUS.good }} />}
              <Typography variant="h6" fontWeight={800}>
                {high ? `Needs attention: ${high} high-priority issue${high > 1 ? 's' : ''}` : issues.length ? `${issues.length} item(s) to review` : 'All checks passing'}
              </Typography>
            </Box>
            <Typography variant="body1" sx={{ mt: 1 }}>
              {v.total.total.toLocaleString('en-IN')} transactions worth <b>{inr(v.total.amount)}</b> in {periodLabel?.toLowerCase()};
              {' '}<b>{v.total.successRate ?? '—'}%</b> approved
              {accMap ? <> and <b>{pct(reconciled, checked) ?? '—'}%</b> of checked transactions confirmed in Accounting</> : null}.
            </Typography>
          </Card>

          <Section title="Headline numbers">
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6} md={2.4}><Headline label="Total value" value={inr(v.total.amount)} sub={`${v.total.total.toLocaleString('en-IN')} transactions`} /></Grid>
              <Grid item xs={12} sm={6} md={2.4}><Headline label="Approval rate" value={`${v.total.successRate ?? '—'}%`} sub={`${v.total.declined} declined`} help="Approved ÷ (approved + declined), from txnStatus" /></Grid>
              <Grid item xs={12} sm={6} md={2.4}><Headline label="Net after refunds" value={inr(v.total.net)} sub={`${v.total.refunded} refunds`} /></Grid>
              <Grid item xs={12} sm={6} md={2.4}><Headline label="Settled with scheme"
                value={num('txn.settlementStatus.SETTLED') == null ? 'Not available' : `${pct(num('txn.settlementStatus.SETTLED')!, v.total.total)}%`}
                sub={`${num('txn.settlementStatus.NOT_SETTLED') ?? '—'} not yet settled`} /></Grid>
              <Grid item xs={12} sm={6} md={2.4}><Headline label="Confirmed in Accounting"
                value={accMap ? `${pct(reconciled, checked) ?? '—'}%` : 'Not available'}
                sub={accMap ? `${reconciled} of ${checked} checked` : acc?.reason} /></Grid>
            </Grid>
          </Section>

          <Section title="Money journey">
            <Card variant="outlined" sx={panel}>
              <Grid container spacing={2} alignItems="stretch">
                {journey?.map((s, i) => (
                  <Grid item xs={12} sm={6} md key={s.label}>
                    <Box sx={{ position: 'relative', pr: { md: 2 } }}>
                      <Typography variant="caption" color="text.secondary" fontWeight={700}>STEP {i + 1}</Typography>
                      <Typography variant="body2" fontWeight={700}>{s.label}</Typography>
                      <Typography variant="h5" fontWeight={800}>{s.value == null ? 'Not available' : s.value.toLocaleString('en-IN')}</Typography>
                      <Typography variant="caption" color="text.secondary">{s.note}</Typography>
                      {i < journey.length - 1 && <Typography sx={{ display: { xs: 'none', md: 'block' }, position: 'absolute', right: 0, top: 28, color: 'text.disabled', fontSize: 22 }}>›</Typography>}
                    </Box>
                  </Grid>
                ))}
              </Grid>
            </Card>
          </Section>

          <Section title="Volume trend">
            <Card variant="outlined" sx={panel}>
              <WaveChart title={v.granularity === 'hour' ? 'Hourly volume' : 'Daily volume'} subtitle={sub} rows={v.series} xKey="bucket"
                series={[{ key: 'TOTAL', label: 'All payment modes' }]} height={280} />
            </Card>
          </Section>

          <Section title="By payment mode">
            <Grid container spacing={2}>
              {v.channels.map((c, i) => (
                <Grid item xs={12} sm={6} md={4} lg={2} key={c.key}>
                  <Card variant="outlined" sx={{ ...panel, cursor: 'pointer', opacity: c.total ? 1 : 0.75 }} onClick={() => setMode(mode === c.key ? '' : c.key)}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: colors[i] }} />
                      <Typography variant="body2" fontWeight={800}>{c.label.toUpperCase()}</Typography>
                    </Box>
                    <Typography variant="h5" fontWeight={800} sx={{ mt: 1 }}>{inr(c.amount)}</Typography>
                    <Typography variant="body2" color="text.secondary">{c.total.toLocaleString('en-IN')} txns · {c.successRate == null ? '—' : `${c.successRate}% approved`}</Typography>
                    <Sparkwave rows={v.series} dataKey={`${c.key}_amount`} color={colors[i]} />
                    {c.mappingNote && <Typography variant="caption" color="text.secondary">{c.mappingNote}</Typography>}
                  </Card>
                </Grid>
              ))}
            </Grid>
          </Section>

          <Grid container spacing={2} sx={{ mb: 3 }}>
            <Grid item xs={12} lg={7}>
              <Card variant="outlined" sx={panel}>
                <WaveChart title="Payment mode mix over time" subtitle={sub} rows={v.series} xKey="bucket" series={channelSeries} height={300} />
              </Card>
            </Grid>
            <Grid item xs={12} lg={5}>
              <Card variant="outlined" sx={panel}>
                <Typography variant="subtitle1" fontWeight={700}>Top 10 merchants by approved value</Typography>
                <Typography variant="caption" color="text.secondary">{sub}</Typography>
                <Box sx={{ mt: 1.5 }}>
                  {v.topMerchants.length === 0 && <Typography color="text.secondary">No approved transactions.</Typography>}
                  {v.topMerchants.map((m) => {
                    const max = Number(v.topMerchants[0]?.amount ?? 1) || 1;
                    return (
                      <Tooltip key={m.merchantNumber} title={`MID ${m.merchantNumber} · ${m.count} transactions · ${inr(m.amount, false)}`}>
                        <Box sx={{ display: 'grid', gridTemplateColumns: '140px 1fr 72px', alignItems: 'center', gap: 1, mb: 0.75 }}>
                          <Typography variant="body2" noWrap>{m.merchantName}</Typography>
                          <Box sx={{ height: 14, borderRadius: '0 4px 4px 0', bgcolor: colors[1], width: `${Math.max(2, (100 * Number(m.amount)) / max)}%` }} />
                          <Typography variant="body2" fontWeight={700} textAlign="right">{inr(m.amount)}</Typography>
                        </Box>
                      </Tooltip>
                    );
                  })}
                </Box>
              </Card>
            </Grid>
          </Grid>

          <Section title="Needs attention" action={<Button size="small" onClick={() => nav('/runs')}>All alerts</Button>}>
            <Card variant="outlined" sx={panel}>
              {issues.length === 0 ? <Typography>No open issues from the latest checks.</Typography> : issues.map((i, n) => (
                <Box key={n} sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', py: 1, borderBottom: n < issues.length - 1 ? '1px dashed' : 'none', borderColor: 'divider' }}>
                  <Chip size="small" label={i.level === 'high' ? 'High' : 'Review'} variant="outlined"
                    sx={{ borderWidth: 2, borderColor: i.level === 'high' ? STATUS.critical : STATUS.warning, minWidth: 70 }} />
                  <Typography sx={{ flex: 1 }}>{i.text}</Typography>
                  <Button size="small" onClick={() => nav(i.link)}>Details</Button>
                </Box>
              ))}
            </Card>
          </Section>
        </>
      )}
    </Box>
  );
}
