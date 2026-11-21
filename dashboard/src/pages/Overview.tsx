import { Box, Grid, Typography } from '@mui/material';
import { get, Kpi, Row } from '../api';
import { ProcessorLines, StackedBars, StatusBars, TwoLines } from '../components/Charts';
import { KpiCard, Loadable, useAsync } from '../components/Common';
import { asParams, useFilters } from '../components/FilterContext';

export interface ChartData {
  volumeByDayProcessor: Row[];
  accountingStatus: Row[];
  dailyPostings: Row[];
  filesByStatus: Row[];
  failuresByRuleProcessor: Row[];
}

export function useCharts() {
  const { filters, version } = useFilters();
  return useAsync(() => get<ChartData>('/charts', asParams(filters)), [version, JSON.stringify(filters)]);
}

export default function Overview() {
  const { filters, version, meta } = useFilters();
  const kpis = useAsync(() => get<Kpi[]>('/kpis', asParams(filters)), [version, JSON.stringify(filters)]);
  const charts = useCharts();
  const processors = meta?.processors.map((p) => p.code) ?? [];
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 2 }}>Overview</Typography>
      <Loadable state={kpis}>
        <Grid container spacing={2} sx={{ mb: 3 }}>
          {kpis.data?.map((k) => (
            <Grid item xs={12} sm={6} md={4} lg={3} key={k.key}><KpiCard k={k} /></Grid>
          ))}
        </Grid>
      </Loadable>
      <Loadable state={charts}>
        {charts.data && (
          <Grid container spacing={2}>
            <Grid item xs={12} lg={6}>
              <ProcessorLines rows={charts.data.volumeByDayProcessor} suffix="_count" title="Transactions per day by processor" allProcessors={processors} />
            </Grid>
            <Grid item xs={12} lg={6}>
              <ProcessorLines rows={charts.data.volumeByDayProcessor} suffix="_amount" title="Transaction amount (INR) per day by processor" allProcessors={processors} />
            </Grid>
            <Grid item xs={12} lg={6}>
              <TwoLines rows={charts.data.dailyPostings} a="cs" b="accounting" labels={['C&S transactions', 'Posted in Accounting']}
                title="Daily postings: C&S vs Accounting" />
            </Grid>
            <Grid item xs={12} lg={6}>
              <StatusBars rows={charts.data.accountingStatus} labelKey="status" valueKey="count" title="Accounting status split" />
            </Grid>
            <Grid item xs={12} lg={6}>
              <StackedBars rows={charts.data.filesByStatus} xKey="category" title="Files by status" colorMode="status" />
            </Grid>
            <Grid item xs={12} lg={6}>
              <StackedBars rows={charts.data.failuresByRuleProcessor} xKey="rule" title="Failures by rule and processor"
                colorMode="entity" allEntities={processors} />
            </Grid>
          </Grid>
        )}
      </Loadable>
    </Box>
  );
}
