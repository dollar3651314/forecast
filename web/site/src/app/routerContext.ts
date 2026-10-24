/** 官网页面切换的 context（Provider 和 Link 在 router.tsx） */
import { createContext, useContext } from 'react';
import type { Page } from '../lib/route';

export type Target = Exclude<Page, 'notFound'>;

export interface Router {
  page: Page;
  href: (to: Target, hash?: string) => string;
  navigate: (to: Target, hash?: string) => void;
}

export const RouterContext = createContext<Router | null>(null);

export function useRouter(): Router {
  const v = useContext(RouterContext);
  if (!v) throw new Error('useRouter 必须在 RouterProvider 里使用');
  return v;
}
