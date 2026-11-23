import { Box, ToggleButton, ToggleButtonGroup, Typography, useMediaQuery } from '@mui/material';
import { useState } from 'react';
import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Row } from '../api';
import { seriesColors } from '../palette';
import { DataTable } from './Common';

export const inr = (v: unknown, compact = true): string => {
  const n = Number(v ?? 0);
  if (!compact) return '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  if (Math.abs(n) >= 1e7) return '₹' + (n / 1e7).toFixed(2) + ' Cr';
  if (Math.abs(n) >= 1e5) return '₹' + (n / 1e5).toFixed(2) + ' L';
  return '₹' + n.toLocaleString('en-IN', { maximumFractionDigits: 0 });
};

function Gradient({ id, color }: { id: string; color: string }) {
  return (
    <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stopColor={color} stopOpacity={0.35} />
      <stop offset="100%" stopColor={color} stopOpacity={0.02} />
    </linearGradient>
  );
}

/** Tiny wave for a tile: one series, no axes. */
export function Sparkwave({ rows, dataKey, color }: { rows: Row[]; dataKey: string; color: string }) {
  const id = 'sw-' + dataKey;
  return (
    <Box sx={{ height: 44 }}>
      <ResponsiveContainer>
        <AreaChart data={rows} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
          <defs><Gradient id={id} color={color} /></defs>
          <Area type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} fill={`url(#${id})`} dot={false}
            isAnimationActive={false} connectNulls />
        </AreaChart>
      </ResponsiveContainer>
    </Box>
  );
}

export interface WaveSeries { key: string; label: string }

/**
 * Smooth area ("wave") chart. One measure at a time (count OR amount, toggled) so there is never a
 * second axis. Series colours follow the fixed series order, so a channel keeps its colour.
 */
export function WaveChart({ title, subtitle, rows, xKey, series, height = 300 }: {
  title: string; subtitle?: string; rows: Row[]; xKey: string; series: WaveSeries[]; height?: number;
}) {
  const dark = useMediaQuery('(prefers-color-scheme: dark)');
  const [measure, setMeasure] = useState<'count' | 'amount'>('amount');
  const [table, setTable] = useState(false);
  const colors = seriesColors(dark);
  const present = series.filter((s) => rows.some((r) => Number(r[`${s.key}_${measure}`] ?? 0) !== 0));
  const data = rows.map((r) => {
    const o: Row = { [xKey]: r[xKey] };
    series.forEach((s) => { o[s.label] = Number(r[`${s.key}_${measure}`] ?? 0); });
    return o;
  });
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
        <Box sx={{ flex: 1, minWidth: 200 }}>
          <Typography variant="subtitle1" fontWeight={700}>{title}</Typography>
          {subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}
        </Box>
        <ToggleButtonGroup size="small" exclusive value={measure} onChange={(_, v) => v && setMeasure(v)}>
          <ToggleButton value="amount">Amount</ToggleButton>
          <ToggleButton value="count">Count</ToggleButton>
        </ToggleButtonGroup>
        <ToggleButtonGroup size="small" exclusive value={table ? 't' : 'c'} onChange={(_, v) => v && setTable(v === 't')}>
          <ToggleButton value="c">Chart</ToggleButton>
          <ToggleButton value="t">Table</ToggleButton>
        </ToggleButtonGroup>
      </Box>
      {rows.length === 0 ? <Typography color="text.secondary" sx={{ py: 6, textAlign: 'center' }}>No transactions in this period.</Typography>
        : table ? <DataTable rows={data} /> : (
          <Box sx={{ height }}>
            <ResponsiveContainer>
              <AreaChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
                <defs>{series.map((s, i) => <Gradient key={s.key} id={`g-${title}-${s.key}`.replace(/\W/g, '')} color={colors[i % 8]} />)}</defs>
                <CartesianGrid vertical={false} strokeOpacity={0.2} />
                <XAxis dataKey={xKey} tick={{ fontSize: 12 }} tickLine={false} axisLine={false} />
                <YAxis tick={{ fontSize: 12 }} tickLine={false} axisLine={false} width={64}
                  tickFormatter={(v: number) => (measure === 'amount' ? inr(v) : v.toLocaleString('en-IN'))} />
                <Tooltip formatter={(v: number) => (measure === 'amount' ? inr(v, false) : v.toLocaleString('en-IN'))} />
                {present.length > 1 && <Legend />}
                {series.map((s, i) => present.includes(s) && (
                  <Area key={s.key} type="monotone" dataKey={s.label} stroke={colors[i % 8]} strokeWidth={2}
                    fill={`url(#${`g-${title}-${s.key}`.replace(/\W/g, '')})`} dot={false} activeDot={{ r: 5 }} />
                ))}
              </AreaChart>
            </ResponsiveContainer>
          </Box>
        )}
    </Box>
  );
}
