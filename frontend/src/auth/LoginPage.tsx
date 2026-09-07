import { useState, type FormEvent } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Input from '@douyinfe/semi-ui/lib/es/input';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import { AuthDiagnostics, AuthFrame } from './AuthFrame';
import { errorMessage, focusField } from './form-utils';

export function LoginPage() {
  const { login, notice } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [requestId, setRequestId] = useState<string>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const normalizedEmail = email.trim().toLowerCase();
    if (!normalizedEmail) {
      setError('请输入邮箱');
      focusField('login-email');
      return;
    }
    if (!password) {
      setError('请输入密码');
      focusField('login-password');
      return;
    }
    setBusy(true);
    setError(null);
    setRequestId(undefined);
    try {
      await login(normalizedEmail, password);
      navigate('/workspace/overview', { replace: true });
    } catch (cause) {
      const failure = errorMessage(cause);
      setError(failure.message);
      setRequestId(failure.requestId);
      focusField('login-email');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      title="登录家账"
      footer={<>还没有账号？ <Link to="/register">注册</Link></>}
    >
      {(error || notice) && <div className="form-alert" role="alert">{error || notice}</div>}
      <form className="auth-form" onSubmit={submit} noValidate>
        <label htmlFor="login-email">邮箱</label>
        <Input id="login-email" value={email} onChange={setEmail} autoComplete="username" inputMode="email" placeholder="name@example.com" />
        <label htmlFor="login-password">密码</label>
        <Input id="login-password" mode="password" value={password} onChange={setPassword} autoComplete="current-password" placeholder="请输入密码" />
        <AuthDiagnostics requestId={requestId}/>
        <Button theme="solid" type="primary" htmlType="submit" loading={busy} block>登录</Button>
      </form>
    </AuthFrame>
  );
}
