import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Select, { type OptionProps } from '@douyinfe/semi-ui/lib/es/select';
import type { Page, Security } from '../../api/contracts';
import { dateText, type RequestFn } from '../common';
import './stock-picker.scss';

type SecurityReference = Pick<Security, 'id' | 'tsCode' | 'name'> & Partial<Pick<Security, 'market'>>;
type CatalogStatus = { state: string; count: number; updatedAt?: string; error?: string };

function securityParts(security: SecurityReference) {
  const [code, suffix] = security.tsCode.split('.');
  return { code: code || security.tsCode, exchange: security.market || suffix || '—' };
}

function securityAccessibleLabel(security: SecurityReference) {
  return `${security.tsCode} · ${security.name}`;
}

function renderSecurityLabel(security: SecurityReference) {
  const { code, exchange } = securityParts(security);
  return <span className="stock-picker-option-content">
    <span className="sr-only">{securityAccessibleLabel(security)}</span>
    <strong className="stock-picker-option-name" aria-hidden="true">{security.name}</strong>
    <span className="stock-picker-option-meta" aria-hidden="true">
      <span className="stock-picker-option-code">{code}</span>
      <span className="stock-picker-option-exchange">{exchange}</span>
    </span>
  </span>;
}

export function StockPicker({ request, value, onChange, disabled = false }: {
  request: RequestFn; value: SecurityReference | null; onChange: (value: Security | null) => void; disabled?: boolean;
}) {
  const id = useId();
  const selectRef = useRef<Select>(null);
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const [controlWidth, setControlWidth] = useState(0);
  const pickerId = `${id}-picker`;
  useLayoutEffect(() => {
    const picker = document.getElementById(pickerId);
    if (!picker) return;
    const measure = () => setControlWidth(picker.getBoundingClientRect().width);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(picker);
    return () => observer?.disconnect();
  }, [pickerId]);
  useEffect(() => { const timer = setTimeout(() => setDebounced(query.trim()), 250); return () => clearTimeout(timer); }, [query]);
  const catalog = useQuery({ queryKey: ['security-catalog'], queryFn: () => request<CatalogStatus>('/api/securities/catalog-status'), staleTime: 60_000, enabled: !disabled });
  const catalogAvailable = (catalog.data?.count ?? 0) > 0
    && (catalog.data?.state === 'READY' || catalog.data?.state === 'ERROR');
  const search = useQuery({ queryKey: ['securities', 'search-page', debounced], queryFn: () => request<Page<Security>>(`/api/securities/search?q=${encodeURIComponent(debounced)}&page=0&size=20`, { responseType: 'page' }), enabled: !disabled && catalogAvailable, staleTime: 60_000 });
  const options: SecurityReference[] = [...(search.data?.items ?? [])];
  if (value && !options.some(item => item.id === value.id)) options.unshift(value);
  const waiting = query.trim() !== debounced || search.isFetching || (catalogAvailable && search.data === undefined && !search.error);
  const retryCatalog = () => { void catalog.refetch(); };
  const status = catalog.isLoading ? <span>正在读取股票目录状态…</span>
    : catalog.error ? <><span>股票目录状态暂时无法读取</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
      : !catalogAvailable ? catalog.data?.state === 'ERROR' ? <><span>股票目录同步失败{catalog.data.error ? `：${catalog.data.error}` : ''}</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
        : <><span>{catalog.data?.state === 'DISABLED' ? '股票目录当前未启用。' : '股票目录正在准备，请稍后重试。'}</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
        : catalog.data?.state !== 'READY' ? <span className="stock-picker-warning">目录刷新失败，正在使用上次成功目录{catalog.data?.error ? `：${catalog.data.error}` : '。'}<button type="button" className="text-action" onClick={retryCatalog}>重试目录更新</button></span>
          : waiting ? <span>正在查找股票…</span>
            : search.error ? <><span>股票搜索暂时不可用</span><button type="button" className="text-action" onClick={() => { void search.refetch(); }}>重试搜索</button></>
              : !search.data?.items.length ? <span>没有找到匹配股票，请检查代码或名称。</span>
                : search.data.hasNext ? <span>显示前 20 条，请输入更完整的代码或名称。</span>
                  : null;

  useLayoutEffect(() => {
    if (!menuOpen) return undefined;
    const stopEditorEscape = (event: KeyboardEvent) => {
      const picker = document.getElementById(pickerId);
      if (event.key !== 'Escape' || !picker?.contains(event.target as Node)) return;
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      selectRef.current?.close();
    };
    window.addEventListener('keydown', stopEditorEscape, true);
    return () => window.removeEventListener('keydown', stopEditorEscape, true);
  }, [menuOpen, pickerId]);

  return <div className="stock-picker" id={pickerId} style={{ position: 'relative' }}>
    <span id={`${id}-label`} className="stock-picker__label">证券</span>
    <Select ref={selectRef} className="stock-picker__select" data-field="securityId" aria-labelledby={`${id}-label`} aria-required filter remote onChangeWithObject
      disabled={disabled || !catalogAvailable} value={value ? { value: value.id, label: renderSecurityLabel(value), security: value } : undefined}
      placeholder="搜索股票代码或名称，直接选择"
      style={{ width: '100%' }} loading={waiting && catalogAvailable} dropdownMatchSelectWidth dropdownClassName="stock-picker-dropdown" dropdownStyle={{ width: controlWidth || '100%', minWidth: 0, boxSizing: 'border-box' }} rePosKey={controlWidth}
      optionList={options.map(item => ({ value: item.id, label: renderSecurityLabel(item), security: item }))}
      onSearch={setQuery}
      onDropdownVisibleChange={setMenuOpen}
      onSelect={(_next, option) => {
        const picked = option.security as Security | undefined;
        if (!picked || picked.id === value?.id) return;
        onChange(picked);
      }}
      getPopupContainer={() => document.getElementById(pickerId)!}
      emptyContent={waiting ? '正在查找股票…' : '没有找到匹配股票'}
      renderSelectedItem={(option: OptionProps) => {
        const selected = option.security as SecurityReference | undefined;
        if (!selected) return option.label;
        const { code, exchange } = securityParts(selected);
        return <span className="stock-picker-selected">
          <span className="sr-only">{securityAccessibleLabel(selected)}</span>
          <strong aria-hidden="true">{selected.name}</strong>
          <span aria-hidden="true">{code} · {exchange}</span>
        </span>;
      }}
    />
    {!disabled && status && <div className="stock-picker-status" role="status">{status}{catalogAvailable && catalog.data?.state === 'ERROR' && catalog.data.updatedAt && <span>上次成功更新：{dateText(catalog.data.updatedAt)}</span>}{catalogAvailable && catalog.data?.state === 'ERROR' && search.error && <><span>股票搜索暂时不可用</span><button type="button" className="text-action" onClick={() => { void search.refetch(); }}>重试搜索</button></>}</div>}
  </div>;
}
