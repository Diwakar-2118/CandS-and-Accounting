import {
  Alert, Box, Button, Dialog, DialogContent, DialogTitle, Table, TableBody, TableCell, TableHead, TablePagination,
  TableRow, TextField, Typography,
} from '@mui/material';
import { useState } from 'react';
import { get, Row } from '../api';
import { DataTable, ExportButtons, Loadable, StatusChip, useAsync } from '../components/Common';
import { asParams, useFilters } from '../components/FilterContext';

interface Page {
  content: Row[];
  totalElements: number;
}

const COLS = ['salesDateTime', 'rrn', 'arn', 'invoiceNumber', 'merchantNumber', 'terminalNumber', 'runNumber',
  'schemeCode', 'platformType', 'transactionCode', 'txnAmount', 'netAmount', 'settlementStatus', 'transactionStatus',
  'txnStatus', 'paymentReleaseStatusEnum', 'accountingStatus'];

const LABELS: Record<string, string> = {
  settlementStatus: 'settlementStatus (UI: Scheme Status)',
  transactionStatus: 'transactionStatus (UI: Settlement Status)',
};

export default function Transactions() {
  const { filters, setFilters, version } = useFilters();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [q, setQ] = useState(filters.q ?? '');
  const [open, setOpen] = useState<number>();
  const data = useAsync(() => get<Page>('/transactions', { ...asParams(filters), page, size }),
    [version, JSON.stringify(filters), page, size]);

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>C&amp;S Transactions</Typography>
      <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
        <TextField size="small" sx={{ width: 420 }} label="Search RRN, ARN, invoice, MID, terminal, run number or amount"
          value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && setFilters({ ...filters, q })} />
        <Button variant="contained" onClick={() => { setPage(0); setFilters({ ...filters, q }); }}>Search</Button>
        <Box sx={{ flex: 1 }} />
        <ExportButtons list="transactions" params={asParams(filters)} />
      </Box>
      <Loadable state={data}>
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow>{COLS.map((c) => <TableCell key={c}>{LABELS[c] ?? c}</TableCell>)}<TableCell /></TableRow></TableHead>
            <TableBody>
              {data.data?.content.map((r) => (
                <TableRow key={String(r.recordId)} hover>
                  {COLS.map((c) => (
                    <TableCell key={c}>{c.endsWith('Status') || c === 'paymentReleaseStatusEnum' ? <StatusChip value={r[c]} /> : String(r[c] ?? '')}</TableCell>
                  ))}
                  <TableCell><Button size="small" onClick={() => setOpen(Number(r.recordId))}>View details</Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
        <TablePagination component="div" count={data.data?.totalElements ?? 0} page={page} rowsPerPage={size}
          onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(+e.target.value); setPage(0); }}
          rowsPerPageOptions={[25, 50, 100, 250]} />
      </Loadable>
      {open !== undefined && <Lifecycle id={open} onClose={() => setOpen(undefined)} />}
    </Box>
  );
}

function Section({ title, value }: { title: string; value: unknown }) {
  return (
    <Box sx={{ mb: 2 }}>
      <Typography variant="subtitle2">{title}</Typography>
      {value == null || (Array.isArray(value) && value.length === 0)
        ? <Typography variant="body2" color="text.secondary">None found in collected data.</Typography>
        : <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 }}>{JSON.stringify(value, null, 2)}</pre>}
    </Box>
  );
}

/** Lifecycle: C&S record -> payment -> scheme rejects -> Accounting postings -> rule results, with sources. */
export function Lifecycle({ id, onClose }: { id: number; onClose: () => void }) {
  const d = useAsync(() => get<Row>(`/transactions/${id}/lifecycle`), [id]);
  const lc = d.data ?? {};
  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>Transaction lifecycle</DialogTitle>
      <DialogContent>
        <Loadable state={d}>
          <Alert severity="info" sx={{ mb: 2 }}>Every block shows its source system, endpoint, record key and retrieved-at time.</Alert>
          <Section title="1. C&S record (masked)" value={{ source: lc.source, raw: lc.raw }} />
          <Section title="2. Payment (run + MID + side)" value={lc.payments} />
          <Section title="3. Transactions behind the payment" value={lc.paymentTransactions} />
          <Section title="4. Scheme rejects (ARN / RRN)" value={lc.schemeRejects} />
          <Section title="5. Accounting postings (RRN search)" value={lc.accountingPostings} />
          <DataTable title="6. Rule results" rows={(lc.ruleResults as Row[]) ?? []} />
          <Section title="Change history" value={lc.history} />
        </Loadable>
      </DialogContent>
    </Dialog>
  );
}
