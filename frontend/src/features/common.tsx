import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { X, Plus, CircleAlert, LoaderCircle } from 'lucide-react';
import { EmptyIllustration } from './visuals';
import { ApiError, type ApiRequestOptions } from '../api/client';
import type { HouseholdRole } from '../api/contracts';

export type RequestFn = <T>(path: string, options?: ApiRequestOptions) => Promise<T>;

export function money(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return String(value);
  return `${numeric < 0 ? '-' : ''}¥${Math.abs(numeric).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function dateText(value: string | null | undefined): string {
  if (!value) return '—';
  const raw = value.slice(0, 10);
  const [year, month, day] = raw.split('-');
  return year && month && day ? `${year}.${month}.${day}` : value;
}

export function PageScaffold({ eyebrow, title, description, primaryAction, readonly, children }: {
  eyebrow?: string; title: string; description: string; primaryAction?: { label: string; onClick: () => void }; readonly?: boolean; children: ReactNode;
}) {
  return <section className="feature-page" aria-labelledby="page-title">
    <header className="page-heading">
      <div>{eyebrow && <p className="section-kicker">{eyebrow}</p>}<h1 id="page-title">{title}</h1><p>{description}</p></div>
      {primaryAction && <Button aria-label={primaryAction.label} theme="solid" type="primary" icon={<Plus size={17} aria-hidden="true"/>} onClick={primaryAction.onClick}>{primaryAction.label}</Button>}
    </header>
    {readonly && <div className="readonly-note">当前为只读协作视图</div>}
    <div className="feature-content">{children}</div>
  </section>;
}

export function QueryState({ loading, error, empty, emptyTitle, emptyDetail, children }: {
  loading: boolean; error?: unknown; empty?: boolean; emptyTitle?: string; emptyDetail?: string; children: ReactNode;
}) {
  if (loading) return <div className="query-state" role="status"><LoaderCircle className="loading-spinner" size={24} aria-hidden="true"/>正在读取家庭数据</div>;
  if (error) {
    const apiError = error instanceof ApiError ? error : null;
    return <div className="query-state error-state" role="alert"><CircleAlert size={26} aria-hidden="true"/><strong>这部分数据暂时无法读取</strong><span>{error instanceof Error ? error.message : '请稍后刷新页面'}</span>{apiError?.requestId && <small>请求 ID：{apiError.requestId}</small>}</div>;
  }
  if (empty) return <div className="query-state empty-state"><EmptyIllustration/><div><strong>{emptyTitle ?? '暂无数据'}</strong><span>{emptyDetail ?? '当前没有可展示的记录。'}</span></div></div>;
  return <>{children}</>;
}

const modalStack: string[] = [];
let previousOverflow = '';
function useModal(open: boolean, onClose: () => void) {
  const id = useId();
  const ref = useRef<HTMLElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    if (modalStack.length === 0) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; }
    modalStack.push(id);
    const focusable = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]') ?? []).filter(el => !el.hidden && el.getAttribute('aria-disabled') !== 'true');
    (focusable()[0] ?? ref.current)?.focus();
    function keys(e: KeyboardEvent) {
      if (modalStack.at(-1) !== id) return;
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close.current(); }
      if (e.key === 'Tab') {
        const items = focusable();
        if (!items.length) { e.preventDefault(); ref.current?.focus(); return; }
        const i = items.indexOf(document.activeElement as HTMLElement);
        if (e.shiftKey && i <= 0) { e.preventDefault(); items.at(-1)?.focus(); }
        else if (!e.shiftKey && (i === items.length-1 || i === -1)) { e.preventDefault(); items[0].focus(); }
      }
    }
    document.addEventListener('keydown', keys, true);
    return () => {
      document.removeEventListener('keydown', keys, true);
      const wasTop = modalStack.at(-1) === id;
      const i = modalStack.indexOf(id); if (i !== -1) modalStack.splice(i, 1);
      if (!modalStack.length) document.body.style.overflow = previousOverflow;
      if (wasTop && previous?.isConnected) previous.focus();
    };
  }, [open, id]);
  return { id, ref };
}

export function Drawer({ open, title, description, onClose, children }: { open: boolean; title: string; description?: string; onClose: () => void; children: ReactNode }) {
  const {id,ref} = useModal(open,onClose);
  if (!open) return null;
  return createPortal(<div className="sheet-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <aside ref={ref} tabIndex={-1} className="side-sheet" role="dialog" aria-modal="true" aria-labelledby={id}>
      <header><div><h2 id={id}>{title}</h2>{description && <p>{description}</p>}</div><button type="button" className="icon-button" aria-label="关闭" onClick={onClose}><X size={20} aria-hidden="true"/></button></header>
      <div className="sheet-body">{children}</div>
    </aside>
  </div>, document.body);
}

export function ConfirmDialog({ open, title, detail, confirmLabel = '确认', danger, onConfirm, onClose, loading = false }: { open: boolean; title: string; detail: ReactNode; confirmLabel?: string; danger?: boolean; onConfirm: () => void; onClose: () => void; loading?: boolean }) {
  const {id,ref} = useModal(open,onClose);
  if (!open) return null;
  return createPortal(<div className="sheet-backdrop dialog-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section ref={ref} tabIndex={-1} className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby={id}>
      <div className="confirmation-symbol"><CircleAlert size={24} aria-hidden="true"/></div><h2 id={id}>{title}</h2><div className="confirm-detail">{detail}</div>
      <footer><Button onClick={onClose}>取消</Button><Button theme="solid" loading={loading} type={danger ? 'danger' : 'primary'} onClick={onConfirm}>{confirmLabel}</Button></footer>
    </section>
  </div>, document.body);
}

export function FormError({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  return <div className="form-alert" role="alert">{error instanceof Error ? error.message : '保存失败，请检查后重试'}{apiError?.fields && <ul>{Object.entries(apiError.fields).map(([field,message])=><li key={field}>{message}</li>)}</ul>}{apiError?.requestId && <div className="request-id">请求 ID：{apiError.requestId}</div>}</div>;
}

export const isManager = (role: HouseholdRole) => role === 'OWNER' || role === 'ADMIN';

export function StatusTag({ tone = 'neutral', children }: { tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'blue'; children: ReactNode }) {
  return <span className={`status-tag ${tone}`}>{children}</span>;
}

export function DataPanel({ title, meta, action, children, className = '' }: { title: string; meta?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`data-panel ${className}`}><header><div><h2>{title}</h2>{meta && <p>{meta}</p>}</div>{action}</header><div className="panel-body">{children}</div></section>;
}
