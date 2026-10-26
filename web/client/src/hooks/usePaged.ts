/**
 * 分页列表（C52：能看到全部记录）："加载更多"只取下一页、接在后面，已显示的不闪成加载中；加载更多失败时保留已显示的，单独给"重试"。
 * 重新加载（reload，例如收到推送）按已经显示的页数重新取，回到第一页时顺序以最新为准。同一条记录（idOf 相同）只显示一次
 * （翻页期间有新记录插到前面时，后一页会带出前一页的最后几条）。
 */
import { useCallback, useRef, useState } from 'react';
import type { Page } from '../api/types';
import { useAsync } from './useAsync';

/** 一页里一条记录的类型 */
type Item<P> = P extends Page<infer T> ? T : never;

/** P：接口返回的一页（可以带额外的字段，例如我的下注的 summary、totals，取第一页的） */
export function usePaged<P extends Page<Item<P>>>(fetchPage: (page: number, pageSize: number) => Promise<P>, key: string, pageSize = 20, idOf?: (t: Item<P>) => string) {
  type T = Item<P>;
  const pages = useRef(1);
  const lastKey = useRef(key);
  if (lastKey.current !== key) {
    lastKey.current = key;
    pages.current = 1;
  }
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const dedupe = useCallback(
    (items: T[]): T[] => {
      if (!idOf) return items;
      const seen = new Set<string>();
      return items.filter((x) => {
        const id = idOf(x);
        if (seen.has(id)) return false;
        seen.add(id);
        return true;
      });
    },
    [idOf],
  );
  const s = useAsync<P>(async () => {
    const n = pages.current;
    const first = await fetchRef.current(1, pageSize);
    let items = first.items;
    let total = first.total;
    for (let p = 2; p <= n && items.length < total; p++) {
      const r = await fetchRef.current(p, pageSize);
      items = items.concat(r.items);
      total = r.total;
    }
    return { ...first, items: dedupe(items), total };
  }, key);
  const [more, setMore] = useState<{ loading: boolean; error: unknown }>({ loading: false, error: undefined });
  const { setData } = s;
  const loadMore = useCallback(async () => {
    const next = pages.current + 1;
    setMore({ loading: true, error: undefined });
    try {
      const r = await fetchRef.current(next, pageSize);
      pages.current = next;
      setData((d) => (d ? { ...d, items: dedupe(d.items.concat(r.items)), total: r.total } : d));
      setMore({ loading: false, error: undefined });
    } catch (error) {
      setMore({ loading: false, error });
    }
  }, [pageSize, setData, dedupe]);
  return {
    ...s,
    items: s.data?.items ?? [],
    total: s.data?.total ?? 0,
    hasMore: !!s.data && s.data.items.length < s.data.total,
    loadMore,
    loadingMore: more.loading,
    moreError: more.error,
  };
}
