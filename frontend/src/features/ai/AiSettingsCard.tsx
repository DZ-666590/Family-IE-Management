import { useEffect, useRef, useState, type FormEvent } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Input from '@douyinfe/semi-ui/lib/es/input';
import type { ApiRequest } from '../../api/client';
import { ConfirmDialog, FormError } from '../common';
import { useDraftProtection } from '../../shared/draft-guard';
import './ai-settings.scss';

interface AiSettings {
  baseUrl: string; model: string; keyConfigured: boolean; maskedKey: string;
  storageReady: boolean; allowedHosts: string[]; updatedAt?: string;
}
interface ConnectionResult { reachable: boolean; modelListed: boolean; message: string }
const endpoint = '/api/me/ai-settings';

export function AiSettingsCard({ request, secureTransport = window.location.protocol === 'https:' }: {
  request: ApiRequest; secureTransport?: boolean;
}) {
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [savedKey, setSavedKey] = useState(0);
  const generation = useRef(0);
  const pending = useRef(false);
  function apply(value: AiSettings) {
    setSettings(value); setBaseUrl(value.baseUrl); setModel(value.model); setApiKey('');
    setConfirmed(false); setSavedKey(v => v + 1);
  }
  useEffect(() => {
    const scope = ++generation.current;
    setLoading(true);
    request<AiSettings>(endpoint).then(value => { if (generation.current === scope) apply(value); })
      .catch(cause => { if (generation.current === scope) setError(cause); })
      .finally(() => { if (generation.current === scope) setLoading(false); });
    return () => { generation.current += 1; };
  }, [request]);
  useDraftProtection({ active: !loading, draft: { baseUrl, model, keyEntered: apiKey !== '' }, busy, savedKey,
    onDiscard: () => {
      generation.current += 1; pending.current = false; setBusy(false); setApiKey(''); setBaseUrl(''); setModel('');
      setSettings(null); setConfirmed(false); setError(null); setMessage(''); setRemoveOpen(false);
    }
  });
  const ready = secureTransport && !!settings?.storageReady && !!settings.allowedHosts.length;
  const changed = !!apiKey || baseUrl !== settings?.baseUrl || model !== settings?.model;
  function edit(update: () => void) { update(); setConfirmed(false); setMessage(''); }
  // Keep the write outside mutation/query caches: only the current request holds the secret.
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!ready || pending.current) return;
    const scope = generation.current;
    pending.current = true; setBusy(true); setError(null); setMessage(''); setConfirmed(false);
    const body = { baseUrl, model, ...(apiKey ? { apiKey } : {}) };
    setApiKey('');
    try {
      const value = await request<AiSettings>(endpoint, { method: 'PUT', body });
      if (scope !== generation.current) return;
      apply(value); setMessage('AI 配置已保存');
    } catch (cause) { if (scope === generation.current) setError(cause); }
    finally {
      delete body.apiKey;
      if (scope === generation.current) { pending.current = false; setBusy(false); }
    }
  }
  async function test() {
    if (!ready || !confirmed || changed || pending.current) return;
    const scope = generation.current;
    pending.current = true; setBusy(true); setError(null); setMessage(''); setConfirmed(false);
    try {
      const value = await request<ConnectionResult>(`${endpoint}/test`, { method: 'POST', body: { confirmed: true } });
      if (scope === generation.current) setMessage(value.message);
    } catch (cause) { if (scope === generation.current) setError(cause); }
    finally { if (scope === generation.current) { pending.current = false; setBusy(false); } }
  }
  async function remove() {
    if (pending.current) return;
    const scope = generation.current;
    pending.current = true; setBusy(true); setError(null); setMessage('');
    try {
      await request<void>(endpoint, { method: 'DELETE' });
      if (scope !== generation.current) return;
      if (settings) apply({ ...settings, baseUrl: '', model: '', keyConfigured: false, maskedKey: '' });
      setRemoveOpen(false); setMessage('AI 配置与密钥已删除');
    } catch (cause) { if (scope === generation.current) setError(cause); }
    finally { if (scope === generation.current) { pending.current = false; setBusy(false); } }
  }
  return <section className="settings-card ai-settings-card" aria-labelledby="ai-settings-title">
    <div><p className="section-kicker">个人偏好</p><h2 id="ai-settings-title">AI 服务配置</h2>
      <p>仅供你的账号使用。支持 OpenAI 兼容接口；保存配置不会发送家庭财务数据。</p></div>
    {loading ? <p role="status">正在读取 AI 配置…</p> : <>
      <FormError error={error} />
      {!secureTransport && <p className="source-note" role="alert">当前页面使用 HTTP，无法安全传输密钥。请通过 HTTPS 访问后再配置或测试 AI 服务。</p>}
      {settings && !settings.storageReady && <p className="source-note" role="alert">服务器尚未配置有效的加密密钥，AI 配置暂不可保存。请联系管理员。</p>}
      {settings && !settings.allowedHosts.length && <p className="source-note" role="alert">管理员尚未开放 AI 服务地址，请先联系管理员添加允许的服务商。</p>}
      {settings && <>
        <form className="settings-form" onSubmit={save}>
          <label htmlFor="ai-base-url">API 地址</label>
          <Input id="ai-base-url" name="aiBaseUrl" type="url" value={baseUrl} disabled={!ready || busy} required maxLength={500}
            placeholder="https://服务商域名/v1" onChange={v => edit(() => setBaseUrl(v))} aria-describedby="ai-url-help" />
          <p className="ai-field-help" id="ai-url-help">填写基础地址，不包含 /chat/completions。允许的域名：{settings.allowedHosts.join('、') || '暂无'}。更换地址需重新输入密钥。</p>
          <label htmlFor="ai-model">模型名称</label>
          <Input id="ai-model" name="aiModel" value={model} disabled={!ready || busy} required maxLength={120}
            placeholder="填写服务商提供的模型名称" onChange={v => edit(() => setModel(v))} />
          <label htmlFor="ai-api-key">API 密钥</label>
          <Input id="ai-api-key" name="aiApiKey" type="password" autoComplete="off" value={apiKey} disabled={!ready || busy} maxLength={2000}
            required={!settings.keyConfigured || baseUrl !== settings.baseUrl} placeholder={settings.keyConfigured ? '留空保留已保存密钥' : '输入密钥'}
            onChange={v => edit(() => setApiKey(v))} aria-describedby="ai-key-help" />
          <p className="ai-field-help" id="ai-key-help">{settings.keyConfigured ? `${settings.maskedKey} · 已配置` : '尚未配置密钥'}。密钥在服务器加密保存，读取时不回传；保存失败后也需重新输入。</p>
          <Button htmlType="submit" theme="solid" type="primary" disabled={!ready || busy} loading={busy}>保存 AI 配置</Button>
        </form>
        <div className="ai-connection-check">
          <h3>检查连接</h3><p>只请求已保存服务商的模型目录，不发送提示词或家庭账目，也不执行推理。第三方服务可能按其规则收费。</p>
          <label className="ai-consent"><input type="checkbox" checked={confirmed} disabled={!ready || !settings.keyConfigured || changed || busy}
            onChange={e => setConfirmed(e.target.checked)} />我确认向该服务商发送一次目录请求，并了解可能产生的费用</label>
          {changed && settings.keyConfigured && <p className="ai-field-help">请先保存修改，再测试已保存的连接。</p>}
          <div className="ai-settings-actions"><Button disabled={!ready || !settings.keyConfigured || !confirmed || changed || busy} onClick={test}>测试已保存的连接</Button>
            {settings.keyConfigured && <Button type="danger" disabled={busy} onClick={() => setRemoveOpen(true)}>删除 AI 配置</Button>}</div>
        </div>
      </>}
      {message && <p className="form-success" role="status">{message}</p>}
    </>}
    <ConfirmDialog open={removeOpen} title="删除个人 AI 配置？" detail="这会删除你的 API 地址、模型及已保存密钥。之后需要重新配置才能使用 AI 服务。"
      confirmLabel="删除配置" confirmDisabled={busy} onClose={() => !busy && setRemoveOpen(false)} onConfirm={remove} />
  </section>;
}
