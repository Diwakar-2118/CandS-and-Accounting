import { AppBar, Box, Button, CssBaseline, Drawer, GlobalStyles, List, ListItemButton, ListItemText, ThemeProvider, Toolbar, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import FilterBar from './components/FilterBar';
import { FilterProvider } from './components/FilterContext';
import Files from './pages/Files';
import Ledger from './pages/Ledger';
import { DataQuality, Processors, Recon, Runs } from './pages/Other';
import Overview from './pages/Overview';
import Executive from './pages/Executive';
import Transactions from './pages/Transactions';
import Validation from './pages/Validation';
import { buildTheme, ColorModeContext, loadMode, saveMode, useColorMode } from './theme';

const NAV: [string, string][] = [
  ['/', 'Executive Summary'], ['/overview', 'Operations overview'], ['/processors', 'Processors'], ['/transactions', 'C&S Transactions'], ['/files', 'Files & Reports'],
  ['/validation', 'Accounting Validation'], ['/ledger', 'Ledger Health'], ['/recon', 'Reconciliation'],
  ['/runs', 'Runs & Alerts'], ['/data-quality', 'Data Quality'],
];
const W = 220;

function Shell() {
  const nav = useNavigate();
  const loc = useLocation();
  return (
    <Box sx={{ display: 'flex' }}>
      <AppBar className="no-print" position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }} color="inherit" elevation={0}>
        <Toolbar>
          <Typography variant="h6" sx={{ flex: 1 }}>Toucan C&amp;S and Accounting Monitor (UAT, read-only)</Typography>
          <ExportPdf />
          <ModeToggle />
        </Toolbar>
      </AppBar>
      <Drawer className="no-print" variant="permanent" sx={{ width: W, '& .MuiDrawer-paper': { width: W } }}>
        <Toolbar />
        <List>
          {NAV.map(([p, l]) => (
            <ListItemButton key={p} selected={loc.pathname === p} onClick={() => nav(p + loc.search)}>
              <ListItemText primary={l} />
            </ListItemButton>
          ))}
        </List>
      </Drawer>
      <Box component="main" sx={{ flex: 1, p: 2, minWidth: 0 }}>
        <Toolbar className="no-print" />
        {loc.pathname !== '/' && <Box className="no-print"><FilterBar /></Box>}
        <Routes>
          <Route path="/" element={<Executive />} />
          <Route path="/overview" element={<Overview />} />
          <Route path="/processors" element={<Processors />} />
          <Route path="/transactions" element={<Transactions />} />
          <Route path="/files" element={<Files />} />
          <Route path="/validation" element={<Validation />} />
          <Route path="/ledger" element={<Ledger />} />
          <Route path="/recon" element={<Recon />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/data-quality" element={<DataQuality />} />
        </Routes>
      </Box>
    </Box>
  );
}

/**
 * Export to PDF through the browser's print dialog ("Save as PDF"): exact on-screen rendering,
 * charts stay vector, no extra library. Print CSS hides navigation and filters.
 */
function ExportPdf() {
  const loc = useLocation();
  const exportPdf = () => {
    const page = NAV.find(([p]) => p === loc.pathname)?.[1] ?? 'Dashboard';
    const prev = document.title;
    // Default file name in the Save-as-PDF dialog, e.g. Executive-Summary-2026-11-23
    document.title = page.replace(/[^A-Za-z0-9]+/g, '-') + '-' + new Date().toISOString().slice(0, 10);
    window.addEventListener('afterprint', () => { document.title = prev; }, { once: true });
    window.print();
  };
  return <Button color="inherit" onClick={exportPdf}>Export PDF</Button>;
}

/**
 * Print layout: A3 portrait (the dashboard is a long vertical page). Page margin is 0 and the padding
 * lives inside the page, so a dark theme prints edge to edge instead of inside a white band.
 */
const printCss = (bg: string) => ({
  '@page': { size: 'A3 portrait', margin: 0 },
  '@media print': {
    '.no-print': { display: 'none !important' },
    '.print-only': { display: 'block !important' },
    'html, body, #root': {
      WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact', backgroundColor: `${bg} !important`,
    },
    main: { padding: '10mm !important' },
    '.MuiCard-root': { breakInside: 'avoid', pageBreakInside: 'avoid' },
  },
  '.print-only': { display: 'none' },
});

function ModeToggle() {
  const { mode, toggle } = useColorMode();
  return <Button color="inherit" onClick={toggle}>{mode === 'dark' ? 'Light mode' : 'Dark mode'}</Button>;
}

function App() {
  const [mode, setMode] = useState<'dark' | 'light'>(loadMode());
  const theme = useMemo(() => buildTheme(mode), [mode]);
  const toggle = () => setMode((m) => { const n = m === 'dark' ? 'light' : 'dark'; saveMode(n); return n; });
  return (
    <ColorModeContext.Provider value={{ mode, toggle }}>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <GlobalStyles styles={printCss(theme.palette.background.default)} />
      <BrowserRouter>
        <FilterProvider>
          <Shell />
        </FilterProvider>
      </BrowserRouter>
    </ThemeProvider>
    </ColorModeContext.Provider>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
