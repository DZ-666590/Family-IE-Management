import { useEffect, useRef, useState } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { ApiRequest } from '../../api/client';
import { FormError } from '../common';
import './ai-settings.scss';
interface SystemStatus { provider: string; model: string; ready: boolean; dailyRequestLimit: number }
export function AiSettingsCard({ request }: { request: ApiRequest }) {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const generation = useRef(0);
  const pending = useRef(false);
  useEffect(() => {
    const scope = ++generation.current;
    request<SystemStatus>('/api/me/ai-settings').then(value => { if (scope === generation.current) setStatus(value); })
      .catch(cause => { if (scope === generation.current) setError(cause); });
    return () => { generation.current++; };
  }, [request]);
  async function test() {
    if (!confirmed || !status?.ready || pending.current) return;
    const scope = generation.current;
    pending.current = true; setBusy(true); setConfirmed(false); setError(null); setMessage('');
    try {
      const result = await request<{ message: string }>('/api/me/ai-settings/test', { method: 'POST', body: { confirmed: true } });
      if (scope === generation.current) setMessage(result.message);
    } catch (cause) { if (scope === generation.current) setError(cause); }
    finally { if (scope === generation.current) { pending.current = false; setBusy(false); } }
  }
  return <section className="settings-card ai-settings-card" aria-labelledby="ai-settings-title">
    <div><p className="section-kicker">系统服务</p><h2 id="ai-settings-title">AI 服务</h2><p>所有登录用户使用系统提供的模型，无需填写个人 API 密钥。</p></div>
    <FormError error={error} />
    {!status && !error && <p role="status">正在读取服务状态…</p>}
    {status && <>
      <dl><dt>服务商</dt><dd>{status.provider}</dd><dt>模型</dt><dd>{status.model}</dd><dt>状态</dt><dd>{status.ready ? '服务器已配置' : '等待管理员配置'}</dd></dl>
      <p>每人每日最多 {status.dailyRequestLimit} 次请求，另有系统总额度。服务商费用由系统账号承担。</p>
      {!status.ready && <p role="status">管理员需要在服务器上设置百炼 API Key 和接入地址，配置后重启服务。</p>}
      {window.location.protocol !== 'https:' && <p className="source-note">当前网站使用 HTTP。密钥不会传到浏览器，但文档上传仍为明文传输，请先使用脱敏材料。</p>}
      <div className="ai-connection-check"><h3>检查连接</h3><p>仅请求模型目录，不发送文档，也不执行推理。本次请求计入额度。</p>
        <label className="ai-consent"><input type="checkbox" checked={confirmed} disabled={!status.ready || busy} onChange={e => setConfirmed(e.target.checked)} />我确认发送一次目录请求，并了解可能产生的服务商费用</label>
        <Button disabled={!status.ready || !confirmed || busy} loading={busy} onClick={test}>测试系统连接</Button>
      </div>
    </>}
    {message && <p role="status">{message}</p>}
  </section>;
}
