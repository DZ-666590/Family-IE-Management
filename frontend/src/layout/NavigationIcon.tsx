import type { ReactNode } from 'react';
export type NavigationIconName = 'overview' | 'transactions' | 'budgets' | 'recurring' | 'assets' | 'investments' | 'loans' | 'annual-stats' | 'extension' | 'collapse' | 'bell' | 'menu';
const drawings: Record<NavigationIconName, ReactNode> = {
  overview: <><path d="m3.5 10 8.5-7 8.5 7v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z"/><path d="M9 21v-7h6v7M9 10h6"/></>,
  transactions: <><rect x="5" y="3" width="14" height="18" rx="2.5"/><path d="M9 3v18M12 8h4M12 12h4M12 16h2M3 7h3M3 12h3M3 17h3"/></>,
  budgets: <><path d="M12 3a9 9 0 1 0 9 9h-9Z"/><path d="M15 3.5v5.5h5.5A9 9 0 0 0 15 3.5Z"/></>,
  recurring: <><path d="M19.5 8A8 8 0 0 0 5 6L3 9m0-5v5h5M4.5 16A8 8 0 0 0 19 18l2-3m0 5v-5h-5"/><path d="M12 7v5l3 2"/></>,
  assets: <><rect x="4" y="9" width="9" height="12" rx="1.5"/><path d="M13 21h7V5a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v4M7 13h3M7 17h3M13 7h3M16 11h1M16 15h1"/></>,
  investments: <><path d="M4 3v16a2 2 0 0 0 2 2h15M7 14l5-5 4 3 5-7M16 5h5v5"/><path d="M8 18v-1M13 18v-3M18 18v-2"/></>,
  loans: <><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 10h18M7 15h3M15 15h2"/></>,
  'annual-stats': <><circle cx="12" cy="12" r="9"/><path d="M8 16v-3M12 16V9M16 16V6"/></>,
  extension: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/><path d="M17.5 3v7M14 6.5h7"/></>,
  collapse: <><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16m7-12-3 4 3 4"/></>,
  bell: <><path d="M5 16c2-2 1-5 2-8a5.2 5.2 0 0 1 10 0c1 3 0 6 2 8l1 1H4Zm5 4a2.5 2.5 0 0 0 4 0"/></>,
  menu: <path d="M4 6h16M4 12h16M4 18h11"/>
};
export function NavigationIcon({ name }: { name: NavigationIconName }) {
  return <svg className="navigation-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{drawings[name]}</svg>;
}
export function LedgerMark() {
  return <svg className="ledger-brand-mark" width="36" height="38" viewBox="0 0 36 38" aria-hidden="true" focusable="false"><path d="m12 12 20-5-6 25-20 5Z" fill="#4F46E5"/><path d="m8 7 20-5-6 25-20 5Z" fill="#A5B4FC" opacity=".8"/><path d="m8 7 20-5-3 13-20 5Z" fill="#E0E7FF" opacity=".8"/><path d="m11 14 10-2m-11 7 8-2" stroke="white" strokeWidth="1.6" strokeLinecap="round"/></svg>;
}
