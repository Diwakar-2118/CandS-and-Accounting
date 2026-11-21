import { Box, Tab, Tabs, Typography } from '@mui/material';
import { useSearchParams } from 'react-router-dom';
import { get, Row } from '../api';
import { DataTable, Loadable, useAsync } from '../components/Common';
import { asParams, useFilters } from '../components/FilterContext';

const CATS: [string, string][] = [
  ['scheme', 'Scheme files'], ['payment', 'Payment files'], ['reports', 'Reports'], ['processed', 'Processed files'],
  ['jv', 'Journal vouchers'], ['upi-bdf', 'UPI summary BDF'], ['bulk', 'Bulk download'], ['rejects', 'Scheme rejects'],
  ['payments', 'Payments'], ['rolling-reserve', 'Rolling reserve'], ['instant-settlement', 'Instant settlement'],
  ['payment-exceptions', 'Payment exceptions'],
];

export default function Files() {
  const { filters, version } = useFilters();
  const [params, setParams] = useSearchParams();
  const cat = params.get('category') ?? 'scheme';
  const data = useAsync(() => get<Row[]>('/files', { ...asParams(filters), category: cat }), [version, cat, JSON.stringify(filters)]);
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>Files &amp; Reports</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        dashboardStatus maps raw values onto Generated / Received / Uploaded / Processing / Processed / Failed / Rejected;
        anything unrecognised is "Unmapped". The raw value is always shown. "Missing" comes only from the expected-file calendar (rule R05).
      </Typography>
      <Tabs value={cat} onChange={(_, v) => { params.set('category', v); setParams(params); }} variant="scrollable" sx={{ mb: 2 }}>
        {CATS.map(([k, l]) => <Tab key={k} value={k} label={l} />)}
      </Tabs>
      <Loadable state={data}>
        <DataTable rows={data.data ?? []} title={CATS.find((c) => c[0] === cat)?.[1]} exportList="files"
          exportParams={{ ...asParams(filters), category: cat }} />
      </Loadable>
    </Box>
  );
}
