import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NotificationsPage } from './NotificationsPage';
import type { RequestFn } from '../common';

it.each(['OWNER', 'ADMIN', 'MEMBER'] as const)('keeps read/resolve and gates generation for %s', async role => {
  const writes: string[] = [];
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') { writes.push(path); return {} as T; }
    return { unreadCount: 1, items: [{ id: 1, title: '待办', body: '提醒内容', type: 'BUDGET_LIMIT', referenceType: 'BUDGET', dueAt: '2026-09-01', readAt: null, resolvedAt: null }] } as T;
  };
  render(<QueryClientProvider client={new QueryClient()}><NotificationsPage request={request} role={role} /></QueryClientProvider>);
  await screen.findByText('待办');
  expect(Boolean(screen.queryByRole('button', { name: '刷新今日提醒' }))).toBe(role !== 'MEMBER');
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '标为已读' }));
  await user.click(screen.getByRole('button', { name: '标记完成' }));
  expect(writes).toEqual(['/api/notifications/1/read', '/api/notifications/1/resolve']);
});
