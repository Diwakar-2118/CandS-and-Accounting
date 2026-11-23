import { createTheme, Theme, useTheme } from '@mui/material';
import { createContext, useContext } from 'react';

/** Navy dark theme (default) and a light theme. Chart colours were validated against both surfaces. */
export const NAVY = { bg: '#0f1420', surface: '#151b27', panel: '#182030', border: '#273247', text: '#e6ebf2', muted: '#8d99ae' };

export function buildTheme(mode: 'dark' | 'light'): Theme {
  const dark = mode === 'dark';
  return createTheme({
    palette: dark
      ? { mode, background: { default: NAVY.bg, paper: NAVY.panel }, text: { primary: NAVY.text, secondary: NAVY.muted }, divider: NAVY.border, primary: { main: '#3987e5' } }
      : { mode, background: { default: '#f5f6f8', paper: '#ffffff' }, primary: { main: '#2a78d6' } },
    shape: { borderRadius: 10 },
    typography: { fontFamily: 'Inter, "Segoe UI", Roboto, Arial, sans-serif' },
    components: {
      MuiCard: {
        styleOverrides: {
          root: dark ? { backgroundColor: NAVY.panel, borderColor: NAVY.border, backgroundImage: 'none' } : {},
        },
      },
      MuiDrawer: { styleOverrides: { paper: dark ? { backgroundColor: NAVY.surface, borderColor: NAVY.border } : {} } },
      MuiAppBar: { styleOverrides: { root: dark ? { backgroundColor: NAVY.surface, borderBottom: `1px solid ${NAVY.border}` } : {} } },
    },
  });
}

export const ColorModeContext = createContext<{ mode: 'dark' | 'light'; toggle: () => void }>({ mode: 'dark', toggle: () => {} });

export function useColorMode() {
  return useContext(ColorModeContext);
}

export function useIsDark(): boolean {
  return useTheme().palette.mode === 'dark';
}

export function loadMode(): 'dark' | 'light' {
  // ?theme=light|dark in the URL wins (shareable links, PDF/screenshot capture).
  const fromUrl = new URLSearchParams(window.location.search).get('theme');
  if (fromUrl === 'light' || fromUrl === 'dark') return fromUrl;
  try {
    return localStorage.getItem('monitor.colorMode') === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export function saveMode(m: 'dark' | 'light') {
  try {
    localStorage.setItem('monitor.colorMode', m);
  } catch {
    /* storage unavailable: keep in memory only */
  }
}
