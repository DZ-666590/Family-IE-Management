import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { usePlugins } from '../extensions/registry';
import { moduleItems } from './navigation';
import { LedgerMark, NavigationIcon, type NavigationIconName } from './NavigationIcon';

export function ModuleLinks({ onSelect, mobile = false, collapsed = false }: { onSelect?: () => void; mobile?: boolean; collapsed?: boolean }) {
  const plugins = usePlugins();
  const [folded, setFolded] = useState<Record<string, boolean>>({});
  const groups = [
    { label: '日常账本', items: moduleItems.filter(item => ['transactions', 'budgets', 'recurring'].includes(item.key)) },
    { label: '资产与负债', items: moduleItems.filter(item => ['assets', 'investments', 'loans'].includes(item.key)) },
    { label: '分析与扩展', items: plugins.items.map(item => ({ key: item.id, label: item.name, path: item.path })) }
  ];
  function link(item: { key: string; label: string; path: string }) {
    const name = ['overview', 'transactions', 'budgets', 'recurring', 'assets', 'investments', 'loans', 'annual-stats'].includes(item.key) ? item.key as NavigationIconName : 'extension';
    return <NavLink key={item.key} to={item.path} onClick={onSelect} title={collapsed ? item.label : undefined} aria-label={item.label} className={({ isActive }) => `module-link${isActive ? ' is-active' : ''}`}><NavigationIcon name={name}/><span className="module-link-label">{item.label}</span></NavLink>;
  }
  return <nav className="module-nav grouped-nav" aria-label={mobile ? '移动模块导航' : '模块导航'}>
    {link({ key: 'overview', label: '家庭总览', path: '/workspace/overview' })}
    {groups.filter(group => group.items.length > 0).map(group => <section className="navigation-group" key={group.label}>
      {collapsed ? <div className="navigation-group-divider" /> : <button className="navigation-group-title" aria-expanded={!folded[group.label]} onClick={() => setFolded(value => ({ ...value, [group.label]: !value[group.label] }))}><span>{group.label}</span><span className={`group-chevron${folded[group.label] ? ' folded' : ''}`} aria-hidden="true">⌄</span></button>}
      {(collapsed || !folded[group.label]) && <div className="navigation-group-items">{group.items.map(link)}</div>}
    </section>)}
  </nav>;
}

export function ModuleSidebar({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  return <aside className={`module-sidebar unified-sidebar${collapsed ? ' is-collapsed' : ''}`} aria-label="工作区侧栏">
    <div className="sidebar-brand"><NavLink to="/workspace/overview" aria-label="家账首页"><LedgerMark/><span className="brand-wordmark">家账<span>家庭财务工作台</span></span></NavLink></div>
    <ModuleLinks collapsed={collapsed}/>
    <div className="sidebar-bottom"><button className="sidebar-collapse" onClick={onToggle} aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'} title={collapsed ? '展开侧边栏' : undefined}><NavigationIcon name="collapse"/><span>收起侧边栏</span></button></div>
  </aside>;
}
