import { NavigationIcon } from './NavigationIcon';
import type { Session } from '../api/contracts';
import { ProfileMenu } from './ProfileMenu';
import { useNavigate } from 'react-router-dom';

export function WorkspaceHeader({
  session,
  mobileTriggerRef,
  onOpenMobile,
  onLogout
}: {
  session: Session;
  mobileTriggerRef: React.RefObject<HTMLButtonElement | null>;
  onOpenMobile: () => void;
  onLogout: () => void;
}) {
  const navigate = useNavigate();
  return (
    <header className="workspace-header">
      <div className="header-leading">
        <button ref={mobileTriggerRef} type="button" className="icon-button mobile-menu-trigger" aria-label="打开模块导航" onClick={onOpenMobile}>
          <NavigationIcon name="menu" />
        </button>
        <span className="workspace-crumb">家账 / 我的家庭</span>
      </div>
      <div className="header-actions">
        <button type="button" className="icon-button notification-button" aria-label="查看提醒" onClick={() => navigate('/workspace/notifications')}><NavigationIcon name="bell" /></button>
        <ProfileMenu session={session} onLogout={onLogout} />
      </div>
    </header>
  );
}
