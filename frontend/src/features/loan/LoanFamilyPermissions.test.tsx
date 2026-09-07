import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage, annualRatePercentError, formatAnnualRatePercent, loanCreatePayload, type LoanDraft } from './LoansPage';
import { FamilyPage } from '../family/FamilyPage';
import type { RequestFn } from '../common';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;

it('keeps financial management read-only for members and owner controls exclusive', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/loans')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/family') return { id: 1, name: '凯文之家', status: 'ACTIVE', archivedAt: null };
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 2, userId: 8, email: 'member@example.com', displayName: '成员', role: 'MEMBER', status: 'ACTIVE' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.startsWith('/api/family/invites')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    throw new Error(`unexpected ${path}`);
  });
  const first = render(wrap(<LoansPage request={request as RequestFn} role="MEMBER" />));
  expect(await screen.findByText('当前为只读协作视图')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '新建贷款' })).not.toBeInTheDocument();
  first.unmount();
  render(wrap(<FamilyPage request={request as RequestFn} role="OWNER" householdName="凯文之家" />));
  expect(await screen.findByRole('button', { name: '归档家庭' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '邀请成员' })).toBeInTheDocument();
});

it('serializes loan form percentages and optional targets for the backend contract', () => {
  const draft: LoanDraft = {
    name: '测试房贷',
    type: 'MORTGAGE',
    linkedAssetId: '',
    memberId: '',
    assignedUserId: '1',
    paymentAccountId: '2',
    paymentCategoryId: '3',
    principal: '100000.00',
    annualRate: '4.9',
    termMonths: '360',
    repaymentMethod: 'EQUAL_PAYMENT',
    startOn: '2026-09-04',
    customSchedule: []
  };

  expect(loanCreatePayload(draft)).toMatchObject({
    linkedAssetId: null,
    memberId: null,
    assignedUserId: 1,
    paymentAccountId: 2,
    paymentCategoryId: 3,
    annualRate: 0.049,
    termMonths: 360,
    customSchedule: null
  });
  expect(loanCreatePayload({ ...draft, annualRate: '3.6' }).annualRate).toBe(0.036);
  expect(loanCreatePayload({ ...draft, annualRate: '3.1' }).annualRate).toBe(0.031);
});

it('validates annual rates as percent input before building a request payload', () => {
  const draft: LoanDraft = {
    name: '测试房贷', type: 'MORTGAGE', linkedAssetId: '', memberId: '', assignedUserId: '1',
    paymentAccountId: '2', paymentCategoryId: '3', principal: '100000.00', annualRate: '3.12345',
    termMonths: '360', repaymentMethod: 'EQUAL_PAYMENT', startOn: '2026-09-04', customSchedule: []
  };

  expect(annualRatePercentError('3.1234')).toBeNull();
  expect(annualRatePercentError('3.12345')).toContain('百分比');
  expect(annualRatePercentError('100.0001')).toContain('0 到 100');
  expect(() => loanCreatePayload(draft)).toThrow(/百分比/);
});

it('renders the stored fractional annual rate as a user-facing percentage', () => {
  expect(formatAnnualRatePercent('0.049000')).toBe('4.9');
});

it('pages through a long loan schedule to the final installment', async () => {
  const loan = { id: 4, name: '三十年房贷', type: 'MORTGAGE', linkedAssetId: null, memberId: null, assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, principal: '1000000.00', annualRate: '0.049000', termMonths: 360, repaymentMethod: 'EQUAL_PAYMENT', startOn: '2026-09-01', currentPrincipal: '1000000.00', status: 'ACTIVE' };
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/loans?')) return { items: [loan], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.includes('/schedule')) {
      const page = Number(new URLSearchParams(path.split('?')[1]).get('page'));
      const start = page * 50 + 1;
      const count = page === 7 ? 10 : 50;
      return { items: Array.from({ length: count }, (_, index) => ({ id: start + index, installmentNo: start + index, dueOn: '2026-10-01', principal: '1.00', interest: '1.00', status: 'PENDING', confirmedTransactionId: null })), page, size: 50, totalElements: 360, totalPages: 8, hasNext: page < 7 };
    }
    if (path.startsWith('/api/accounts') || path.startsWith('/api/assets') || path.startsWith('/api/family/memberships')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path.startsWith('/api/categories')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(wrap(<LoansPage request={request as RequestFn} role="MEMBER" userId={7} />));

  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  for (let page = 1; page < 8; page += 1) await user.click(await screen.findByRole('button', { name: '下一页' }));
  expect(await screen.findByText('360')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled();
});
