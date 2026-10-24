/**
 * 分页列表："加载更多"时保留已显示的数据，不整页闪成加载中。
 */
import { useCallback, useRef } from 'react';
import type { Page } from '../api/types';
import { useAsync } from './useAsync';

export function usePaged<T>(fetchPage: (page: number, pageSize: number) => Promise<Page<T>>, key: string, pageSize = 20) {
  const pages = useRef(1);
  const lastKey = useRef(key);
  if (lastKey.current !== key) {
    lastKey.current = key;
    pages.current = 1;
  }
  // 原型阶段一次取回前 N 页；正式接口可改为按页追加
  const s = useAsync(() => fetchPage(1, pages.current * pageSize), key);
  const { reload } = s;
  const loadMore = useCallback(() => {
    pages.current += 1;
    reload();
  }, [reload]);
  return {
    ...s,
    items: s.data?.items ?? [],
    total: s.data?.total ?? 0,
    hasMore: !!s.data && s.data.items.length < s.data.total,
    loadMore,
  };
}
