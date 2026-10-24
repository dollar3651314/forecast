import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../components/Icon';
import { ToastContext, type ToastItem } from './contexts';

/** 页面顶部的提示条：成交、强平、止盈止损、出金状态等都通过它展示 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(1);
  const dismiss = useCallback((id: number) => setItems((xs) => xs.filter((x) => x.id !== id)), []);
  const show = useCallback(
    (item: Omit<ToastItem, 'id'>) => {
      const id = seq.current++;
      setItems((xs) => [...xs.slice(-3), { ...item, id }]);
      setTimeout(() => dismiss(id), item.tone === 'error' ? 6000 : 4500);
    },
    [dismiss],
  );
  const value = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map((x) => (
          <div key={x.id} className={`toast toast-${x.tone}`}>
            <div className="toast-text">
              <b>{x.title}</b>
              {x.body && <span>{x.body}</span>}
            </div>
            <button type="button" className="toast-x" onClick={() => dismiss(x.id)} aria-label={t('common.close')}>
              <Icon name="close" size={12} strokeWidth={2.6} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
