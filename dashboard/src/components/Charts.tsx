import { Box, Button, Card, CardContent, Typography, useMediaQuery } from '@mui/material';
import { ReactNode, useState } from 'react';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Row } from '../api';
import { colorFor, statusColor } from '../palette';
import { DataTable } from './Common';

const AXIS = { fontSize: 12 };

/** Card with a chart/table toggle: every chart has a table view. */
export function ChartCard({ title, rows, children, note }: { title: string; rows: Row[]; children: ReactNode; note?: string }) {
  const [table, setTable] = useState(false);
  return (
    <Card variant="outlined">
      <CardContent>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="subtitle1" fontWeight={600}>{title}</Typography>
          <Button size="small" onClick={() => setTable(!table)}>{table ? 'Chart' : 'Table'}</Button>
        </Box>
        {note && <Typography variant="caption" color="text.secondary">{note}</Typography>}
        {rows.length === 0 ? <Typography color="text.secondary" sx={{ py: 4 }}>No data for this filter.</Typography>
          : table ? <DataTable rows={rows} /> : <Box sx={{ height: 280 }}><ResponsiveContainer>{children as never}</ResponsiveContainer></Box>}
      </CardContent>
    </Card>
  );
}

function useDark() {
  return useMediaQuery('(prefers-color-scheme: dark)');
}

/** Series keys of the form "<entity><suffix>" present in rows, sorted. */
function entities(rows: Row[], suffix: string): string[] {
  return Array.from(new Set(rows.flatMap((r) => Object.keys(r).filter((k) => k.endsWith(suffix)).map((k) => k.slice(0, -suffix.length))))).sort();
}

/** One measure per chart (no dual axis): count OR amount, one line per processor. */
export function ProcessorLines({ rows, suffix, title, allProcessors }: { rows: Row[]; suffix: string; title: string; allProcessors: string[] }) {
  const dark = useDark();
  const ents = entities(rows, suffix);
  const universe = Array.from(new Set([...allProcessors, ...ents]));
  return (
    <ChartCard title={title} rows={rows}>
      <AreaChart data={rows} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
        <defs>{ents.map((e) => <linearGradient id={`pl-${suffix}-${e}`.replace(/W/g, '')} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={colorFor(e, universe, dark)} stopOpacity={0.35} /><stop offset="100%" stopColor={colorFor(e, universe, dark)} stopOpacity={0.02} /></linearGradient>)}</defs>
        <CartesianGrid vertical={false} strokeOpacity={0.25} />
        <XAxis dataKey="date" tick={AXIS} />
        <YAxis tick={AXIS} tickFormatter={(v: number) => v.toLocaleString('en-IN', { notation: 'compact' })} />
        <Tooltip formatter={(v: number) => v.toLocaleString('en-IN', { maximumFractionDigits: 2 })} />
        {ents.length > 1 && <Legend />}
        {ents.map((e) => (
          <Area key={e} type="monotone" dataKey={e + suffix} name={e} stroke={colorFor(e, universe, dark)} strokeWidth={2}
            fill={`url(#${`pl-${suffix}-${e}`.replace(/W/g, '')})`} dot={false} activeDot={{ r: 5 }} />
        ))}
      </AreaChart>
    </ChartCard>
  );
}

export function TwoLines({ rows, a, b, title, labels }: { rows: Row[]; a: string; b: string; title: string; labels: [string, string] }) {
  const dark = useDark();
  const c = [colorFor('a', ['a', 'b'], dark), colorFor('b', ['a', 'b'], dark)];
  return (
    <ChartCard title={title} rows={rows} note="Accounting = transactions with postings found by RRN.">
      <AreaChart data={rows} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
        <defs><linearGradient id={'tl-a'} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={c[0]} stopOpacity={0.35} /><stop offset="100%" stopColor={c[0]} stopOpacity={0.02} /></linearGradient><linearGradient id={'tl-b'} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={c[1]} stopOpacity={0.35} /><stop offset="100%" stopColor={c[1]} stopOpacity={0.02} /></linearGradient></defs>
        <CartesianGrid vertical={false} strokeOpacity={0.25} />
        <XAxis dataKey="date" tick={AXIS} />
        <YAxis tick={AXIS} />
        <Tooltip />
        <Legend />
        <Area type="monotone" dataKey={a} name={labels[0]} stroke={c[0]} strokeWidth={2} fill="url(#tl-a)" dot={false} activeDot={{ r: 5 }} />
        <Area type="monotone" dataKey={b} name={labels[1]} stroke={c[1]} strokeWidth={2} fill="url(#tl-b)" dot={false} activeDot={{ r: 5 }} />
      </AreaChart>
    </ChartCard>
  );
}

/** Status split: status colours are reserved for status and each bar is labelled. */
export function StatusBars({ rows, labelKey, valueKey, title }: { rows: Row[]; labelKey: string; valueKey: string; title: string }) {
  return (
    <ChartCard title={title} rows={rows}>
      <BarChart data={rows} layout="vertical" margin={{ top: 8, right: 24, bottom: 0, left: 24 }}>
        <CartesianGrid horizontal={false} strokeOpacity={0.25} />
        <XAxis type="number" tick={AXIS} />
        <YAxis type="category" dataKey={labelKey} tick={AXIS} width={110} />
        <Tooltip />
        <Bar dataKey={valueKey} radius={[0, 4, 4, 0]} label={{ position: 'right', fontSize: 12 }} maxBarSize={28}>
          {rows.map((r, i) => <Cell key={i} fill={statusColor(String(r[labelKey]))} />)}
        </Bar>
      </BarChart>
    </ChartCard>
  );
}

/** Stacked bars; keys are entities (processors) or statuses. */
export function StackedBars({ rows, xKey, title, colorMode, allEntities = [] }: {
  rows: Row[]; xKey: string; title: string; colorMode: 'status' | 'entity'; allEntities?: string[];
}) {
  const dark = useDark();
  const keys = Array.from(new Set(rows.flatMap((r) => Object.keys(r).filter((k) => k !== xKey)))).sort();
  const universe = Array.from(new Set([...allEntities, ...keys]));
  return (
    <ChartCard title={title} rows={rows}>
      <BarChart data={rows} margin={{ top: 8, right: 16, bottom: 0, left: 8 }}>
        <CartesianGrid vertical={false} strokeOpacity={0.25} />
        <XAxis dataKey={xKey} tick={AXIS} />
        <YAxis tick={AXIS} allowDecimals={false} />
        <Tooltip />
        {keys.length > 1 && <Legend />}
        {keys.map((k, i) => (
          <Bar key={k} dataKey={k} stackId="s" fill={colorMode === 'status' ? statusColor(k) : colorFor(k, universe, dark)}
            stroke={dark ? '#1a1a19' : '#fcfcfb'} strokeWidth={2} radius={i === keys.length - 1 ? [4, 4, 0, 0] : 0} maxBarSize={48} />
        ))}
      </BarChart>
    </ChartCard>
  );
}
