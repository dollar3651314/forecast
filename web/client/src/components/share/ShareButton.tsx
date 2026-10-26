/**
 * "分享"按钮（S14，MF-5）：点了打开分享弹窗（ShareDialog），卡片在打开时才去取。
 * 市场详情（市场卡 / 已结束的是开奖卡）、周报（周报卡）、我的下注里押中的已结算下注（押中的开奖卡）用它。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../Icon';
import { ShareDialog, type ShareDialogProps } from './ShareDialog';

export function ShareButton({ className, compact, testId = 'share-open', ...dialog }: Omit<ShareDialogProps, 'onClose'> & { className?: string; compact?: boolean; testId?: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className={`btn btn-ghost btn-sm share-btn${compact ? ' compact' : ''}${className ? ` ${className}` : ''}`}
        onClick={(e) => {
          // 放在整块可点的卡片（链接）里时不触发外面的跳转
          e.preventDefault();
          e.stopPropagation();
          setOpen(true);
        }}
        aria-haspopup="dialog"
        data-testid={testId}
      >
        <Icon name="share" size={16} />
        <span>{t('share.button')}</span>
      </button>
      {open && <ShareDialog {...dialog} onClose={() => setOpen(false)} />}
    </>
  );
}
