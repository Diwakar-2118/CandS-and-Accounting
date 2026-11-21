import {
  Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Dialog, DialogContent, DialogTitle, Table, TableBody,
  TableCell, TableContainer, TableHead, TablePagination, TableRow, Tooltip, Typography,
} from '@mui/material';
import { ReactNode, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { exportUrl, Kpi, Row } from '../api';
import { statusColor } from '../palette';

/** Status chip: colour is never alone - the label is always shown. */
export function StatusChip({ value }: { value?: unknown }) {
  const v = value == null ? '—' : String(value);
  const c = statusColor(v);
  return (
    <Chip size="small" label={v} variant="outlined"
      sx={{ borderColor: c, borderWidth: 2, '& .MuiChip-label': { color: 'text.primary' } }} />
  );
}

export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]): { data?: T; error?: string; loading: boolean } {
  const [s, setS] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  useEffect(() => {
    let live = true;
    setS((x) => ({ ...x, loading: true }));
    fn().then((data) => live && setS({ data, loading: false }))
      .catch((e: Error) => live && setS({ error: e.message, loading: false }));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return s;
}

export function Loadable({ state, children }: { state: { error?: string; loading: boolean }; children: ReactNode }) {
  if (state.error) return <Alert severity="error">Not available: {state.error}</Alert>;
  if (state.loading) return <Typography color="text.secondary">Loading…</Typography>;
  return <>{children}</>;
}

export function ExportButtons({ list, params }: { list: string; params: Record<string, string | undefined> }) {
  return (
    <Box sx={{ display: 'flex', gap: 1 }}>
      <Button size="small" href={exportUrl(list, 'csv', params)}>CSV</Button>
      <Button size="small" href={exportUrl(list, 'xlsx', params)}>XLSX</Button>
    </Box>
  );
}

function fmt(v: unknown): ReactNode {
  if (v == null) return '';
  if (typeof v === 'object') {
    const s = JSON.stringify(v);
    return <Tooltip title={<pre style={{ margin: 0, maxWidth: 600, whiteSpace: 'pre-wrap' }}>{JSON.stringify(v, null, 2)}</pre>}>
      <span>{s.length > 60 ? s.slice(0, 60) + '…' : s}</span></Tooltip>;
  }
  return String(v);
}

const STATUS_COLS = new Set(['outcome', 'status', 'accountingStatus', 'dashboardStatus', 'displayStatus']);

/** Generic table; hides internal source-reference columns (shown in the details dialog and exports). */
export function DataTable({ rows, columns, onRow, title, exportList, exportParams }: {
  rows: Row[]; columns?: string[]; onRow?: (r: Row) => void; title?: string;
  exportList?: string; exportParams?: Record<string, string | undefined>;
}) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [detail, setDetail] = useState<Row>();
  const cols = useMemo(() => columns ?? Array.from(new Set(rows.flatMap((r) => Object.keys(r))))
    .filter((c) => !c.startsWith('_') && c !== 'payload' && c !== 'evidence').slice(0, 14), [rows, columns]);
  useEffect(() => setPage(0), [rows]);
  return (
    <Box sx={{ mb: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle1" fontWeight={600}>{title} <Typography component="span" color="text.secondary">({rows.length})</Typography></Typography>
        {exportList && <ExportButtons list={exportList} params={exportParams ?? {}} />}
      </Box>
      {rows.length === 0 ? <Typography color="text.secondary">No rows.</Typography> : (
        <TableContainer sx={{ maxWidth: '100%', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow>{cols.map((c) => <TableCell key={c}>{c}</TableCell>)}<TableCell /></TableRow></TableHead>
            <TableBody>
              {rows.slice(page * size, page * size + size).map((r, i) => (
                <TableRow key={i} hover>
                  {cols.map((c) => <TableCell key={c}>{STATUS_COLS.has(c) ? <StatusChip value={r[c]} /> : fmt(r[c])}</TableCell>)}
                  <TableCell><Button size="small" onClick={() => (onRow ? onRow(r) : setDetail(r))}>View details</Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <TablePagination component="div" count={rows.length} page={page} rowsPerPage={size}
            onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(+e.target.value); setPage(0); }}
            rowsPerPageOptions={[25, 50, 100]} />
        </TableContainer>
      )}
      <JsonDialog value={detail} onClose={() => setDetail(undefined)} />
    </Box>
  );
}

export function JsonDialog({ value, onClose, title = 'Details and source evidence' }: { value?: unknown; onClose: () => void; title?: string }) {
  return (
    <Dialog open={!!value} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{JSON.stringify(value, null, 2)}</pre>
      </DialogContent>
    </Dialog>
  );
}

function renderValue(v: unknown): ReactNode {
  if (v == null) return '—';
  if (typeof v === 'number') return v.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  if (typeof v === 'string') return /^-?\d+(\.\d+)?$/.test(v) ? Number(v).toLocaleString('en-IN', { maximumFractionDigits: 2 }) : v;
  if (Array.isArray(v)) {
    return v.map((x, i) => <Box key={i} sx={{ fontSize: 13 }}>{Object.entries(x as Row).map(([k, y]) => `${k}: ${y}`).join(' · ')}</Box>);
  }
  return Object.entries(v as Row).map(([k, y]) => <Box key={k} sx={{ fontSize: 14 }}>{k}: <b>{String(y)}</b></Box>);
}

/** KPI tile: "Not available" with the last successful run time when its source failed - never 0. */
export function KpiCard({ k }: { k: Kpi }) {
  const nav = useNavigate();
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      <CardActionArea onClick={() => k.drill && nav(k.drill)} sx={{ height: '100%' }}>
        <CardContent>
          <Typography variant="body2" color="text.secondary">{k.label}</Typography>
          {k.available ? (
            <Typography component="div" variant={typeof k.value === 'object' ? 'body1' : 'h5'} sx={{ mt: 0.5 }}>{renderValue(k.value)}</Typography>
          ) : (
            <>
              <Typography variant="h6" sx={{ mt: 0.5 }}>Not available</Typography>
              <Typography variant="caption" color="text.secondary" component="div">{k.reason}</Typography>
            </>
          )}
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
            Source: {k.sources.map((s) => `${s.endpoint ?? s.dataset}`).join(', ')}
            <br />Last successful: {k.lastSuccessAt ? new Date(k.lastSuccessAt).toLocaleString('en-IN') : 'never'}
          </Typography>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
