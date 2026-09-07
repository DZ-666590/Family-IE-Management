import { useState } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Drawer } from '../features/common';
import { createMemoryRouter, Link, Outlet, RouterProvider } from 'react-router-dom';
import { DraftGuardProvider, useDraftRegistry } from './draft-guard';

function FormFixture() {
  const [open, setOpen] = useState(true);
  const [amount, setAmount] = useState('');
  return <><button onClick={() => { setAmount(''); setOpen(true); }}>打开</button><Drawer open={open} title="记一笔" draft={{ amount }} onClose={() => setOpen(false)}><label>金额<input value={amount} onChange={e => setAmount(e.target.value)} /></label></Drawer></>;
}

describe('unsaved drawer drafts', () => {
  it('restores the opener after confirming a discard that removes both dialogs', async () => {
    const user = userEvent.setup(); render(<FormFixture />);
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: '打开' }));
    await user.type(screen.getByLabelText('金额'), '3');
    await user.click(screen.getByRole('button', { name: '关闭' }));
    await user.click(screen.getByRole('button', { name: '放弃修改' }));
    expect(screen.getByRole('button', { name: '打开' })).toHaveFocus();
  });
  it.each(['button', 'backdrop', 'escape'])('protects edited input on %s close and restores the draft on cancel', async close => {
    const user = userEvent.setup(); render(<FormFixture />);
    await user.type(screen.getByLabelText('金额'), '28.50');
    if (close === 'button') await user.click(screen.getByRole('button', { name: '关闭' }));
    if (close === 'backdrop') fireEvent.mouseDown(screen.getByRole('dialog').parentElement!);
    if (close === 'escape') await user.keyboard('{Escape}');
    const confirmation = screen.getByRole('dialog', { name: '放弃未保存的修改？' });
    await user.click(within(confirmation).getByRole('button', { name: '继续编辑' }));
    expect(screen.getByLabelText('金额')).toHaveValue('28.50');
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: '放弃修改' }));
    expect(screen.queryByLabelText('金额')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '打开' }));
    expect(screen.getByLabelText('金额')).toHaveValue('');
  });
  it('closes untouched and reverted drafts without prompting', async () => {
    const user = userEvent.setup(); render(<FormFixture />);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '打开' }));
    await user.type(screen.getByLabelText('金额'), '9');
    await user.clear(screen.getByLabelText('金额'));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

it('blocks browser back and releases the intended navigation after confirmation', async () => {
  const router = createMemoryRouter([{ element: <DraftGuardProvider><Outlet /></DraftGuardProvider>, children: [
    { path: '/form', element: <FormFixture /> }, { path: '/previous', element: <p>之前页面</p> }
  ] }], { initialEntries: ['/previous', '/form'] });
  const user = userEvent.setup(); render(<RouterProvider router={router} />);
  await user.type(screen.getByLabelText('金额'), '88');
  await act(async () => { await router.navigate(-1); });
  expect(router.state.location.pathname).toBe('/form');
  await user.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(screen.getByLabelText('金额')).toHaveValue('88');
  await act(async () => { await router.navigate(-1); });
  await user.click(screen.getByRole('button', { name: '放弃修改' }));
  expect(await screen.findByText('之前页面')).toBeInTheDocument();
});

it.each(['/form', '/form?view=history'])('discards retained forms on navigation to %s and protects a new draft', async target => {
  const router = createMemoryRouter([{ path: '/form', element: <DraftGuardProvider><Link to={target}>切换视图</Link><FormFixture /></DraftGuardProvider> }], { initialEntries: ['/form'] });
  const user = userEvent.setup(); render(<RouterProvider router={router} />);
  await user.type(screen.getByLabelText('金额'), '88');
  await user.click(screen.getByRole('link', { name: '切换视图' }));
  await user.click(screen.getByRole('button', { name: '放弃修改' }));
  expect(screen.queryByLabelText('金额')).not.toBeInTheDocument();
  expect(router.state.location.pathname + router.state.location.search).toBe(target);
  await user.click(screen.getByRole('button', { name: '打开' }));
  expect(screen.getByLabelText('金额')).toHaveValue('');
  await user.type(screen.getByLabelText('金额'), '99');
  await user.click(screen.getByRole('link', { name: '切换视图' }));
  expect(screen.getByRole('dialog', { name: '放弃未保存的修改？' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(screen.getByLabelText('金额')).toHaveValue('99');
});

it('clears mandatory-session drafts even while a blocked navigation is waiting', async () => {
  function ClearSession() { const registry = useDraftRegistry(); return <button onClick={() => registry?.clear()}>会话结束</button>; }
  const router = createMemoryRouter([{ element: <DraftGuardProvider><ClearSession /><Outlet /></DraftGuardProvider>, children: [
    { path: '/', element: <><Link to="/login">离开</Link><FormFixture /></> }, { path: '/login', element: <p>登录页面</p> }
  ] }]);
  const user = userEvent.setup(); render(<RouterProvider router={router} />);
  await user.type(screen.getByLabelText('金额'), '88');
  const unload = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  await user.click(screen.getByRole('link', { name: '离开' }));
  await user.click(screen.getByRole('button', { name: '会话结束' }));
  expect(await screen.findByText('登录页面')).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  const after = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(after);
  expect(after.defaultPrevented).toBe(false);
});

it('waits for pending saves and updates the exit gate when saving fails', async () => {
  function SavingForm() {
    const [busy, setBusy] = useState(false);
    const [value, setValue] = useState('');
    return <><Link to="/next">离开</Link><button onClick={() => setBusy(true)}>开始保存</button><button onClick={() => setBusy(false)}>保存失败</button><Drawer title="保存中" open draft={{ value }} busy={busy} onClose={() => {}}><input aria-label="内容" value={value} onChange={e => setValue(e.target.value)} /></Drawer></>;
  }
  const router = createMemoryRouter([{ element: <DraftGuardProvider><Outlet /></DraftGuardProvider>, children: [
    { path: '/', element: <SavingForm /> }, { path: '/next', element: <p>下一页</p> }
  ] }]);
  const user = userEvent.setup(); render(<RouterProvider router={router} />);
  await user.type(screen.getByLabelText('内容'), 'changed');
  await user.click(screen.getByRole('button', { name: '开始保存' }));
  await user.click(screen.getByRole('link', { name: '离开' }));
  expect(screen.getByRole('button', { name: '放弃修改' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '保存失败' }));
  expect(screen.getByRole('button', { name: '放弃修改' })).not.toBeDisabled();
});

it.each(['select', 'date', 'checkbox', 'radio'])('detects meaningful %s changes and their reversal', async kind => {
  function Fields() {
    const [open, setOpen] = useState(true);
    const [choice, setChoice] = useState('a');
    const [date, setDate] = useState('2026-09-01');
    const [checked, setChecked] = useState(false);
    return <Drawer open={open} title="编辑" draft={{ choice, date, checked }} onClose={() => setOpen(false)}>
      <select aria-label="选项" value={choice} onChange={e => setChoice(e.target.value)}><option value="a">A</option><option value="b">B</option></select>
      <input aria-label="日期" type="date" value={date} onChange={e => setDate(e.target.value)} />
      <input aria-label="勾选" type="checkbox" checked={checked} onChange={e => setChecked(e.target.checked)} />
      <input aria-label="单选 A" type="radio" checked={choice === 'a'} onChange={() => setChoice('a')} />
      <input aria-label="单选 B" type="radio" checked={choice === 'b'} onChange={() => setChoice('b')} />
    </Drawer>;
  }
  const user = userEvent.setup(); render(<Fields />);
  const change = async (revert: boolean) => {
    if (kind === 'select') await user.selectOptions(screen.getByLabelText('选项'), revert ? 'a' : 'b');
    if (kind === 'date') fireEvent.change(screen.getByLabelText('日期'), { target: { value: revert ? '2026-09-01' : '2026-09-02' } });
    if (kind === 'checkbox') await user.click(screen.getByLabelText('勾选'));
    if (kind === 'radio') await user.click(screen.getByLabelText(revert ? '单选 A' : '单选 B'));
  };
  await change(false); await user.keyboard('{Escape}');
  expect(screen.getByRole('dialog', { name: '放弃未保存的修改？' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '继续编辑' }));
  await change(true); await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
