/**
 * 官网的页面切换（不引入路由库，见 src/lib/route.ts）：当前页面放在 context 里，站内链接用 history.pushState，浏览器前进后退用 popstate。
 */
import { useCallback, useEffect, useMemo, useState, type MouseEvent, type ReactNode } from 'react';
import { pageFromPath, pathFor, type Page } from '../lib/route';
import { RouterContext, useRouter, type Target } from './routerContext';

const BASE = import.meta.env.BASE_URL || '/';

function scrollTo(hash?: string) {
  if (hash) {
    const el = document.getElementById(hash);
    if (el) {
      el.scrollIntoView();
      return;
    }
  }
  window.scrollTo(0, 0);
}

export function RouterProvider({ children }: { children: ReactNode }) {
  const [page, setPage] = useState<Page>(() => pageFromPath(window.location.pathname, BASE));
  useEffect(() => {
    const onPop = () => setPage(pageFromPath(window.location.pathname, BASE));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);
  const href = useCallback((to: Target, hash?: string) => pathFor(to, BASE) + (hash ? `#${hash}` : ''), []);
  const navigate = useCallback(
    (to: Target, hash?: string) => {
      const url = href(to, hash);
      if (url !== window.location.pathname + window.location.hash) window.history.pushState(null, '', url);
      setPage(to);
      // 等新页面渲染完再滚动
      requestAnimationFrame(() => scrollTo(hash));
    },
    [href],
  );
  const value = useMemo(() => ({ page, href, navigate }), [page, href, navigate]);
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

/** 站内链接：普通点击在页面内切换，按住 Ctrl/⌘ 或中键时交给浏览器（新标签页打开） */
export function Link({ to, hash, className, children, onClick, ...rest }: { to: Target; hash?: string; className?: string; children: ReactNode; onClick?: () => void; 'data-testid'?: string }) {
  const { href, navigate } = useRouter();
  const handle = (e: MouseEvent<HTMLAnchorElement>) => {
    onClick?.();
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    navigate(to, hash);
  };
  return (
    <a href={href(to, hash)} className={className} onClick={handle} {...rest}>
      {children}
    </a>
  );
}
