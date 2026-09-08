import { useEffect, useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { Page, Security } from '../../api/contracts';
import type { RequestFn } from '../common';

export function StockPicker({ request, value, onChange, disabled = false }: {
  request: RequestFn; value: Security | null; onChange: (value: Security | null) => void; disabled?: boolean;
}) {
  const id = useId();
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  useEffect(() => { const timer = setTimeout(() => setDebounced(query.trim()), 250); return () => clearTimeout(timer); }, [query]);
  const catalog = useQuery({ queryKey: ['security-catalog'], queryFn: () => request<{ state: string; count: number; updatedAt?: string; error?: string }>('/api/securities/catalog-status'), staleTime: 60_000, enabled: !disabled });
  const search = useQuery({ queryKey: ['securities', 'search-page', debounced], queryFn: () => request<Page<Security>>(`/api/securities/search?q=${encodeURIComponent(debounced)}&page=0&size=20`, { responseType: 'page' }), enabled: !disabled, staleTime: 60_000 });
  const options = [...(search.data?.items ?? [])];
  if (value && !options.some(item => item.id === value.id)) options.unshift(value);
  const waiting = query.trim() !== debounced || search.isFetching;
  return <div className="stock-picker">
    <label htmlFor={`${id}-query`}>证券搜索</label>
    <input id={`${id}-query`} disabled={disabled} value={query} onChange={event => setQuery(event.target.value)} placeholder="输入股票代码或名称，如 000001 / 平安银行" autoComplete="off"/>
    {!disabled && <div className="stock-picker-status" role="status">
      {waiting ? '正在查找股票…' : search.error ? <><span>股票搜索暂时不可用</span><button type="button" className="text-action" onClick={() => { void search.refetch(); void catalog.refetch(); }}>重试搜索</button></> : !search.data?.items.length ? catalog.data?.count === 0 ? <><span>股票目录正在准备，请稍后重试。</span><button type="button" className="text-action" onClick={() => { void search.refetch(); void catalog.refetch(); }}>重试搜索</button></> : '没有找到匹配股票，请检查代码或名称。' : search.data.hasNext ? '显示前 20 条，请输入更完整的代码或名称。' : '选择系统股票目录中的证券，无需自行登记。'}
    </div>}
    <label htmlFor={`${id}-selection`}>证券</label>
    <select id={`${id}-selection`} name="securityId" disabled={disabled} required value={value?.id ?? ''} onChange={event => onChange(options.find(item => item.id === Number(event.target.value)) ?? null)}>
      <option value="">请选择证券</option>
      {options.map(item => <option key={item.id} value={item.id}>{item.tsCode} · {item.name}</option>)}
    </select>
  </div>;
}
