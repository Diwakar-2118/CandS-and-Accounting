import { Box, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { ReactNode, useMemo, useState } from 'react';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Row } from '../api';
import { seriesColors, STATUS } from '../palette';
import { DataTable } from './Common';
import { NAVY, useIsDark } from '../theme';

export const inr = (v: unknown, compact = true): string => {
  const n = Number(v ?? 0);
  if (!compact) return '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  if (Math.abs(n) >= 1e7) return '₹' + (n / 1e7).toFixed(2) + ' Cr';
  if (Math.abs(n) >= 1e5) return '₹' + (n / 1e5).toFixed(2) + ' L';
  return '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 0 });
};

const safeId = (s: string) => s.replace(/[^a-zA-Z0-9]/g, '');

function Gradient({ id, color, top = 0.4 }: { id: string; color: string; top?: number }) {
  return (
    <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stopColor={color} stopOpacity={top} />
      <stop offset="100%" stopColor={color} stopOpacity={0.02} />
    </linearGradient>
  );
}

/** Soft glow behind wave strokes (dark mode only). */
function Glow({ id }: { id: string }) {
  return (
    <filter id={id} x="-10%" y="-30%" width="120%" height="160%">
      <feGaussianBlur stdDeviation="3" result="b" />
      <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
    </filter>
  );
}

function useAxis() {
  const dark = useIsDark();
  const ink = dark ? NAVY.muted : '#52514e';
  return {
    tick: { fontSize: 12, fill: ink },
    grid: dark ? NAVY.border : '#e6e6e3',
    tooltip: dark
      ? { contentStyle: { background: NAVY.surface, border: `1px solid ${NAVY.border}`, borderRadius: 8, color: NAVY.text }, labelStyle: { color: NAVY.text } }
      : { contentStyle: { borderRadius: 8 } },
  };
}

/** Tiny wave for a tile: one series, no axes. */
export function Sparkwave({ rows, dataKey, color, height = 44 }: { rows: Row[]; dataKey: string; color: string; height?: number }) {
  const dark = useIsDark();
  const id = useMemo(() => 'sw' + safeId(dataKey) + Math.random().toString(36).slice(2, 7), [dataKey]);
  return (
    <Box sx={{ height }}>
      <ResponsiveContainer>
        <AreaChart data={rows} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
          <defs><Gradient id={id} color={color} />{dark && <Glow id={id + 'g'} />}</defs>
          <Area type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} fill={`url(#${id})`} dot={false}
            isAnimationActive={false} connectNulls style={dark ? { filter: `url(#${id}g)` } : undefined} />
        </AreaChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Ring gauge for a single percentage (status colour + text, never colour alone). */
export function Ring({ value, size = 56 }: { value: number | null; size?: number }) {
  const dark = useIsDark();
  const r = (size - 8) / 2;
  const c = 2 * Math.PI * r;
  const v = value == null ? 0 : Math.max(0, Math.min(100, value));
  const color = value == null ? NAVY.muted : v >= 95 ? STATUS.good : v >= 80 ? STATUS.warning : STATUS.critical;
  return (
    <svg width={size} height={size} role="img" aria-label={value == null ? 'not available' : `${v}%`}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={dark ? NAVY.border : '#e6e6e3'} strokeWidth={6} />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={6} strokeLinecap="round"
        strokeDasharray={`${(c * v) / 100} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
    </svg>
  );
}

export interface WaveSeries { key: string; label: string; color?: string }

/**
 * Smooth area ("wave") chart with glow in dark mode. One measure at a time (count OR amount) so
 * there is never a second axis. Series colours follow a fixed order, so an entity keeps its colour.
 */
export function WaveChart({ title, subtitle, rows, xKey, series, height = 300 }: {
  title: string; subtitle?: string; rows: Row[]; xKey: string; series: WaveSeries[]; height?: number;
}) {
  const dark = useIsDark();
  const ax = useAxis();
  const [measure, setMeasure] = useState<'count' | 'amount'>('amount');
  const [table, setTable] = useState(false);
  const colors = seriesColors(dark);
  const colorOf = (s: WaveSeries, i: number) => s.color ?? colors[i % 8];
  const present = series.filter((s) => rows.some((r) => Number(r[`${s.key}_${measure}`] ?? 0) !== 0));
  const data = rows.map((r) => {
    const o: Row = { [xKey]: r[xKey] };
    series.forEach((s) => { o[s.label] = Number(r[`${s.key}_${measure}`] ?? 0); });
    return o;
  });
  const gid = 'w' + safeId(title);
  return (
    <Box>
      <Header title={title} subtitle={subtitle}>
        <ToggleButtonGroup size="small" exclusive value={measure} onChange={(_, v) => v && setMeasure(v)}>
          <ToggleButton value="amount">Amount</ToggleButton>
          <ToggleButton value="count">Count</ToggleButton>
        </ToggleButtonGroup>
        <ToggleButtonGroup size="small" exclusive value={table ? 't' : 'c'} onChange={(_, v) => v && setTable(v === 't')}>
          <ToggleButton value="c">Chart</ToggleButton>
          <ToggleButton value="t">Table</ToggleButton>
        </ToggleButtonGroup>
      </Header>
      {rows.length === 0 ? <Empty /> : table ? <DataTable rows={data} /> : (
        <Box sx={{ height }}>
          <ResponsiveContainer>
            <AreaChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
              <defs>
                {series.map((s, i) => <Gradient key={s.key} id={gid + safeId(s.key)} color={colorOf(s, i)} />)}
                {dark && <Glow id={gid + 'glow'} />}
              </defs>
              <CartesianGrid vertical={false} stroke={ax.grid} strokeDasharray="3 4" />
              <XAxis dataKey={xKey} tick={ax.tick} tickLine={false} axisLine={false} />
              <YAxis tick={ax.tick} tickLine={false} axisLine={false} width={64}
                tickFormatter={(v: number) => (measure === 'amount' ? inr(v) : v.toLocaleString('en-IN'))} />
              <Tooltip {...ax.tooltip} cursor={{ stroke: ax.tick.fill, strokeDasharray: '3 3' }}
                formatter={(v: number) => (measure === 'amount' ? inr(v, false) : v.toLocaleString('en-IN'))} />
              {present.length > 1 && <Legend wrapperStyle={{ fontSize: 12 }} />}
              {series.map((s, i) => present.includes(s) && (
                <Area key={s.key} type="monotone" dataKey={s.label} stroke={colorOf(s, i)} strokeWidth={2.5}
                  fill={`url(#${gid + safeId(s.key)})`} dot={false} activeDot={{ r: 5, strokeWidth: 2 }}
                  style={dark ? { filter: `url(#${gid}glow)` } : undefined} />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Box>
  );
}

/** Vertical bars with a single-hue gradient (top bright, base faded); one measure. */
export function GradientBars({ title, subtitle, rows, xKey, valueKey, color, format }: {
  title: string; subtitle?: string; rows: Row[]; xKey: string; valueKey: string; color: string; format?: (v: number) => string;
}) {
  const ax = useAxis();
  const [table, setTable] = useState(false);
  const id = 'gb' + safeId(title);
  const fmt = format ?? ((v: number) => v.toLocaleString('en-IN'));
  return (
    <Box>
      <Header title={title} subtitle={subtitle}>
        <ToggleButtonGroup size="small" exclusive value={table ? 't' : 'c'} onChange={(_, v) => v && setTable(v === 't')}>
          <ToggleButton value="c">Chart</ToggleButton>
          <ToggleButton value="t">Table</ToggleButton>
        </ToggleButtonGroup>
      </Header>
      {rows.length === 0 ? <Empty /> : table ? <DataTable rows={rows.map((r) => ({ [xKey]: r[xKey], [valueKey]: r[valueKey] }))} /> : (
        <Box sx={{ height: 260 }}>
          <ResponsiveContainer>
            <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
              <defs>
                <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={color} stopOpacity={1} />
                  <stop offset="100%" stopColor={color} stopOpacity={0.35} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} stroke={ax.grid} strokeDasharray="3 4" />
              <XAxis dataKey={xKey} tick={ax.tick} tickLine={false} axisLine={false} />
              <YAxis tick={ax.tick} tickLine={false} axisLine={false} width={64} tickFormatter={fmt} />
              <Tooltip {...ax.tooltip} cursor={{ fill: 'rgba(128,128,128,0.08)' }} formatter={(v: number) => fmt(v)} />
              <Bar dataKey={valueKey} fill={`url(#${id})`} radius={[4, 4, 0, 0]} maxBarSize={34} />
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Box>
  );
}

function Header({ title, subtitle, children }: { title: string; subtitle?: string; children?: ReactNode }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
      <Box sx={{ flex: 1, minWidth: 200 }}>
        <Typography variant="subtitle1" fontWeight={700}>{title}</Typography>
        {subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}
      </Box>
      {children}
    </Box>
  );
}

function Empty() {
  return <Typography color="text.secondary" sx={{ py: 6, textAlign: 'center' }}>No transactions in this period.</Typography>;
}

// ---------------------------------------------------------------- payment network map

export interface NetLink { processor: string; mode: string; count: number; amount: number }
export interface NetAcc { mode: string; confirmed: number; issues: number; notChecked: number }

/**
 * Processors -> payment modes -> Accounting. Node size = value through it; link width = value on that
 * path; mode colour = the same fixed colour as everywhere else. Hover any node or link for numbers.
 */
export function PaymentNetwork({ links, accounting, modes }: {
  links: NetLink[]; accounting: NetAcc[]; modes: { key: string; label: string }[];
}) {
  const dark = useIsDark();
  const colors = seriesColors(dark);
  const ink = dark ? NAVY.text : '#0b0b0b';
  const muted = dark ? NAVY.muted : '#52514e';
  const W = 980, xP = 150, xM = 520, xA = 860;
  const processors = Array.from(new Set(links.map((l) => l.processor))).sort();
  const modeKeys = [...modes.map((m) => m.key), 'OTHER'].filter((k) => links.some((l) => l.mode === k));
  const modeLabel = (k: string) => modes.find((m) => m.key === k)?.label ?? 'Other / unclassified';
  const modeColor = (k: string) => { const i = modes.findIndex((m) => m.key === k); return i >= 0 ? colors[i] : muted; };
  const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
  const pAmt = (p: string) => sum(links.filter((l) => l.processor === p).map((l) => l.amount));
  const mAmt = (m: string) => sum(links.filter((l) => l.mode === m).map((l) => l.amount));
  const maxNode = Math.max(1, ...processors.map(pAmt), ...modeKeys.map(mAmt));
  const maxLink = Math.max(1, ...links.map((l) => l.amount));
  const radius = (v: number) => 7 + 19 * Math.sqrt(v / maxNode);
  // Enough vertical room per mode for label above + node + amount below (~78px), headers kept clear.
  const TOP = 86, BOTTOM = 46;
  const H = Math.max(420, TOP + BOTTOM + 78 * (Math.max(modeKeys.length, 2) - 1), TOP + BOTTOM + 44 * (Math.max(processors.length, 2) - 1));
  const yOf = (i: number, n: number) => (n <= 1 ? (TOP + H - BOTTOM) / 2 : TOP + (i * (H - TOP - BOTTOM)) / (n - 1));
  const pY = Object.fromEntries(processors.map((p, i) => [p, yOf(i, processors.length)]));
  const mY = Object.fromEntries(modeKeys.map((m, i) => [m, yOf(i, modeKeys.length)]));
  const curve = (x1: number, y1: number, x2: number, y2: number) => `M${x1},${y1} C${(x1 + x2) / 2},${y1} ${(x1 + x2) / 2},${y2} ${x2},${y2}`;
  const totalConfirmed = sum(accounting.map((a) => a.confirmed));
  const totalIssues = sum(accounting.map((a) => a.issues));
  const totalNot = sum(accounting.map((a) => a.notChecked));

  if (links.length === 0) return <Empty />;
  return (
    <Box sx={{ width: '100%', overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', minWidth: 640, height: 'auto' }} role="img" aria-label="Payment network">
        <defs>
          <filter id="netglow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="4" /></filter>
        </defs>
        {[['Processors', xP], ['Payment modes', xM], ['Accounting', xA]].map(([t, x]) => (
          <text key={t as string} x={x as number} y={16} textAnchor="middle" fill={muted} fontSize={12} fontWeight={700}>{(t as string).toUpperCase()}</text>
        ))}
        {links.map((l) => (
          <path key={l.processor + l.mode} d={curve(xP, pY[l.processor], xM, mY[l.mode])} fill="none" stroke={modeColor(l.mode)}
            strokeOpacity={dark ? 0.55 : 0.45} strokeWidth={1 + 10 * (l.amount / maxLink)}>
            <title>{`${l.processor} → ${modeLabel(l.mode)}: ${l.count.toLocaleString('en-IN')} txns, ${inr(l.amount, false)}`}</title>
          </path>
        ))}
        {modeKeys.map((m) => {
          const a = accounting.find((x) => x.mode === m);
          const total = a ? a.confirmed + a.issues + a.notChecked : 0;
          return (
            <path key={'acc' + m} d={curve(xM, mY[m], xA, H / 2)} fill="none" stroke={a && a.confirmed ? STATUS.good : muted}
              strokeOpacity={a && a.confirmed ? 0.6 : 0.25} strokeDasharray={a && a.confirmed ? undefined : '4 5'}
              strokeWidth={1 + 8 * ((a?.confirmed ?? 0) / Math.max(1, totalConfirmed))}>
              <title>{`${modeLabel(m)} → Accounting: ${a?.confirmed ?? 0} confirmed, ${a?.issues ?? 0} with issues, ${a?.notChecked ?? 0} not checked yet (of ${total})`}</title>
            </path>
          );
        })}
        {processors.map((p) => (
          <g key={p}>
            {dark && <circle cx={xP} cy={pY[p]} r={radius(pAmt(p)) + 4} fill={colors[0]} opacity={0.35} filter="url(#netglow)" />}
            <circle cx={xP} cy={pY[p]} r={radius(pAmt(p))} fill={colors[0]} stroke={dark ? NAVY.panel : '#fff'} strokeWidth={2}>
              <title>{`${p}: ${inr(pAmt(p), false)}`}</title>
            </circle>
            <text x={xP - radius(pAmt(p)) - 8} y={pY[p] + 4} textAnchor="end" fill={ink} fontSize={13}>{p}</text>
          </g>
        ))}
        {modeKeys.map((m) => (
          <g key={m}>
            {dark && <circle cx={xM} cy={mY[m]} r={radius(mAmt(m)) + 4} fill={modeColor(m)} opacity={0.35} filter="url(#netglow)" />}
            <circle cx={xM} cy={mY[m]} r={radius(mAmt(m))} fill={modeColor(m)} stroke={dark ? NAVY.panel : '#fff'} strokeWidth={2}>
              <title>{`${modeLabel(m)}: ${inr(mAmt(m), false)}`}</title>
            </circle>
            <text x={xM} y={mY[m] - radius(mAmt(m)) - 6} textAnchor="middle" fill={ink} fontSize={13} fontWeight={600}>{modeLabel(m)}</text>
            <text x={xM} y={mY[m] + radius(mAmt(m)) + 14} textAnchor="middle" fill={muted} fontSize={11}>{inr(mAmt(m))}</text>
          </g>
        ))}
        <g>
          {dark && <circle cx={xA} cy={H / 2} r={34} fill={STATUS.good} opacity={0.3} filter="url(#netglow)" />}
          <circle cx={xA} cy={H / 2} r={30} fill={dark ? NAVY.surface : '#fff'} stroke={STATUS.good} strokeWidth={3} />
          <text x={xA} y={H / 2 + 5} textAnchor="middle" fill={ink} fontSize={13} fontWeight={700}>{totalConfirmed}</text>
          <text x={xA} y={H / 2 + 52} textAnchor="middle" fill={ink} fontSize={12}>confirmed</text>
          <text x={xA} y={H / 2 + 68} textAnchor="middle" fill={muted} fontSize={11}>{totalIssues} with issues · {totalNot} not checked</text>
        </g>
      </svg>
      <Typography variant="caption" color="text.secondary">
        Hover a node or link for exact figures. Link width = value on that path; green links to Accounting = transactions confirmed there (dashed = none confirmed yet).
      </Typography>
    </Box>
  );
}

