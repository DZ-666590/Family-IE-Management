import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StockPicker } from './StockPicker';
import type { Security } from '../../api/contracts';
import type { RequestFn } from '../common';
import { FormError } from '../common';
import { ApiError } from '../../api/client';

const stock: Security = { id: 5, tsCode: '000021.SZ', name: '深科技', market: 'SZ', active: true, securityType: 'STOCK' };
it('searches and picks in one combobox without a second selection field', async () => {
  const request: RequestFn = async <T,>(path: string) => (path.includes('catalog-status') ? {state:'READY',count:5558} : {items:[stock],page:0,size:20,totalElements:1,totalPages:1,hasNext:false}) as T;
  function Picker() { const [value,setValue]=useState<Security|null>(null); return <><StockPicker request={request} value={value} onChange={setValue}/><output>{value?.id ?? '未选择'}</output></>; }
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><Picker/></QueryClientProvider>);
  const user=userEvent.setup();
  const control=screen.getByRole('combobox',{name:'证券'});
  await waitFor(() => expect(control).not.toHaveAttribute('aria-disabled', 'true'));
  await user.click(control);
  await user.type(screen.getByRole('textbox'), '深科技');
  await waitFor(() => expect(screen.queryByText('选择系统股票目录中的证券，无需自行登记。')).not.toBeInTheDocument());
  await user.click(await screen.findByRole('option',{name:/000021.SZ · 深科技/}));
  expect(await screen.findByText('5',{selector:'output'})).toBeInTheDocument();
  expect(screen.getAllByRole('combobox')).toHaveLength(1);
  expect(screen.queryByLabelText('证券搜索')).not.toBeInTheDocument();
});

it('associates server security errors with the searchable control and restores focus', async()=>{
  const request:RequestFn=async<T,>(path:string)=>(path.includes('catalog-status')?{state:'READY',count:5558}:{items:[stock],page:0,size:20,totalElements:1,totalPages:1,hasNext:false})as T;
  function Form(){const[error,setError]=useState<Error|null>(null);return <form><FormError error={error}/><StockPicker request={request} value={stock} onChange={()=>{}}/><button type="button" onClick={()=>setError(new ApiError('证券选择无效',{status:422,fields:{securityId:'请重新选择有效股票'}}))}>模拟校验</button></form>;}
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><Form/></QueryClientProvider>);
  const control=screen.getByRole('combobox',{name:'证券'});
  await waitFor(()=>expect(control).toHaveAttribute('aria-disabled','false'));
  await userEvent.click(screen.getByRole('button',{name:'模拟校验'}));
  expect(control).toHaveAttribute('aria-invalid','true');
  expect(control).toHaveAccessibleDescription('请重新选择有效股票');
  expect(control===document.activeElement || control.contains(document.activeElement)).toBe(true);
});

it('anchors the search menu to the picker and keeps the menu the same width as the control', async () => {
  const request: RequestFn = async <T,>(path: string) => (path.includes('catalog-status')
    ? { state: 'READY', count: 5558 }
    : { items: [stock], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }) as T;
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <StockPicker request={request} value={null} onChange={() => {}} />
  </QueryClientProvider>);
  const control = screen.getByRole('combobox', { name: '证券' });
  const picker = control.closest('.stock-picker');
  expect(picker).toHaveStyle({ position: 'relative' });

  await userEvent.click(control);
  const listbox = await screen.findByRole('listbox');
  const dropdown = listbox.closest<HTMLElement>('.stock-picker-dropdown');
  expect(dropdown).toBeInTheDocument();
  expect(dropdown).toHaveStyle({ width: '100%' });
  expect(picker).toContainElement(dropdown);
});

it('renders a stock name first with code and exchange metadata while preserving the legacy option name', async () => {
  const request: RequestFn = async <T,>(path: string) => (path.includes('catalog-status')
    ? { state: 'READY', count: 5558 }
    : { items: [stock], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }) as T;
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <StockPicker request={request} value={null} onChange={() => {}} />
  </QueryClientProvider>);
  await userEvent.click(screen.getByRole('combobox', { name: '证券' }));
  const option = await screen.findByRole('option', { name: /000021.SZ · 深科技/ });
  expect(within(option).getByText('深科技')).toHaveClass('stock-picker-option-name');
  expect(within(option).getByText('000021')).toHaveClass('stock-picker-option-code');
  expect(within(option).getByText('SZ')).toHaveClass('stock-picker-option-exchange');
});

it('closes the picker menu on Escape without closing a containing editor', async () => {
  const request: RequestFn = async <T,>(path: string) => (path.includes('catalog-status')
    ? { state: 'READY', count: 5558 }
    : { items: [stock], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }) as T;
  let editorEscapes = 0;
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <div onKeyDown={() => { editorEscapes += 1; }}>
      <StockPicker request={request} value={null} onChange={() => {}} />
    </div>
  </QueryClientProvider>);
  const control = screen.getByRole('combobox', { name: '证券' });
  await userEvent.click(control);
  expect(control).toHaveAttribute('aria-expanded', 'true');
  await userEvent.keyboard('{Escape}');
  expect(control).toHaveAttribute('aria-expanded', 'false');
  expect(editorEscapes).toBe(0);
});
