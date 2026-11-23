import { AppBar, Box, CssBaseline, Drawer, List, ListItemButton, ListItemText, ThemeProvider, Toolbar, Typography, createTheme, useMediaQuery } from '@mui/material';
import { useMemo } from 'react';
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
import { SURFACE } from './palette';

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
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }} color="default" elevation={0}>
        <Toolbar>
          <Typography variant="h6" sx={{ flex: 1 }}>Toucan C&amp;S and Accounting Monitor (UAT, read-only)</Typography>
        </Toolbar>
      </AppBar>
      <Drawer variant="permanent" sx={{ width: W, '& .MuiDrawer-paper': { width: W } }}>
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
        <Toolbar />
        {loc.pathname !== '/' && <FilterBar />}
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

function App() {
  const dark = useMediaQuery('(prefers-color-scheme: dark)');
  const theme = useMemo(() => createTheme({
    palette: { mode: dark ? 'dark' : 'light', background: { default: dark ? SURFACE.dark : '#f5f6f8', paper: dark ? '#222221' : '#ffffff' } },
  }), [dark]);
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <FilterProvider>
          <Shell />
        </FilterProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
