// Chart colours: the dataviz skill's validated reference palette, used unchanged.
// Categorical slots are assigned in fixed order to processors sorted by code, so a processor
// keeps its colour when filters change. Status colours are reserved for status and always
// shown with a text label.

const LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'];
const DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#0ca30c', '#9085e9', '#e66767'];
export const OTHER = '#8a8984';

export const STATUS = {
  good: '#0ca30c',
  warning: '#fab219',
  serious: '#ec835a',
  critical: '#d03b3b',
};

export const SURFACE = { light: '#fcfcfb', dark: '#1a1a19' };

export function seriesColors(dark: boolean): string[] {
  return dark ? DARK : LIGHT;
}

/** Stable colour per entity: index in the sorted entity list; beyond 8 entities fold to OTHER. */
export function colorFor(entity: string, allEntities: string[], dark: boolean): string {
  const sorted = [...allEntities].sort();
  const i = sorted.indexOf(entity);
  return i >= 0 && i < 8 ? seriesColors(dark)[i] : OTHER;
}

export function statusColor(outcome: string): string {
  switch (outcome) {
    case 'PASS':
    case 'COMPLETED':
    case 'Generated':
    case 'Processed':
      return STATUS.good;
    case 'WARN':
    case 'PENDING':
    case 'Processing':
      return STATUS.warning;
    case 'MISSING':
    case 'Rejected':
      return STATUS.serious;
    case 'FAIL':
    case 'FAILED':
    case 'Failed':
      return STATUS.critical;
    default:
      return OTHER;
  }
}
