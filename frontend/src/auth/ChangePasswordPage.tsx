import { useEffect, useRef, useState, type FormEvent } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Input from '@douyinfe/semi-ui/lib/es/input';
import { useAuth } from './AuthProvider';
import { focusField } from './form-utils';
import { useDraftProtection } from '../shared/draft-guard';
import { FormError } from '../features/common';

export function ChangePasswordPage() {
  const { changePassword } = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [busy, setBusy] = useState(false);
  const [serverError, setServerError] = useState<unknown>(null);
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; }, []);
  useDraftProtection({ draft: { currentPassword, newPassword, confirmation }, busy,
    onDiscard: () => { generation.current += 1; setBusy(false); setCurrentPassword(''); setNewPassword(''); setConfirmation(''); setError(null); setServerError(null); setSuccess(false); }
  });

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const scope = generation.current;
    setSuccess(false);
    setServerError(null);
    if (!currentPassword) {
      setError('请输入当前密码');
      focusField('current-password');
      return;
    }
    if (newPassword.length < 8 || newPassword.length > 72) {
      setError('新密码需为 8–72 个字符');
      focusField('new-password');
      return;
    }
    if (newPassword !== confirmation) {
      setError('两次输入的新密码不一致');
      focusField('confirm-password');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await changePassword(currentPassword, newPassword);
      if (scope !== generation.current) return;
      setCurrentPassword('');
      setNewPassword('');
      setConfirmation('');
      setSuccess(true);
    } catch (cause) {
      if (scope !== generation.current) return;
      setServerError(cause);
    } finally {
      if (scope === generation.current) setBusy(false);
    }
  }

  return (
    <section className="settings-card" aria-labelledby="password-title">
      <div>
        <p className="section-kicker">账号安全</p>
        <h2 id="password-title">修改密码</h2>
        <p>更新后请使用新密码继续登录。密码不会保存在浏览器中。</p>
      </div>
      {error && <div id="password-validation-error" className="form-alert" role="alert">{error}</div>}
      {success && <div className="form-success" role="status">密码已更新</div>}
      <form className="settings-form" onSubmit={submit} noValidate>
        <FormError error={serverError} />
        <label htmlFor="current-password">当前密码</label>
        <Input disabled={busy} id="current-password" name="currentPassword" mode="password" value={currentPassword} onChange={setCurrentPassword} autoComplete="current-password" aria-describedby={error ? 'password-validation-error' : undefined} />
        <label htmlFor="new-password">新密码</label>
        <Input disabled={busy} id="new-password" name="newPassword" mode="password" value={newPassword} onChange={setNewPassword} autoComplete="new-password" aria-describedby={error ? 'password-validation-error' : undefined} />
        <label htmlFor="confirm-password">确认新密码</label>
        <Input disabled={busy} id="confirm-password" name="confirmation" mode="password" value={confirmation} onChange={setConfirmation} autoComplete="new-password" aria-describedby={error ? 'password-validation-error' : undefined} />
        <Button theme="solid" type="primary" htmlType="submit" loading={busy}>更新密码</Button>
      </form>
    </section>
  );
}
