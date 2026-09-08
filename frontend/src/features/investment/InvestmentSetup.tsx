import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BriefcaseBusiness, Check } from 'lucide-react';
import type { Account, InvestmentAccount } from '../../api/contracts';
import { FormError, type RequestFn } from '../common';

export interface SetupStatus { completed: boolean; hasAccounts: boolean; hasTrades: boolean }
export interface SetupResource<T> { data?: T[]; isLoading: boolean; error: unknown; refetch: () => unknown }
export function InvestmentSetup({ request, manager, accounts, cashAccounts, onCreateAccount, onOpening }: {
  request: RequestFn; manager: boolean; accounts: SetupResource<InvestmentAccount>; cashAccounts: SetupResource<Account>;
  onCreateAccount: () => void; onOpening: (accountId: string) => void;
}) {
  const cache = useQueryClient();
  const [selectedId, setSelectedId] = useState('');
  const status = useQuery({ queryKey: ['investment-setup'], queryFn: () => request<SetupStatus>('/api/investment-setup') });
  const complete = useMutation({ mutationFn: () => request<SetupStatus>('/api/investment-setup/complete', { method: 'POST' }), onSuccess: result => cache.setQueryData(['investment-setup'], result) });
  if (status.isLoading || status.data?.completed || status.data?.hasTrades) return null;
  if (status.error) return <div role="status" className="investment-setup"><span>初始化状态暂时无法读取，已有投资记录不受影响。</span><button className="text-action" onClick={() => { void status.refetch(); }}>重试初始化状态</button></div>;
  const prerequisiteLoading = accounts.isLoading || cashAccounts.isLoading || accounts.data === undefined || cashAccounts.data === undefined;
  const prerequisiteError = accounts.error || cashAccounts.error;
  if (prerequisiteError) return <section className="investment-setup" aria-label="投资初始化">
    <div className="investment-setup-heading"><BriefcaseBusiness size={24} aria-hidden="true"/><div><h2>从你的实际持仓开始</h2><p>投资账户或资金账户暂时无法读取。</p></div></div>
    <div role="alert" className="investment-setup-prerequisite"><span>{prerequisiteError instanceof Error ? prerequisiteError.message : '请稍后重试账户数据'}</span><button type="button" className="text-action" onClick={() => { void accounts.refetch(); void cashAccounts.refetch(); }}>重试账户数据</button></div>
  </section>;
  if (prerequisiteLoading) return <section className="investment-setup" aria-label="投资初始化">
    <div className="investment-setup-heading"><BriefcaseBusiness size={24} aria-hidden="true"/><div><h2>从你的实际持仓开始</h2><p role="status">正在读取投资账户和资金账户…</p></div></div>
  </section>;
  const investmentAccounts = accounts.data ?? [];
  const fundingAccounts = cashAccounts.data ?? [];
  // Require an actual confirmed funding account, including a valid zero balance.
  const usable = investmentAccounts.filter(account => fundingAccounts.some(cash => cash.id === account.fundingAccountId && cash.openingConfirmed && cash.openingOn && !cash.archivedAt));
  const accountId = usable.some(account => String(account.id) === selectedId) ? selectedId : String(usable[0]?.id ?? '');
  return <section className="investment-setup" aria-label="投资初始化">
    <div className="investment-setup-heading"><BriefcaseBusiness size={24} aria-hidden="true"/><div><h2>从你的实际持仓开始</h2><p>把券商账户里的现状记进家账，之后再同步每一笔变化。</p></div></div>
    {!manager ? <p>请家庭管理员初始化投资账户；你可以先浏览股票行情。</p> : <>
      <div className="investment-setup-steps"><span className={usable.length ? 'done' : ''}><Check size={15} aria-hidden="true"/>关联资金账户</span><span>确认已有持仓</span></div>
      {!usable.length ? <><p>先关联一个已确认期初余额的现金账户，今后的买卖会准确反映现金变化。</p><div className="investment-setup-actions"><button type="button" onClick={onCreateAccount}>{investmentAccounts.length ? '设置投资资金账户' : '创建投资账户'}</button><a href="/workspace/transactions?section=accounts">初始化现金账户</a></div></> : <>
        <label>用于初始化的投资账户<select value={accountId} onChange={event => setSelectedId(event.target.value)}>{usable.map(account => <option key={account.id} value={account.id}>{account.name}</option>)}</select></label>
        <p>已有持仓只录入数量和单位成本，不会再次扣减现金。新发生的买入请使用“记一笔投资”。</p>
        <FormError error={complete.error}/>
        <div className="investment-setup-actions"><button type="button" disabled={complete.isPending} onClick={() => onOpening(accountId)}>录入已有持仓</button><button type="button" disabled={complete.isPending} onClick={() => complete.mutate()}>{complete.isPending ? '正在保存…' : '暂时没有持仓，完成初始化'}</button></div>
      </>}
    </>}
  </section>;
}
