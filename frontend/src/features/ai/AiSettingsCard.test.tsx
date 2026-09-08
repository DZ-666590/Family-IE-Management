import { createMemoryRouter, RouterProvider, Outlet } from 'react-router-dom';
import { DraftGuardProvider, useDraftRegistry } from '../../shared/draft-guard';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi, it, expect } from 'vitest';
import { AiSettingsCard } from './AiSettingsCard';
import type { ApiRequest } from '../../api/client';
const empty = { baseUrl: '', model: '', keyConfigured: false, maskedKey: '', storageReady: true, allowedHosts: ['api.example.com'] };
it('never sends a secret on HTTP and explains why', async () => {
  const request = vi.fn().mockResolvedValue(empty);
  render(<AiSettingsCard request={request as ApiRequest} />);
  expect(await screen.findByText(/当前页面使用 HTTP/)).toBeInTheDocument();
  expect(screen.getByLabelText('API 密钥')).toBeDisabled();
  expect(screen.getByRole('button', { name: '保存 AI 配置' })).toBeDisabled();
  expect(request).toHaveBeenCalledTimes(1);
});
it('saves once, clears the secret and requires confirmation before any connection test', async () => {
  const user = userEvent.setup();
  let sent: unknown;
  const request = vi.fn().mockImplementation(async (_path, options) => {
    if (options?.method === 'PUT') {
      sent = structuredClone(options.body);
      return { ...empty, baseUrl: 'https://api.example.com/v1', model: 'sample', keyConfigured: true, maskedKey: '••••••••' };
    }
    return empty;
  });
  render(<AiSettingsCard request={request as ApiRequest} secureTransport />);
  await user.type(await screen.findByLabelText('API 地址'), 'https://api.example.com/v1');
  await user.type(screen.getByLabelText('模型名称'), 'sample');
  await user.type(screen.getByLabelText('API 密钥'), 'test-secret');
  await user.click(screen.getByRole('button', { name: '保存 AI 配置' }));
  expect(await screen.findByText('AI 配置已保存')).toBeInTheDocument();
  expect(screen.getByLabelText('API 密钥')).toHaveValue('');
  expect(localStorage.length).toBe(0);
  expect(screen.getByRole('button', { name: '测试已保存的连接' })).toBeDisabled();
  expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(0);
  expect(sent).toEqual({ baseUrl: 'https://api.example.com/v1', model: 'sample', apiKey: 'test-secret' });
});
it('only tests saved settings after explicit consent and confirms deletion', async () => {
  const user = userEvent.setup();
  const saved = { ...empty, baseUrl: 'https://api.example.com/v1', model: 'sample', keyConfigured: true, maskedKey: '••••••••' };
  const request = vi.fn().mockImplementation(async (_path, options) => options?.method === 'POST' ? { reachable: true, modelListed: true, message: '目录连接成功' } : saved);
  render(<AiSettingsCard request={request as ApiRequest} secureTransport />);
  const check = await screen.findByRole('checkbox');
  await user.click(check);
  await user.click(screen.getByRole('button', { name: '测试已保存的连接' }));
  expect(await screen.findByText('目录连接成功')).toBeInTheDocument();
  expect(check).not.toBeChecked();
  expect(request).toHaveBeenCalledWith('/api/me/ai-settings/test', { method: 'POST', body: { confirmed: true } });
  await user.click(screen.getByRole('button', { name: '删除 AI 配置' }));
  expect(request.mock.calls.filter(([, o]) => o?.method === 'DELETE')).toHaveLength(0);
  await user.click(screen.getByRole('button', { name: '删除配置' }));
  expect(await screen.findByText('AI 配置与密钥已删除')).toBeInTheDocument();
});
it('explains unavailable encryption and clears failed secret input', async () => {
  const user = userEvent.setup();
  const request = vi.fn().mockImplementation(async (_path, options) => {
    if (options?.method === 'PUT') throw new Error('配置保存失败');
    return empty;
  });
  render(<AiSettingsCard request={request as ApiRequest} secureTransport />);
  await user.type(await screen.findByLabelText('API 地址'), 'https://api.example.com/v1');
  await user.type(screen.getByLabelText('模型名称'), 'sample');
  await user.type(screen.getByLabelText('API 密钥'), 'test-secret');
  await user.click(screen.getByRole('button', { name: '保存 AI 配置' }));
  expect(await screen.findByText('配置保存失败')).toBeInTheDocument();
  expect(screen.getByLabelText('API 密钥')).toHaveValue('');
});
it('shows missing server encryption configuration', async () => {
  render(<AiSettingsCard request={vi.fn().mockResolvedValue({ ...empty, storageReady: false }) as ApiRequest} secureTransport />);
  expect(await screen.findByText(/服务器尚未配置有效的加密密钥/)).toBeInTheDocument();
  expect(screen.getByLabelText('API 密钥')).toBeDisabled();
});

it('clears private inputs on session cleanup and ignores late save failures', async () => {
  const user = userEvent.setup();
  let rejectSave: (error: Error) => void = () => {};
  const request = vi.fn().mockImplementation(async (_path, options) => options?.method === 'PUT'
    ? new Promise((_resolve, reject) => { rejectSave = reject; }) : empty);
  function ClearSession() { const registry = useDraftRegistry(); return <button onClick={() => registry?.clear()}>模拟会话退出</button>; }
  const router = createMemoryRouter([{ element: <DraftGuardProvider><ClearSession /><Outlet /></DraftGuardProvider>, children: [
    { path: '/', element: <AiSettingsCard request={request as ApiRequest} secureTransport /> }
  ] }]);
  render(<RouterProvider router={router} />);
  await user.type(await screen.findByLabelText('API 地址'), 'https://api.example.com/v1');
  await user.type(screen.getByLabelText('模型名称'), 'sample');
  await user.type(screen.getByLabelText('API 密钥'), 'test-secret');
  await user.click(screen.getByRole('button', { name: '保存 AI 配置' }));
  await user.click(screen.getByRole('button', { name: '模拟会话退出' }));
  await act(async () => { rejectSave(new Error('late-private-failure')); });
  expect(screen.queryByDisplayValue('test-secret')).not.toBeInTheDocument();
  expect(screen.queryByText('late-private-failure')).not.toBeInTheDocument();
});
