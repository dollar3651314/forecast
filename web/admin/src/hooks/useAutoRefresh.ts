/**
 * 自动刷新（v2 E7）：页面打开时每隔 intervalMs 调一次 refresh；可以暂停；页面不可见（切到别的标签页、最小化）时停止，
 * 回到页面时立即刷新一次再继续计时。真正的实时推送以后再做（需求说明书 v2.1 第 11.1 节 E7）。
 */
import { useEffect, useRef, useState } from 'react';

function pageVisible(): boolean {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden';
}

export interface AutoRefresh {
  paused: boolean;
  setPaused: (p: boolean) => void;
  /** 页面是否可见 */
  visible: boolean;
  /** 正在自动刷新：没暂停并且页面可见 */
  active: boolean;
  intervalMs: number;
}

export function useAutoRefresh(refresh: () => void, intervalMs = 5000): AutoRefresh {
  const [paused, setPaused] = useState(false);
  const [visible, setVisible] = useState(pageVisible);
  const ref = useRef(refresh);
  ref.current = refresh;

  useEffect(() => {
    const onChange = () => {
      const v = pageVisible();
      setVisible(v);
    };
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);

  // 从不可见回到可见（且没暂停）：立即刷新一次
  const wasVisible = useRef(visible);
  useEffect(() => {
    if (visible && !wasVisible.current && !paused) ref.current();
    wasVisible.current = visible;
  }, [visible, paused]);

  useEffect(() => {
    if (paused || !visible) return;
    const id = setInterval(() => ref.current(), intervalMs);
    return () => clearInterval(id);
  }, [paused, visible, intervalMs]);

  return { paused, setPaused, visible, active: !paused && visible, intervalMs };
}
