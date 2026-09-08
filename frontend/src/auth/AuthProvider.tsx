import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { createApiClient } from '../api/client';
import type { ApiRequest, ApiRequestOptions } from '../api/client';
import type { ChangePasswordRequest, RegisterRequest, RegisterResponse, Session } from '../api/contracts';
import { refreshAfterWrite } from '../shared/write-refresh';
import { useDraftRegistry } from '../shared/draft-guard';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthContextValue {
  session: Session | null;
  status: AuthStatus;
  notice?: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  request: ApiRequest;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [notice, setNotice] = useState<string | null>(null);
  const generation = useRef(0);
  const drafts = useDraftRegistry();

  const client = useMemo(() => createApiClient({
    invalidatePendingWork: () => { generation.current += 1; queryClient.clear(); },
    onSessionExpired: message => {
      drafts?.clear();
      client.resetSessionScope();
      setSession(null);
      setStatus('anonymous');
      setNotice(message);
    }
  }), [queryClient, drafts]);

  useEffect(() => {
    const scope = generation.current;
    const controller = new AbortController();
    client.api<Session>('/api/session', { signal: controller.signal, handleUnauthorized: false })
      .then(value => {
        if (scope !== generation.current) return;
        setSession(value);
        setStatus('authenticated');
      })
      .catch(error => {
        if (scope !== generation.current) return;
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSession(null);
        setStatus('anonymous');
      });
    return () => controller.abort();
  }, [client]);

  const beginSessionTransition = useCallback(() => {
    generation.current += 1;
    client.resetSessionScope();
    queryClient.clear();
    setSession(null);
    setStatus('anonymous');
    return generation.current;
  }, [client, queryClient]);

  const assertCurrent = useCallback((scope: number) => {
    if (scope !== generation.current) throw new DOMException('Session changed', 'AbortError');
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const scope = beginSessionTransition();
    const body = new URLSearchParams({ username: email, password });
    const value = await client.api<Session>('/api/auth/login', { method: 'POST', body });
    assertCurrent(scope);
    client.invalidateCsrf();
    client.resetSessionExpiry();
    setNotice(null);
    setSession(value);
    setStatus('authenticated');
  }, [client, beginSessionTransition, assertCurrent]);

  const register = useCallback(async (request: RegisterRequest) => {
    const scope = beginSessionTransition();
    await client.api<RegisterResponse>('/api/auth/register', { method: 'POST', body: request });
    assertCurrent(scope);
    await login(request.email, request.password);
  }, [client, login, beginSessionTransition, assertCurrent]);

  const logout = useCallback(async () => {
    drafts?.clear();
    const scope = beginSessionTransition();
    try {
      await client.api<void>('/api/auth/logout', { method: 'POST' });
    } finally {
      if (scope === generation.current) {
        queryClient.clear();
        setSession(null);
        setStatus('anonymous');
        setNotice(null);
        client.invalidateCsrf();
      }
    }
  }, [client, queryClient, beginSessionTransition, drafts]);

  const request: ApiRequest = useMemo(() => Object.assign(async <T,>(path: string, options?: ApiRequestOptions): Promise<T> => {
    const scope = generation.current;
    const result = await client.api<T>(path, options);
    assertCurrent(scope);
    await refreshAfterWrite(queryClient, path, options, () => scope === generation.current);
    assertCurrent(scope);
    return result;
  }, { beginRecoverableOperation: client.beginRecoverableOperation }), [client, queryClient, assertCurrent]);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const scope = generation.current;
    const request: ChangePasswordRequest = { currentPassword, newPassword };
    await client.api<void>('/api/auth/change-password', { method: 'POST', body: request });
    assertCurrent(scope);
  }, [client, assertCurrent]);

  const value = useMemo<AuthContextValue>(() => ({
    session,
    status,
    notice,
    login,
    register,
    logout,
    changePassword,
    request
  }), [session, status, notice, login, register, logout, changePassword, request]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
