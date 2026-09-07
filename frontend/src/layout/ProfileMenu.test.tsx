import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ProfileMenu } from './ProfileMenu';

it('dismisses outside and invokes logout only on selection', async () => {
  const logout = vi.fn();
  const user = userEvent.setup();
  render(<MemoryRouter><button>外部</button><ProfileMenu session={{ userId: 1, householdId: 1, role: 'MEMBER', displayName: '测试成员', email: 'member@example.com', username: 'member' }} onLogout={logout} /></MemoryRouter>);
  await user.click(screen.getByRole('button', { name: '个人中心' }));
  expect(screen.getByText('member@example.com')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '外部' }));
  expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  expect(logout).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: '个人中心' }));
  await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
  expect(logout).toHaveBeenCalledTimes(1);
});
