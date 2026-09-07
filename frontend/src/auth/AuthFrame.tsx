import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { LedgerMark } from '../layout/NavigationIcon';
import editorialPhoto from '../assets/editorial-home.jpg';

export function AuthFrame({ title, children, footer }: {
  title: string;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <main className="auth-editorial">
      <section className="auth-editorial__panel" aria-labelledby="auth-title">
        <header className="auth-editorial__brand">
          <Link to="/login" aria-label="家账"><LedgerMark/><span>家账</span></Link>
        </header>
        <div className="auth-editorial__content">
          <h1 id="auth-title">{title}</h1>
          {children}
          <footer className="auth-editorial__footer">{footer}</footer>
        </div>
      </section>
      <aside className="auth-editorial__portrait" aria-hidden="true">
        <picture>
          <source media="(min-width: 761px)" srcSet={editorialPhoto} type="image/jpeg"/>
          <img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" alt="" fetchPriority="high" decoding="async"/>
        </picture>
      </aside>
    </main>
  );
}

export function AuthDiagnostics({ requestId }: { requestId?: string }) {
  return requestId ? <details className="auth-diagnostics"><summary>问题详情</summary><p className="request-id">请求编号 {requestId}</p></details> : null;
}
