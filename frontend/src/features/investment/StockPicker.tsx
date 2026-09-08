import { useEffect, useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { Page, Security } from '../../api/contracts';
import type { RequestFn } from '../common';

type SecurityReference = Pick<Security, 'id' | 'tsCode' | 'name'>;
type CatalogStatus = { state: string; count: number; updatedAt?: string; error?: string };

function catalogUpdatedAt(value?: string) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value ?? '';
  return `${part('year')}.${part('month')}.${part('day')} ${part('hour')}:${part('minute')}`;
}

export function StockPicker({ request, value, onChange, disabled = false }: {
  request: RequestFn; value: SecurityReference | null; onChange: (value: Security | null) => void; disabled?: boolean;
}) {
  const id = useId();
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  useEffect(() => { const timer = setTimeout(() => setDebounced(query.trim()), 250); return () => clearTimeout(timer); }, [query]);
  const catalog = useQuery({ queryKey: ['security-catalog'], queryFn: () => request<CatalogStatus>('/api/securities/catalog-status'), staleTime: 60_000, enabled: !disabled });
  const catalogReady = catalog.data?.state === 'READY' && catalog.data.count > 0;
  const search = useQuery({ queryKey: ['securities', 'search-page', debounced], queryFn: () => request<Page<Security>>(`/api/securities/search?q=${encodeURIComponent(debounced)}&page=0&size=20`, { responseType: 'page' }), enabled: !disabled && catalogReady, staleTime: 60_000 });
  const options: SecurityReference[] = [...(search.data?.items ?? [])];
  if (value && !options.some(item => item.id === value.id)) options.unshift(value);
  const waiting = query.trim() !== debounced || search.isFetching || (catalogReady && search.data === undefined && !search.error);
  const retryCatalog = () => { void catalog.refetch(); };
  const updateTime = catalogUpdatedAt(catalog.data?.updatedAt);
  return <div className="stock-picker">
    <label htmlFor={`${id}-query`}>证券搜索</label>
    <input id={`${id}-query`} disabled={disabled} value={query} onChange={event => setQuery(event.target.value)} placeholder="输入股票代码或名称，如 000001 / 平安银行" autoComplete="off"/>
    {!disabled && <div className="stock-picker-status" role="status">
      {catalog.isLoading ? <span>正在读取股票目录状态…</span>
        : catalog.error ? <><span>股票目录状态暂时无法读取</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
          : catalog.data?.state === 'ERROR' ? <><span>股票目录同步失败{catalog.data.error ? `：${catalog.data.error}` : ''}</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
            : !catalogReady ? <><span>{catalog.data?.state === 'DISABLED' ? '股票目录当前未启用。' : '股票目录正在准备，请稍后重试。'}</span><button type="button" className="text-action" onClick={retryCatalog}>重试目录状态</button></>
              : waiting ? <span>正在查找股票…</span>
                : search.error ? <><span>股票搜索暂时不可用</span><button type="button" className="text-action" onClick={() => { void search.refetch(); }}>重试搜索</button></>
                  : !search.data?.items.length ? <span>没有找到匹配股票，请检查代码或名称。</span>
                    : search.data.hasNext ? <span>显示前 20 条，请输入更完整的代码或名称。</span>
                      : <span>选择系统股票目录中的证券，无需自行登记。</span>}
      {catalogReady && updateTime && <small className="stock-picker-updated">目录更新：{updateTime}</small>}
    </div>}
    <label htmlFor={`${id}-selection`}>证券</label>
    <select id={`${id}-selection`} name="securityId" disabled={disabled} required value={value?.id ?? ''} onChange={event => onChange(search.data?.items.find(item => item.id === Number(event.target.value)) ?? null)}>
      <option value="">请选择证券</option>
      {options.map(item => <option key={item.id} value={item.id}>{item.tsCode} · {item.name}</option>)}
    </select>
  </div>;
}
