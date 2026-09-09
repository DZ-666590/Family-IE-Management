import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi, it, expect } from 'vitest';
import { AiSettingsCard } from './AiSettingsCard';
import type { ApiRequest } from '../../api/client';
const configured = {provider: '阿里云百炼', model: 'qwen3.8-max', ready: true, dailyRequestLimit: 20};
it('shows shared service on HTTP without any secret fields or automatic provider calls', async () => {
 const request = vi.fn().mockResolvedValue(configured);
 render(<AiSettingsCard request={request as ApiRequest} />);
 expect(await screen.findByText('qwen3.8-max')).toBeInTheDocument();
 expect(screen.queryByLabelText('API 密钥')).not.toBeInTheDocument();
 expect(screen.getByRole('button', {name:'测试系统连接'})).toBeDisabled();
 expect(request).toHaveBeenCalledTimes(1);
 expect(localStorage.length).toBe(0);
});
it('requires explicit consent and sends only directory confirmation', async () => {
 const user=userEvent.setup();
 const request=vi.fn().mockImplementation(async (_path, options) => options?.method==='POST' ? {message:'目录连接成功'} : configured);
 render(<AiSettingsCard request={request as ApiRequest} />);
 await user.click(await screen.findByRole('checkbox'));
 await user.click(screen.getByRole('button',{name:'测试系统连接'}));
 expect(await screen.findByText('目录连接成功')).toBeInTheDocument();
 expect(request).toHaveBeenCalledWith('/api/me/ai-settings/test',{method:'POST',body:{confirmed:true}});
 expect(screen.getByRole('checkbox')).not.toBeChecked();
});
it('shows missing server configuration', async () => {
 render(<AiSettingsCard request={vi.fn().mockResolvedValue({...configured,ready:false}) as ApiRequest} />);
 expect(await screen.findByText('等待管理员配置')).toBeInTheDocument();
 expect(screen.getByRole('checkbox')).toBeDisabled();
});
