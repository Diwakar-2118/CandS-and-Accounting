// Thin client for the monitor backend. Auth is HTTP Basic, prompted by the browser (same origin).

export type Row = Record<string, unknown>;

export interface Filters {
  from?: string;
  to?: string;
  processor?: string;
  scheme?: string;
  channel?: string;
  merchant?: string;
  txnStatus?: string;
  accountingStatus?: string;
  ttCode?: string;
  fileType?: string;
  q?: string;
  settlementStatus?: string;
  paymentReleaseStatus?: string;
}

export function qs(params: Record<string, string | number | boolean | undefined>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '' && v !== null) p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : '';
}

export async function get<T>(path: string, params: Record<string, string | number | boolean | undefined> = {}): Promise<T> {
  const r = await fetch(`/api${path}${qs(params)}`, { credentials: 'same-origin' });
  if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 300)}`);
  return r.json() as Promise<T>;
}

export async function post<T>(path: string, body: unknown = {}): Promise<T> {
  const r = await fetch(`/api${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`${r.status} ${(await r.text()).slice(0, 300)}`);
  return r.json() as Promise<T>;
}

export function exportUrl(list: string, format: 'csv' | 'xlsx', params: Record<string, string | undefined>): string {
  return `/api/export/${list}${qs({ ...params, format })}`;
}

export interface Kpi {
  key: string;
  label: string;
  value: unknown;
  available: boolean;
  reason?: string;
  lastSuccessAt?: string;
  drill?: string;
  sources: { dataset: string; endpoint?: string; screen?: string; available: boolean; error?: string; lastSuccessAt?: string }[];
}

export interface Meta {
  today: string;
  defaultFrom: string;
  running: boolean;
  processors: { code: string; displayName: string; active: boolean }[];
  channels: string[];
  fileCategories: string[];
  rules: { id: string; name: string; category: string }[];
  datasets: Row[];
  labelMismatches: { uiLabel: string; apiField: string }[];
}
