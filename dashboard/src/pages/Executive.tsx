import { Alert, Box, Button, Card, Chip, Grid, LinearProgress, MenuItem, TextField, Tooltip, Typography } from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import ReportProblemIcon from '@mui/icons-material/ReportProblem';
import { ReactNode, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get, Kpi, Row } from '../api';
import { useAsync } from '../components/Common';
import { useFilters } from '../components/FilterContext';
import { GradientBars, inr, NetAcc, NetLink, PaymentNetwork, Ring, Sparkwave, WaveChart } from '../components/Waves';
import { seriesColors, STATUS } from '../palette';
import { NAVY, useIsDark } from '../theme';

interface Channel {
  key: string; label: string; total: number; amount: number; approved: number; declined: number; refunded: number;
  other: number; successRate: number | null; net: number; mappingNote?: string | null;
}
interface Volume {
  available: boolean; reason?: string; from: string; to: string; granularity: 'hour' | 'day';
  channels: Channel[]; total: Channel & { unclassified: Record<string, number> }; series: Row[];
  topMerchants: { merchantName: string; merchantNumber: string; count: number; amount: number }[];
  network: { links: NetLink[]; accounting: NetAcc[] };
  source: { lastSuccessAt?: string };
}

const PERIODS: [string, string][] = [['today', 'Today'], ['yesterday', 'Yesterday'], ['last7', 'Last 7 days'], ['month', 'This month']];
const pct = (a: number, b: number) => (b > 0 ? Math.round((1000 * a) / b) / 10 : null);

function usePanel() {
  const dark = useIsDark();
  return {
    p: 2.5, borderRadius: 3, height: '100%',
    boxShadow: dark ? '0 0 0 1px rgba(57,135,229,0.06), 0 8px 24px rgba(0,0,0,0.25)' : '0 1px 3px rgba(0,0,0,0.06)',
  } as const;
}

interface Issue { level: 'high' | 'medium'; text: string; link: string }

/** Turns KPI and alert data into short sentences a manager can act on. */
function issuesFrom(kpis: Kpi[], alerts: Row[]): Issue[] {
  const out: Issue[] = [];
  const k = (key: string) => kpis.find((x) => x.key === key);
  const failingSources = alerts.filter((a) => a.type === 'SOURCE_UNAVAILABLE');
  if (failingSources.length) {
    out.push({ level: 'high', link: '/data-quality',
      text: `${failingSources.length} data source(s) could not be read from UAT (${failingSources.map((a) => String(a.subject).replace(/^cs\.|^acc\./, '').replace(/_/g, ' ')).join(', ')}). Figures that depend on them show "Not available".` });
  }
  const tb = k('tb.difference');
  if (tb?.available && Array.isArray(tb.value)) {
    (tb.value as Row[]).filter((r) => r.outcome === 'FAIL').forEach((r) => {
      const d = Number(r.difference);
      out.push({ level: 'high', link: '/ledger',
        text: `Accounting books do not balance for ${String(r.range).replace('group ', '')}: ${d > 0 ? 'debits exceed credits' : 'credits exceed debits'} by ${inr(Math.abs(d), false)}.` });
    });
  }
  const adj = k('adjustments.unbalanced');
  if (adj?.available && Number(adj.value) > 0) out.push({ level: 'high', link: '/ledger', text: `${adj.value} manual ledger adjustment(s) are one-sided (debit ≠ credit).` });
  const per = k('periods.health');
  if (per?.available && per.value !== 'OK') out.push({ level: 'medium', link: '/ledger', text: `Accounting periods: ${per.value} (e.g. no open period for today, or gaps between periods).` });
  const acc = k('accounting.status');
  if (acc?.available && acc.value && typeof acc.value === 'object') {
    const a = acc.value as Record<string, number>;
    if (a.MISSING) out.push({ level: 'high', link: '/transactions?accountingStatus=MISSING', text: `${a.MISSING} transaction(s) never reached Accounting within the agreed time.` });
    if (a.FAILED) out.push({ level: 'high', link: '/transactions?accountingStatus=FAILED', text: `${a.FAILED} transaction(s) were posted to Accounting with unbalanced entries.` });
  }
  const byRule: Record<string, number> = {};
  alerts.filter((a) => a.type === 'FAIL' || a.type === 'MISSING').forEach((a) => { byRule[String(a.ruleId)] = (byRule[String(a.ruleId)] ?? 0) + 1; });
  const plain: Record<string, string> = {
    R03: 'released transactions have no matching payment record', R04: 'journal vouchers are overdue',
    R05: 'clearing files failed or are missing', R06: 'UPI payout files were not generated (BDF)',
    R07: 'records show "Decryption failed" in bank/account fields', R11: 'Accounting entries appear to be posted twice',
    R15: 'ledger running balances do not add up',
  };
  Object.entries(byRule).filter(([r]) => plain[r]).forEach(([r, n]) => out.push({ level: 'medium', link: '/validation', text: `${n} ${plain[r]}.` }));
  return out;
}

function Tile({ label, value, sub, right, children }: { label: string; value: ReactNode; sub?: ReactNode; right?: ReactNode; children?: ReactNode }) {
  const panel = usePanel();
  return (
    <Card variant="outlined" sx={panel}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="body2" color="text.secondary" fontWeight={600}>{label}</Typography>
          <Typography variant="h4" fontWeight={800} sx={{ mt: 0.5, lineHeight: 1.15 }}>{value}</Typography>
          {sub && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{sub}</Typography>}
        </Box>
        {right}
      </Box>
      {children}
    </Card>
  );
}

export default function Executive() {
  const nav = useNavigate();
  const { meta, version } = useFilters();
  const dark = useIsDark();
  const panel = usePanel();
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
  const reconciledPct = accMap ? pct(reconciled, checked) : null;

  const periodLabel = PERIODS.find((p) => p[0] === period)?.[1];
  const modeLabel = mode ? v?.channels.find((c) => c.key === mode)?.label ?? mode : 'All payment modes';
  const sub = `${periodLabel} · ${processor || 'All processors'} · ${modeLabel}`;
  const healthColor = high ? STATUS.critical : issues.length ? STATUS.warning : STATUS.good;

  const journey = v && [
    { label: 'Received', value: v.total.total, note: inr(v.total.amount) },
    { label: 'Approved', value: v.total.approved, note: `${pct(v.total.approved, v.total.total) ?? '—'}% of received` },
    { label: 'Settled with scheme', value: num('txn.settlementStatus.SETTLED'), note: 'settled with the scheme' },
    { label: 'Paid out to merchants', value: num('txn.paymentRelease.COMPLETED'), note: 'payment released' },
    { label: 'Confirmed in Accounting', value: accMap ? reconciled : null, note: accMap ? `of ${checked} checked so far` : 'not checked yet' },
  ];

  return (
    <Box sx={{ maxWidth: 1440, mx: 'auto' }}>
      {/* Title + filters */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap', mb: 2.5 }}>
        <Box sx={{ flex: 1, minWidth: 260 }}>
          <Typography variant="h5" fontWeight={800}>Executive Summary</Typography>
          <Typography variant="body2" color="text.secondary">Payments volume, settlement, Accounting confirmation and open issues · Toucan UAT</Typography>
          <Typography className="print-only" variant="body2" color="text.secondary">
            {v ? v.from + ' to ' + v.to + ' · ' : ''}{sub} · exported {new Date().toLocaleString('en-IN')}
          </Typography>
        </Box>
        <TextField className="no-print" select size="small" label="Period" value={period} onChange={(e) => setPeriod(e.target.value)} sx={{ minWidth: 150 }}>
          {PERIODS.map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
        </TextField>
        <TextField className="no-print" select size="small" label="Processor" value={processor} onChange={(e) => setProcessor(e.target.value)} sx={{ minWidth: 150 }}>
          <MenuItem value="">All</MenuItem>
          {meta?.processors.map((p) => <MenuItem key={p.code} value={p.code}>{p.displayName}</MenuItem>)}
        </TextField>
        <TextField className="no-print" select size="small" label="Payment mode" value={mode} onChange={(e) => setMode(e.target.value)} sx={{ minWidth: 150 }}>
          <MenuItem value="">All</MenuItem>
          {(v?.channels ?? []).map((c) => <MenuItem key={c.key} value={c.key}>{c.label}</MenuItem>)}
        </TextField>
        <Button className="no-print" variant="outlined" onClick={() => { setPeriod('last7'); setProcessor(''); setMode(''); }}>Reset</Button>
      </Box>

      {(vol.loading || kpis.loading) && <LinearProgress sx={{ mb: 2 }} />}
      {vol.error && <Alert severity="error">Not available: {vol.error}</Alert>}
      {v && !v.available && <Alert severity="warning">Transactions are not available: {v.reason}</Alert>}

      {v?.available && (
        <>
          {/* Row 1: headline tiles */}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={12} sm={6} lg={3}>
              <Tile label="Total value" value={inr(v.total.amount)} sub={`${v.total.total.toLocaleString('en-IN')} transactions · net ${inr(v.total.net)}`}>
                <Sparkwave rows={v.series} dataKey="TOTAL_amount" color={colors[2]} />
              </Tile>
            </Grid>
            <Grid item xs={12} sm={6} lg={3}>
              <Tile label="Approval rate" value={`${v.total.successRate ?? '—'}%`} sub={`${v.total.approved} approved · ${v.total.declined} declined`}
                right={<Ring value={v.total.successRate} />} />
            </Grid>
            <Grid item xs={12} sm={6} lg={3}>
              <Tile label="Confirmed in Accounting" value={reconciledPct == null ? 'Not available' : `${reconciledPct}%`}
                sub={accMap ? `${reconciled} of ${checked} checked` : acc?.reason} right={<Ring value={reconciledPct} />} />
            </Grid>
            <Grid item xs={12} sm={6} lg={3}>
              <Card variant="outlined" sx={{ ...panel, borderColor: healthColor, borderWidth: 1.5, cursor: 'pointer' }} onClick={() => nav('/runs')}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography variant="body2" color="text.secondary" fontWeight={600}>Overall health</Typography>
                  {high ? <ErrorIcon sx={{ color: STATUS.critical }} /> : issues.length ? <ReportProblemIcon sx={{ color: STATUS.warning }} /> : <CheckCircleIcon sx={{ color: STATUS.good }} />}
                </Box>
                <Typography variant="h4" fontWeight={800} sx={{ mt: 0.5 }}>{high ? 'Needs attention' : issues.length ? 'Review' : 'Healthy'}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {high ? `${high} high-priority issue${high > 1 ? 's' : ''}` : issues.length ? `${issues.length} item(s) to review` : 'All checks passing'}
                </Typography>
              </Card>
            </Grid>
          </Grid>

          {/* Row 2: wave + gradient bars */}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={12} lg={7}>
              <Card variant="outlined" sx={panel}>
                <WaveChart title="Volume trends by payment mode" subtitle={sub} rows={v.series} xKey="bucket"
                  series={v.channels.map((c) => ({ key: c.key, label: c.label }))} height={290} />
              </Card>
            </Grid>
            <Grid item xs={12} lg={5}>
              <Card variant="outlined" sx={panel}>
                <GradientBars title={v.granularity === 'hour' ? 'Value by hour' : 'Value by day'} subtitle={sub}
                  rows={v.series.map((r) => ({ bucket: r.bucket, value: Number(r.TOTAL_amount ?? 0) }))} xKey="bucket" valueKey="value"
                  color={colors[0]} format={(n) => inr(n)} />
              </Card>
            </Grid>
          </Grid>

          {/* Row 3: network */}
          <Card variant="outlined" sx={{ ...panel, mb: 2, ...(dark ? { borderColor: 'rgba(57,135,229,0.45)' } : {}) }}>
            <Typography variant="subtitle1" fontWeight={700}>Payment network</Typography>
            <Typography variant="caption" color="text.secondary">Where the money flows: processors → payment modes → Accounting · {sub}</Typography>
            <Box sx={{ mt: 1 }}>
              <PaymentNetwork links={v.network.links} accounting={v.network.accounting} modes={v.channels.map((c) => ({ key: c.key, label: c.label }))} />
            </Box>
          </Card>

          {/* Row 4: journey + attention */}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={12} lg={7}>
              <Card variant="outlined" sx={panel}>
                <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1.5 }}>Money journey</Typography>
                {journey?.map((s, i) => {
                  const base = v.total.total || 1;
                  const w = s.value == null ? 0 : Math.max(2, (100 * s.value) / base);
                  return (
                    <Box key={s.label} sx={{ display: 'grid', gridTemplateColumns: '190px 1fr 170px', alignItems: 'center', gap: 1.5, mb: 1.25 }}>
                      <Typography variant="body2" fontWeight={600}>{i + 1}. {s.label}</Typography>
                      <Box sx={{ height: 12, borderRadius: 6, bgcolor: dark ? NAVY.border : '#eceae6', overflow: 'hidden' }}>
                        <Box sx={{ height: '100%', width: `${w}%`, borderRadius: 6, background: `linear-gradient(90deg, ${colors[0]}66, ${colors[0]})` }} />
                      </Box>
                      <Box sx={{ textAlign: 'right' }}>
                        <Typography variant="body2" fontWeight={800}>{s.value == null ? 'Not available' : s.value.toLocaleString('en-IN')}</Typography>
                        <Typography variant="caption" color="text.secondary">{s.note}</Typography>
                      </Box>
                    </Box>
                  );
                })}
              </Card>
            </Grid>
            <Grid item xs={12} lg={5}>
              <Card variant="outlined" sx={panel}>
                <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                  <Typography variant="subtitle1" fontWeight={700} sx={{ flex: 1 }}>Needs attention</Typography>
                  <Button size="small" onClick={() => nav('/runs')}>All alerts</Button>
                </Box>
                {issues.length === 0 ? <Typography>No open issues from the latest checks.</Typography> : issues.slice(0, 6).map((i, n) => (
                  <Box key={n} sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start', py: 1, borderBottom: n < Math.min(issues.length, 6) - 1 ? '1px dashed' : 'none', borderColor: 'divider', cursor: 'pointer' }}
                    onClick={() => nav(i.link)}>
                    <Chip size="small" label={i.level === 'high' ? 'High' : 'Review'} variant="outlined"
                      sx={{ borderWidth: 2, borderColor: i.level === 'high' ? STATUS.critical : STATUS.warning, minWidth: 66 }} />
                    <Typography variant="body2" sx={{ flex: 1 }}>{i.text}</Typography>
                  </Box>
                ))}
                {issues.length > 6 && <Typography variant="caption" color="text.secondary">+{issues.length - 6} more under Runs &amp; Alerts</Typography>}
              </Card>
            </Grid>
          </Grid>

          {/* Row 5: payment modes + merchants */}
          <Grid container spacing={2}>
            <Grid item xs={12} lg={7}>
              <Grid container spacing={2}>
                {v.channels.map((c, i) => (
                  <Grid item xs={12} sm={6} md={4} key={c.key}>
                    <Card variant="outlined" sx={{ ...panel, p: 2, cursor: 'pointer', outline: mode === c.key ? `2px solid ${colors[i]}` : 'none' }}
                      onClick={() => setMode(mode === c.key ? '' : c.key)}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: colors[i], boxShadow: dark ? `0 0 8px ${colors[i]}` : 'none' }} />
                        <Typography variant="body2" fontWeight={800}>{c.label}</Typography>
                        <Box sx={{ flex: 1 }} />
                        <Typography variant="caption" color="text.secondary">{c.successRate == null ? '—' : `${c.successRate}% ok`}</Typography>
                      </Box>
                      <Typography variant="h6" fontWeight={800} sx={{ mt: 0.5 }}>{inr(c.amount)}</Typography>
                      <Typography variant="caption" color="text.secondary">{c.total.toLocaleString('en-IN')} transactions</Typography>
                      <Sparkwave rows={v.series} dataKey={`${c.key}_amount`} color={colors[i]} height={36} />
                      {c.mappingNote && <Tooltip title={c.mappingNote}><Typography variant="caption" color="text.secondary">No data for this mode</Typography></Tooltip>}
                    </Card>
                  </Grid>
                ))}
              </Grid>
            </Grid>
            <Grid item xs={12} lg={5}>
              <Card variant="outlined" sx={panel}>
                <Typography variant="subtitle1" fontWeight={700}>Top 10 merchants</Typography>
                <Typography variant="caption" color="text.secondary">By approved value · {sub}</Typography>
                <Box sx={{ mt: 1.5 }}>
                  {v.topMerchants.length === 0 && <Typography color="text.secondary">No approved transactions.</Typography>}
                  {v.topMerchants.map((m) => {
                    const max = Number(v.topMerchants[0]?.amount ?? 1) || 1;
                    return (
                      <Tooltip key={m.merchantNumber} title={`MID ${m.merchantNumber} · ${m.count} transactions · ${inr(m.amount, false)}`}>
                        <Box sx={{ display: 'grid', gridTemplateColumns: '130px 1fr 70px', alignItems: 'center', gap: 1, mb: 0.9 }}>
                          <Typography variant="body2" noWrap>{m.merchantName}</Typography>
                          <Box sx={{ height: 12, borderRadius: '0 6px 6px 0', width: `${Math.max(2, (100 * Number(m.amount)) / max)}%`,
                            background: `linear-gradient(90deg, ${colors[6]}66, ${colors[6]})` }} />
                          <Typography variant="body2" fontWeight={700} textAlign="right">{inr(m.amount)}</Typography>
                        </Box>
                      </Tooltip>
                    );
                  })}
                </Box>
              </Card>
            </Grid>
          </Grid>

          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
            {v.source?.lastSuccessAt ? `Data as of ${new Date(v.source.lastSuccessAt).toLocaleString('en-IN')}. ` : ''}
            Approval rate = approved ÷ (approved + declined). Payment mode from schemeCode. Every figure links to its source records in the detailed screens.
          </Typography>
        </>
      )}
    </Box>
  );
}
