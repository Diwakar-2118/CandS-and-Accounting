import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Filters, get, Meta } from '../api';

interface Ctx {
  filters: Filters;
  setFilters: (f: Filters) => void;
  meta?: Meta;
  reloadMeta: () => void;
  /** Bumped by "Refresh" and by filter changes so every page re-fetches. */
  version: number;
  refresh: () => void;
}

const FilterCtx = createContext<Ctx | null>(null);

const KEYS: (keyof Filters)[] = ['from', 'to', 'processor', 'scheme', 'channel', 'merchant', 'txnStatus',
  'accountingStatus', 'ttCode', 'fileType', 'q', 'settlementStatus', 'paymentReleaseStatus'];

/** Global filters live in the URL so drill-down links and shared links carry them. */
export function FilterProvider({ children }: { children: ReactNode }) {
  const [params, setParams] = useSearchParams();
  const [meta, setMeta] = useState<Meta>();
  const [version, setVersion] = useState(0);

  const filters = useMemo(() => {
    const f: Filters = {};
    KEYS.forEach((k) => {
      const v = params.get(k);
      if (v) f[k] = v;
    });
    return f;
  }, [params]);

  const reloadMeta = () => get<Meta>('/meta').then(setMeta).catch(() => setMeta(undefined));
  useEffect(() => {
    reloadMeta();
  }, []);

  const setFilters = (f: Filters) => {
    const p = new URLSearchParams();
    KEYS.forEach((k) => {
      const v = f[k];
      if (v) p.set(k, v);
    });
    // keep page-specific params (e.g. category, tab)
    params.forEach((v, k) => {
      if (!KEYS.includes(k as keyof Filters)) p.set(k, v);
    });
    setParams(p);
    setVersion((x) => x + 1);
  };

  return (
    <FilterCtx.Provider value={{ filters, setFilters, meta, reloadMeta, version, refresh: () => setVersion((x) => x + 1) }}>
      {children}
    </FilterCtx.Provider>
  );
}

export function useFilters(): Ctx {
  const c = useContext(FilterCtx);
  if (!c) throw new Error('FilterProvider missing');
  return c;
}

/** Filters as plain query params. */
export function asParams(f: Filters): Record<string, string | undefined> {
  return { ...f } as Record<string, string | undefined>;
}
