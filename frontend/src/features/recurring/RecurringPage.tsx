import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { businessDate } from '../../shared/runtime';
import type {
  Account, Category, HouseholdRole, Member, Membership, Page,
  RecurringOccurrence, RecurringRule, RecurringScheduleType, TransactionKind,
} from '../../api/contracts';
import { PaginationControls, readAllPages, usePageRecovery } from '../../shared/pagination';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { DataPanel, Drawer, FormError, PageScaffold, QueryState, StatusTag, isManager, money, type RequestFn } from '../common';

type RuleDraft = {
  id?: number; kind: TransactionKind; amount: string; scheduleType: RecurringScheduleType;
  intervalValue: number; dayOfMonth: number | null; dayOfWeek: string | null;
  startOn: string; endOn: string | null; accountId: string; memberId: string;
  categoryId: string; assignedUserId: string; paused: boolean;
};

export function RecurringPage({ request, role, userId }: { request: RequestFn; role: HouseholdRole; userId: number }) {
  const [section, setSection] = useState<'pending' | 'rules'>('pending');
  const [draft, setDraft] = useState<RuleDraft | null>(null);
  const [rulePage, setRulePage] = useState(0);
  const [occurrencePage, setOccurrencePage] = useState(0);
  const [selectedOccurrenceIds, setSelectedOccurrenceIds] = useState<number[]>([]);
  const [payment, setPayment] = useState<RecurringOccurrence | null>(null);
  const queryClient = useQueryClient();
  const manager = isManager(role);
  const fundsError = useFundsRefresh();
  const rules = useQuery({ queryKey: ['recurring-rules', 'page', rulePage], queryFn: () => request<Page<RecurringRule>>(`/api/recurring-rules?includeInactive=true&page=${rulePage}&size=50`, { responseType: 'page' }) });
  const ruleOptions = useQuery({ queryKey: ['recurring-rules', 'all-reference'], queryFn: () => readAllPages(page => request<Page<RecurringRule>>(`/api/recurring-rules?includeInactive=true&page=${page}&size=50`, { responseType: 'page' })) });
  const occurrences = useQuery({ queryKey: ['recurring-occurrences', 'pending-page', occurrencePage], queryFn: () => request<Page<RecurringOccurrence>>(`/api/recurring-occurrences?status=PENDING&page=${occurrencePage}&size=50`, { responseType: 'page' }) });
  const accounts = useQuery({ queryKey: ['accounts', 'all-options'], queryFn: () => readAllPages(page => request<Page<Account>>(`/api/accounts?page=${page}&size=50`, { responseType: 'page' })) });
  const categories = useQuery({ queryKey: ['categories', 'flat-all-options'], queryFn: () => readAllPages(page => request<Page<Category>>(`/api/categories?projection=flat&page=${page}&size=50`, { responseType: 'page' })) });
  const members = useQuery({ queryKey: ['members'], queryFn: () => request<Member[]>('/api/members') });
  const memberships = useQuery({ queryKey: ['memberships', 'all-options'], queryFn: () => readAllPages(page => request<Page<Membership>>(`/api/family/memberships?page=${page}&size=50`, { responseType: 'page' })) });
  const ruleMap = useMemo(() => new Map(ruleOptions.data?.map(rule => [rule.id, rule]) ?? []), [ruleOptions.data]);
  const pendingItems = occurrences.data?.items ?? [];
  const selectableItems = pendingItems.filter(item => item.assignedUserId === userId);
  const allSelected = selectableItems.length > 0 && selectableItems.every(item => selectedOccurrenceIds.includes(item.id));
  usePageRecovery(rulePage, rules.data, setRulePage);
  usePageRecovery(occurrencePage, occurrences.data, setOccurrencePage);
  const refreshRecurring = () => {
    void queryClient.invalidateQueries({ queryKey: ['recurring-rules'] });
    void queryClient.invalidateQueries({ queryKey: ['recurring-occurrences'] });
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
  };
  const save = useMutation({
    mutationFn: (value: RuleDraft) => request<RecurringRule>(value.id ? `/api/recurring-rules/${value.id}` : '/api/recurring-rules', {
      method: value.id ? 'PATCH' : 'POST',
      body: { ...value, accountId: Number(value.accountId), memberId: Number(value.memberId), categoryId: Number(value.categoryId), assignedUserId: Number(value.assignedUserId), dayOfMonth: value.scheduleType === 'WEEKLY' ? null : value.dayOfMonth, dayOfWeek: value.scheduleType === 'WEEKLY' ? value.dayOfWeek : null },
    }),
    onSuccess: () => { setDraft(null); refreshRecurring(); },
  });
  const archive = useMutation({ mutationFn: (id: number) => request<void>(`/api/recurring-rules/${id}`, { method: 'DELETE' }), onSuccess: refreshRecurring });
  const confirm = useMutation({ mutationFn: (id: number) => request<RecurringOccurrence>(`/api/recurring-occurrences/${id}/confirm`, { method: 'POST' }), onError: fundsError, onSuccess: () => { setPayment(null); refreshRecurring(); } });
  const confirmBatch = useMutation({ mutationFn: (ids: number[]) => request('/api/recurring-occurrences/confirm', { method: 'POST', body: { occurrenceIds: ids } }), onError: fundsError, onSuccess: () => { setSelectedOccurrenceIds([]); refreshRecurring(); } });
  const skip = useMutation({ mutationFn: (id: number) => request<RecurringOccurrence>(`/api/recurring-occurrences/${id}/cancel`, { method: 'POST' }), onSuccess: (_data, id) => { setSelectedOccurrenceIds(ids => ids.filter(item => item !== id)); refreshRecurring(); } });
  const newRule = (): RuleDraft => ({ kind: 'expense', amount: '', scheduleType: 'MONTHLY', intervalValue: 1, dayOfMonth: 1, dayOfWeek: null, startOn: businessDate(), endOn: null, accountId: '', memberId: '', categoryId: '', assignedUserId: '', paused: false });
  const editRule = (item: RecurringRule): RuleDraft => ({ id: item.id, kind: item.kind, amount: item.amount, scheduleType: item.scheduleType, intervalValue: item.intervalValue, dayOfMonth: item.dayOfMonth, dayOfWeek: item.dayOfWeek, startOn: item.startOn, endOn: item.endOn, accountId: String(item.accountId), memberId: String(item.memberId), categoryId: String(item.categoryId), assignedUserId: String(item.assignedUserId), paused: item.paused });
  const toggleSelected = (id: number) => setSelectedOccurrenceIds(ids => ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id]);
  const toggleAll = () => setSelectedOccurrenceIds(allSelected ? [] : selectableItems.map(item => item.id));

  return <PageScaffold title="周期账单" description="到期先进入待确认，确认后才生成真实收支。" primaryAction={manager ? { label: '新建周期规则', onClick: () => setDraft(newRule()) } : undefined}>
    <nav className="segmented-tabs" aria-label="周期账单视图"><button className={section === 'pending' ? 'active' : ''} onClick={() => setSection('pending')}>待确认账单</button><button className={section === 'rules' ? 'active' : ''} onClick={() => setSection('rules')}>规则管理</button></nav>
    <FormError error={confirm.error || confirmBatch.error || skip.error || archive.error} />
    <div className="recurring-view">
      {section === 'pending' && <DataPanel title="待确认" meta={`${occurrences.data?.totalElements ?? 0} 个发生项`} action={selectableItems.length > 0 ? <span><label><input type="checkbox" aria-label="全选当前页" checked={allSelected} onChange={toggleAll} /> 全选当前页</label>{selectedOccurrenceIds.length > 0 && <Button size="small" loading={confirmBatch.isPending} onClick={() => confirmBatch.mutate(selectedOccurrenceIds)}>批量确认 {selectedOccurrenceIds.length} 条</Button>}</span> : undefined}>
        <QueryState loading={occurrences.isLoading || ruleOptions.isLoading} error={occurrences.error || ruleOptions.error} empty={!pendingItems.length && occurrencePage === 0} emptyTitle="没有待确认账单"><><div className="task-list">{pendingItems.map(item => { const rule = ruleMap.get(item.ruleId); const allowed = item.assignedUserId === userId; return <article key={item.id}><div className="task-date"><b>{item.dueOn.slice(8)}</b><span>{item.dueOn.slice(0, 7)}</span></div><div><div className="recurring-item-heading"><KindTag kind={rule?.kind} /><h3>{rule?.categoryName ?? `规则 #${item.ruleId}`}</h3></div><p>{rule?.accountName ?? '账户信息不可用'} · {rule?.assignedUserName ?? '未分配'}</p></div><strong className={`recurring-amount ${rule?.kind ?? ''}`}>{kindSign(rule?.kind)}{money(rule?.amount)}</strong>{allowed ? <><input type="checkbox" aria-label={`选择 ${item.dueOn}`} checked={selectedOccurrenceIds.includes(item.id)} onChange={() => toggleSelected(item.id)} /><Button size="small" loading={confirm.isPending} onClick={() => { confirm.reset(); setPayment(item); }}>确认入账</Button><Button size="small" theme="borderless" loading={skip.isPending} onClick={() => skip.mutate(item.id)}>跳过本期</Button></> : <StatusTag>由其他成员确认</StatusTag>}</article>; })}</div><PaginationControls page={occurrencePage} totalPages={occurrences.data?.totalPages ?? 0} hasNext={occurrences.data?.hasNext ?? false} onPageChange={setOccurrencePage} label="待确认账单" /></></QueryState>
      </DataPanel>}
      {section === 'rules' && <DataPanel title="周期规则" meta="月度、季度、年度与周度计划"><QueryState loading={rules.isLoading} error={rules.error} empty={!rules.data?.items.length && rulePage === 0} emptyTitle="还没有周期规则"><><div className="rule-list">{rules.data?.items.map(item => <article key={item.id}><header><div><div className="recurring-item-heading"><KindTag kind={item.kind} /><h3>{item.categoryName}</h3></div><StatusTag tone={item.paused ? 'warning' : 'success'}>{!item.active ? '已归档' : item.paused ? '已暂停' : '执行中'}</StatusTag></div><strong className={`recurring-amount ${item.kind}`}>{kindSign(item.kind)}{money(item.amount)}</strong></header><p>{scheduleText(item)} · 下次 {item.nextDueOn ?? '无'}</p><footer>{item.accountName} · {item.memberName} · {item.assignedUserName}{manager && item.active && <span><button onClick={() => setDraft(editRule(item))}>编辑</button><button onClick={() => archive.mutate(item.id)}>归档</button></span>}</footer></article>)}</div><PaginationControls page={rulePage} totalPages={rules.data?.totalPages ?? 0} hasNext={rules.data?.hasNext ?? false} onPageChange={setRulePage} label="周期规则" /></></QueryState></DataPanel>}
    </div>
    <Drawer draft={draft} sessionKey={draft?.id} busy={save.isPending} onSessionStart={save.reset} open={draft !== null} title={draft?.id ? '编辑周期规则' : '新建周期规则'} onClose={() => setDraft(null)}>{draft && <form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); save.mutate(draft); }}><FormError error={save.error} /><fieldset className={`recurring-kind-selector ${draft.kind}`}><legend>收支类型</legend><p>{draft.kind === 'income' ? '收入：会增加家庭账户余额' : '支出：会减少家庭账户余额'}</p><label>类型<select name="kind" value={draft.kind} onChange={e => setDraft({ ...draft, kind: e.target.value as TransactionKind, categoryId: '' })}><option value="expense">支出</option><option value="income">收入</option></select></label></fieldset><label>金额<input name="amount" required inputMode="decimal" value={draft.amount} onChange={e => setDraft({ ...draft, amount: e.target.value })} /></label><label>频率<select name="scheduleType" value={draft.scheduleType} onChange={e => { const scheduleType = e.target.value as RecurringScheduleType; setDraft({ ...draft, scheduleType, dayOfMonth: scheduleType === 'WEEKLY' ? null : 1, dayOfWeek: scheduleType === 'WEEKLY' ? 'MONDAY' : null }); }}><option value="MONTHLY">每月</option><option value="QUARTERLY">每季度</option><option value="YEARLY">每年</option><option value="WEEKLY">每周</option></select></label><label>间隔<input name="intervalValue" type="number" min="1" max="24" value={draft.intervalValue} onChange={e => setDraft({ ...draft, intervalValue: Number(e.target.value) })} /></label>{draft.scheduleType === 'WEEKLY' ? <label>星期<select name="dayOfWeek" value={draft.dayOfWeek ?? 'MONDAY'} onChange={e => setDraft({ ...draft, dayOfWeek: e.target.value })}>{[['MONDAY', '周一'], ['TUESDAY', '周二'], ['WEDNESDAY', '周三'], ['THURSDAY', '周四'], ['FRIDAY', '周五'], ['SATURDAY', '周六'], ['SUNDAY', '周日']].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label> : <label>{draft.scheduleType === 'YEARLY' ? '每年日期' : draft.scheduleType === 'QUARTERLY' ? '季度日期' : '每月日期'}<input name="dayOfMonth" aria-label="每月日期" type="number" min="1" max="31" required value={draft.dayOfMonth ?? 1} onChange={e => setDraft({ ...draft, dayOfMonth: Number(e.target.value) })} /></label>}<label>开始日期<input name="startOn" type="date" value={draft.startOn} onChange={e => setDraft({ ...draft, startOn: e.target.value })} /></label><label>结束日期（可选）<input name="endOn" type="date" value={draft.endOn ?? ''} onChange={e => setDraft({ ...draft, endOn: e.target.value || null })} /></label><label>账户<select name="accountId" required value={draft.accountId} onChange={e => setDraft({ ...draft, accountId: e.target.value })}><option value="">请选择</option><AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} /></select></label><label>分类<select name="categoryId" required value={draft.categoryId} onChange={e => setDraft({ ...draft, categoryId: e.target.value })}><option value="">请选择</option>{categories.data?.filter(item => item.kind === draft.kind).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>归属成员<select name="memberId" required value={draft.memberId} onChange={e => setDraft({ ...draft, memberId: e.target.value })}><option value="">请选择</option>{members.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>确认人<select name="assignedUserId" required value={draft.assignedUserId} onChange={e => setDraft({ ...draft, assignedUserId: e.target.value })}><option value="">请选择</option>{memberships.data?.map(item => <option key={item.userId} value={item.userId}>{item.displayName}</option>)}</select></label><label className="switch-line"><input name="paused" type="checkbox" checked={draft.paused} onChange={e => setDraft({ ...draft, paused: e.target.checked })} />暂停规则</label><Button htmlType="submit" theme="solid" type="primary" loading={save.isPending}>保存周期规则</Button></form>}</Drawer>
    <Drawer open={payment !== null} busy={confirm.isPending} sessionKey={payment?.id} title="记录周期账单" description="以今天作为实际收付款日期；计划到期日保留不变。不会执行银行扣款。" onClose={() => { setPayment(null); confirm.reset(); }}>{payment && <><FormError error={confirm.error} /><p>计划到期日 {payment.dueOn} · 实际入账日 {businessDate()}</p><PaymentPreview account={accounts.data?.find(a => a.id === ruleMap.get(payment.ruleId)?.accountId)} amount={ruleMap.get(payment.ruleId)?.amount} incoming={ruleMap.get(payment.ruleId)?.kind === 'income'} /><Button theme="solid" loading={confirm.isPending} disabled={payment.assignedUserId !== userId} onClick={() => confirm.mutate(payment.id)}>记录本次账单</Button></>}</Drawer>
  </PageScaffold>;
}

function scheduleText(item: RecurringRule) {
  if (item.scheduleType === 'WEEKLY') return `每 ${item.intervalValue} 周 · ${item.dayOfWeek}`;
  const unit = item.scheduleType === 'YEARLY' ? '年' : item.scheduleType === 'QUARTERLY' ? '季度' : '个月';
  return `每 ${item.intervalValue} ${unit} · ${item.dayOfMonth} 日`;
}

function kindLabel(kind?: TransactionKind) {
  return kind === 'income' ? '收入' : kind === 'expense' ? '支出' : '类型未知';
}

function kindSign(kind?: TransactionKind) {
  return kind === 'income' ? '+' : kind === 'expense' ? '-' : '';
}

function KindTag({ kind }: { kind?: TransactionKind }) {
  return <span className={`recurring-kind-tag ${kind ?? 'unknown'}`} aria-label={`收支类型：${kindLabel(kind)}`}>{kindLabel(kind)}</span>;
}
