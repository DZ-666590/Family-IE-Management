import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { IconSetting, IconUserGroup, IconPlus, IconExit } from '@douyinfe/semi-icons';
import type { Session } from '../api/contracts';
import { canManage, roleLabel } from './navigation';

export function ProfileMenu({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => { setOpen(false); }, [location]);
  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
  function close() { setOpen(false); trigger.current?.focus(); }
  function go(path: string) { close(); navigate(path); }
  function keys(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); close(); return; }
    const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    let next = index;
    if (event.key === 'ArrowDown') next = (index + 1) % items.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    else return;
    event.preventDefault(); items[next]?.focus();
  }
  return <div className="profile-menu" ref={root} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget as Node)) setOpen(false);
  }}>
    <button ref={trigger} type="button" className="profile-trigger" aria-label="个人中心" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => setOpen(value => !value)} onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setOpen(true); }
      }}><span className="user-avatar" aria-hidden="true">{session.displayName.slice(0, 1)}</span></button>
    {open && <div className="profile-popover">
      <div className="profile-identity"><strong>{session.displayName}</strong><span>{session.email}</span><small>{roleLabel(session.role)}</small></div>
      <div id={id} ref={menu} role="menu" aria-label="个人中心菜单" onKeyDown={keys}>
        <button role="menuitem" tabIndex={-1} onClick={() => go('/workspace/family')}><span aria-hidden="true"><IconUserGroup /></span><span>家庭与成员</span></button>
        {canManage(session.role) && <button role="menuitem" tabIndex={-1} onClick={() => go('/workspace/family?action=invite')}><span aria-hidden="true"><IconPlus /></span><span>邀请成员</span></button>}
        <button role="menuitem" tabIndex={-1} onClick={() => go('/workspace/settings')}><span aria-hidden="true"><IconSetting /></span><span>账号设置</span></button>
        <div role="separator" className="profile-separator" />
        <button role="menuitem" tabIndex={-1} className="profile-logout" onClick={() => { close(); onLogout(); }}><span aria-hidden="true"><IconExit /></span><span>退出登录</span></button>
      </div>
    </div>}
  </div>;
}
