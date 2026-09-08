import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
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
  await screen.findByText('选择系统股票目录中的证券，无需自行登记。');
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
