import { useEffect } from 'react';

let locks = 0;

/** 弹窗打开时锁定背景滚动（原型规范：弹窗打开时背景不能滚动） */
export function useBodyLock(active: boolean): void {
  useEffect(() => {
    if (!active) return;
    locks += 1;
    document.body.classList.add('scroll-locked');
    return () => {
      locks -= 1;
      if (locks === 0) document.body.classList.remove('scroll-locked');
    };
  }, [active]);
}
