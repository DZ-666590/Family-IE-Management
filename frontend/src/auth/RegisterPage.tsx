import { useEffect, useRef, useState, type FormEvent } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Input from '@douyinfe/semi-ui/lib/es/input';
import Radio from '@douyinfe/semi-ui/lib/es/radio';
import RadioGroup from '@douyinfe/semi-ui/lib/es/radio/radioGroup';
import { Link, useNavigate } from 'react-router-dom';
import type { RegisterRequest } from '../api/contracts';
import { useAuth } from './AuthProvider';
import { AuthDiagnostics, AuthFrame } from './AuthFrame';
import { errorMessage, focusField } from './form-utils';
import { useDraftProtection } from '../shared/draft-guard';

type Mode = RegisterRequest['mode'];
type FormErrors = Record<string, string>;

function FieldError({ id, children }: { id: string; children?: string }) {
  return children ? <p className="field-error" id={id} role="alert">{children}</p> : null;
}

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('CREATE');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [householdName, setHouseholdName] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [requestId, setRequestId] = useState<string>();
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const field = Object.keys(errors)[0];
    if (!busy && field) focusField(field);
  }, [busy, errors]);
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; }, []);
  const protection = useDraftProtection({ draft: { mode, email, displayName, password, householdName, inviteToken }, busy,
    onDiscard: () => { generation.current += 1; setBusy(false); setMode('CREATE'); setEmail(''); setDisplayName(''); setPassword(''); setHouseholdName(''); setInviteToken(''); setErrors({}); setGeneralError(null); setRequestId(undefined); }
  });

  function validate(): FormErrors {
    const next: FormErrors = {};
    if (!email.trim()) next.email = '请输入邮箱';
    if (!displayName.trim() || displayName.trim().length > 40) next.displayName = '姓名需为 1–40 个字符';
    if (password.length < 8 || password.length > 72) next.password = '密码需为 8–72 个字符';
    if (mode === 'CREATE' && !householdName.trim()) next.householdName = '请输入家庭名称';
    if (mode === 'JOIN' && !inviteToken.trim()) next.inviteToken = '请输入邀请码';
    return next;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const scope = generation.current;
    const nextErrors = validate();
    if (Object.keys(nextErrors).length) {
      setErrors(nextErrors);
      focusField(Object.keys(nextErrors)[0]!);
      return;
    }
    const request: RegisterRequest = {
      email: email.trim().toLowerCase(),
      displayName: displayName.trim(),
      password,
      mode,
      householdName: mode === 'CREATE' ? householdName.trim() : null,
      inviteToken: mode === 'JOIN' ? inviteToken.trim() : null
    };
    setBusy(true);
    setErrors({});
    setGeneralError(null);
    setRequestId(undefined);
    try {
      await register(request);
      if (scope !== generation.current) return;
      protection.release();
      navigate('/workspace/overview', { replace: true });
    } catch (cause) {
      if (scope !== generation.current) return;
      const failure = errorMessage(cause);
      setErrors(failure.fields ?? {});
      setGeneralError(failure.message);
      setRequestId(failure.requestId);
      const firstField = Object.keys(failure.fields ?? {})[0];
      if (firstField) focusField(firstField);
    } finally {
      if (scope === generation.current) setBusy(false);
    }
  }

  return (
    <AuthFrame
      title={mode === 'CREATE' ? '创建家庭空间' : '加入家庭空间'}
      footer={<>已有账号？ <Link to="/login">返回登录</Link></>}
    >
      <RadioGroup
        disabled={busy}
        className="registration-mode"
        value={mode}
        onChange={event => {
          setMode(event.target.value as Mode);
          setErrors({});
          setGeneralError(null);
          setRequestId(undefined);
        }}
        aria-label="注册方式"
      >
        <Radio value="CREATE">创建新家庭</Radio>
        <Radio value="JOIN">通过邀请码加入</Radio>
      </RadioGroup>
      {generalError && <div className="form-alert" role="alert">{generalError}</div>}
      <form className="auth-form" onSubmit={submit} noValidate>
        <label htmlFor="email">邮箱</label>
        <Input disabled={busy} id="email" value={email} onChange={setEmail} autoComplete="email" inputMode="email" aria-describedby={errors.email ? 'email-error' : undefined} placeholder="name@example.com" />
        <FieldError id="email-error">{errors.email}</FieldError>

        <label htmlFor="displayName">姓名</label>
        <Input disabled={busy} id="displayName" value={displayName} onChange={setDisplayName} autoComplete="name" aria-describedby={errors.displayName ? 'displayName-error' : undefined} placeholder="家庭成员如何称呼你" maxLength={40} />
        <FieldError id="displayName-error">{errors.displayName}</FieldError>

        <label htmlFor="password">密码</label>
        <Input disabled={busy} id="password" mode="password" value={password} onChange={setPassword} autoComplete="new-password" aria-describedby={errors.password ? 'password-help password-error' : 'password-help'} placeholder="设置登录密码" />
        <p className="field-help" id="password-help">8–72 个字符</p>
        <FieldError id="password-error">{errors.password}</FieldError>

        {mode === 'CREATE' ? (
          <>
            <label htmlFor="householdName">家庭名称</label>
            <Input disabled={busy} id="householdName" value={householdName} onChange={setHouseholdName} aria-describedby={errors.householdName ? 'householdName-error' : undefined} placeholder="例如：凯文之家" />
            <FieldError id="householdName-error">{errors.householdName}</FieldError>
          </>
        ) : (
          <>
            <label htmlFor="inviteToken">邀请码</label>
            <Input disabled={busy} id="inviteToken" value={inviteToken} onChange={setInviteToken} aria-describedby={errors.inviteToken ? 'inviteToken-error' : undefined} placeholder="粘贴家庭管理员发来的邀请码" />
            <FieldError id="inviteToken-error">{errors.inviteToken}</FieldError>
          </>
        )}
        <AuthDiagnostics requestId={requestId}/>
        <Button theme="solid" type="primary" htmlType="submit" loading={busy} block>
          {mode === 'CREATE' ? '创建家庭' : '加入家庭'}
        </Button>
      </form>
    </AuthFrame>
  );
}
