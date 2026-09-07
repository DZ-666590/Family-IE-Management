import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Session } from '../api/contracts';
import { SIDEBAR_PREFERENCE_KEY, WorkspaceLayout } from './WorkspaceLayout';

const session = (role: Session['role']): Session => ({
  userId: 7,
  householdId: 11,
  email: `${role.toLowerCase()}@example.com`,
  displayName: role === 'OWNER' ? '凯文' : role === 'ADMIN' ? '管理员' : '家庭成员',
  role,
  username: `${role.toLowerCase()}@example.com`
});

const renderLayout = (role: Session['role'] = 'OWNER') => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/workspace/overview']}>
      <WorkspaceLayout session={session(role)} onLogout={vi.fn()} />
    </MemoryRouter>
  </QueryClientProvider>
);

it.each([
  ['OWNER', ['家庭与成员', '系统设置'], ['邀请成员'], []],
  ['ADMIN', ['家庭与成员', '系统设置'], ['邀请成员'], []],
  ['MEMBER', ['家庭与成员', '系统设置'], [], ['邀请成员']]
] as const)('shows the exact %s navigation and administrative actions', async (role, modules, visibleActions, hiddenActions) => {
  renderLayout(role);
  const nav = screen.getByRole('navigation', { name: '模块导航' });
  for (const module of modules) expect(within(nav).queryByRole('link', { name: module })).not.toBeInTheDocument();
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '个人中心' }));
  expect(screen.getByRole('menuitem', { name: '家庭与成员' })).toBeInTheDocument();
  expect(screen.getByRole('menuitem', { name: '账号设置' })).toBeInTheDocument();
  for (const action of visibleActions) expect(screen.getByRole('menuitem', { name: action })).toBeInTheDocument();
  for (const action of hiddenActions ?? []) expect(screen.queryByRole('menuitem', { name: action })).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '资产账户' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '投资持仓' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '贷款计划' })).toBeInTheDocument();
  if (role === 'MEMBER') {
    await userEvent.click(screen.getByRole('link', { name: '资产账户' }));
    expect(screen.getByText('当前为只读协作视图')).toBeInTheDocument();
  }
});

it('navigates the profile menu by keyboard and restores focus on Escape', async () => {
  renderLayout();
  const user = userEvent.setup();
  const avatar = screen.getByRole('button', { name: '个人中心' });
  await user.click(avatar);
  expect(screen.getByRole('menuitem', { name: '家庭与成员' })).toHaveFocus();
  await user.keyboard('{End}');
  expect(screen.getByRole('menuitem', { name: '退出登录' })).toHaveFocus();
  await user.keyboard('{ArrowUp}');
  expect(screen.getByRole('menuitem', { name: '账号设置' })).toHaveFocus();
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expect(avatar).toHaveFocus();
});

it('opens the invitation form from the avatar without creating an invite', async () => {
  renderLayout();
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '个人中心' }));
  await user.click(screen.getByRole('menuitem', { name: '邀请成员' }));
  expect(await screen.findByRole('dialog', { name: '邀请成员' })).toBeInTheDocument();
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});

it('keeps one compact navigation and remembers its expanded preference', async () => {
  const user = userEvent.setup();
  const first = renderLayout();
  expect(screen.queryByRole('navigation', { name: '应用导航' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '收起侧边栏' }));
  expect(screen.getByRole('complementary', { name: '工作区侧栏' })).toHaveClass('is-collapsed');
  expect(localStorage.getItem(SIDEBAR_PREFERENCE_KEY)).toBe('true');
  first.unmount();
  renderLayout();
  expect(screen.getByRole('complementary', { name: '工作区侧栏' })).toHaveClass('is-collapsed');
  await user.click(screen.getByRole('button', { name: '展开侧边栏' }));
  expect(screen.getByRole('navigation', { name: '模块导航' })).toBeInTheDocument();
});

it('closes the mobile module drawer with Escape and returns focus to its trigger', async () => {
  const user = userEvent.setup();
  renderLayout();
  const trigger = screen.getByRole('button', { name: '打开模块导航' });
  await user.click(trigger);
  expect(screen.getByRole('dialog', { name: '模块导航' })).toBeInTheDocument();
  await user.keyboard('{Escape}');
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '模块导航' })).not.toBeInTheDocument());
  expect(trigger).toHaveFocus();
});

it('closes the mobile module drawer after route selection and outside click', async () => {
  const user = userEvent.setup();
  renderLayout();
  const trigger = screen.getByRole('button', { name: '打开模块导航' });
  await user.click(trigger);
  const drawer = screen.getByRole('dialog', { name: '模块导航' });
  await user.click(within(drawer).getByRole('link', { name: '预算管理' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '模块导航' })).not.toBeInTheDocument());
  await user.click(trigger);
  fireEvent.mouseDown(screen.getByTestId('mobile-drawer-backdrop'));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '模块导航' })).not.toBeInTheDocument());
  expect(trigger).toHaveFocus();
});
