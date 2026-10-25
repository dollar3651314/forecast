/**
 * 分享卡片的弹窗（S14，MF-5；需求第 5.2 节"分享卡片和挑战卡"、AC-86、AC-87；线框图 W5）。
 * 卡片图片由服务器生成（1200 × 630，和 Telegram、X 链接预览里显示的是同一张），这里只负责预览和分享入口：
 * - 手机：主按钮"分享"调系统的分享面板（`navigator.share`，带标题、卡片文字、链接）；浏览器不支持时退回"复制链接"；
 * - 电脑：主按钮"复制链接"，另有"下载图片"和发到 Telegram、X 的分享页（打开对方的发帖页，由客户自己发，系统不代发）。
 * 押中的开奖卡（win）有"在卡片上显示我的派彩金额"，默认不勾（金额默认隐藏，AC-86）；勾上以后重新生成一张带金额的卡片。
 * 主操作在弹窗底部（C79），图片加载中显示占位、出错时说明原因（链接照样能分享，C48）。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ShareCard } from '../../api/types';
import { useToast } from '../../app/contexts';
import { useAsync } from '../../hooks/useAsync';
import { useIsDesktop } from '../../hooks/useMediaQuery';
import { ErrorState } from '../AsyncView';
import { Icon } from '../Icon';
import { Modal } from '../Modal';

export interface ShareDialogProps {
  title: string;
  /** 取卡片（showAmount 只对押中的开奖卡有意义） */
  load: (opts: { showAmount: boolean }) => Promise<ShareCard>;
  /** 押中的开奖卡：显示"在卡片上显示我的派彩金额"（默认不勾） */
  amountToggle?: boolean;
  /** 弹窗缓存用的标识（同一个标识、同一个勾选只取一次） */
  cacheKey: string;
  onClose: () => void;
}

/** 系统分享面板（手机）：没有这个功能的浏览器返回 false */
function canNativeShare(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.share === 'function';
}

async function copyText(s: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(s);
      return true;
    }
  } catch {
    // 退回下面的老办法
  }
  try {
    const el = document.createElement('textarea');
    el.value = s;
    el.setAttribute('readonly', '');
    el.style.position = 'fixed';
    el.style.opacity = '0';
    document.body.appendChild(el);
    el.select();
    const ok = document.execCommand('copy');
    el.remove();
    return ok;
  } catch {
    return false;
  }
}

/** 第一行（标题那一句）给 Telegram、X 的分享页当正文；链接单独带 */
function firstLine(text: string): string {
  return text.split('\n')[0] ?? '';
}

export function ShareDialog({ title, load, amountToggle, cacheKey, onClose }: ShareDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const desktop = useIsDesktop();
  const [showAmount, setShowAmount] = useState(false);
  const s = useAsync(() => load({ showAmount }), `share|${cacheKey}|${showAmount ? 1 : 0}`);
  const card = s.data;
  const [img, setImg] = useState<'loading' | 'ok' | 'error'>('loading');
  const [loadedFor, setLoadedFor] = useState<string | undefined>(undefined);
  if (card && loadedFor !== card.imageUrl) {
    setLoadedFor(card.imageUrl);
    setImg('loading');
  }
  const native = !desktop && canNativeShare();

  const copy = async () => {
    if (!card) return;
    const ok = await copyText(card.url);
    toast.show(ok ? { tone: 'success', title: t('share.copied') } : { tone: 'error', title: t('share.copyFail') });
  };

  const shareNative = async () => {
    if (!card) return;
    try {
      await navigator.share({ title: card.title, text: card.text, url: card.url });
    } catch (e) {
      // 客户自己取消（AbortError）不提示；其他错误退回复制链接
      if (!(e instanceof DOMException && e.name === 'AbortError')) await copy();
    }
  };

  const download = async () => {
    if (!card) return;
    try {
      const res = await fetch(card.imageUrl);
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      const href = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = href;
      a.download = `card-${card.kind}.png`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(href), 1000);
    } catch {
      // 下载不了时在新标签页打开图片，客户自己另存
      window.open(card.imageUrl, '_blank', 'noopener');
    }
  };

  const tg = card ? `https://t.me/share/url?url=${encodeURIComponent(card.url)}&text=${encodeURIComponent(firstLine(card.text))}` : '#';
  const x = card ? `https://x.com/intent/post?text=${encodeURIComponent(firstLine(card.text))}&url=${encodeURIComponent(card.url)}` : '#';

  const footer = (
    <div className={`share-actions${desktop ? ' is-d' : ''}`}>
      {native ? (
        <>
          <button type="button" className="btn btn-lg btn-block btn-primary" disabled={!card} onClick={() => void shareNative()} data-testid="share-native" data-autofocus>
            <Icon name="share" size={18} />
            {t('share.native')}
          </button>
          <button type="button" className="btn btn-block btn-ghost" disabled={!card} onClick={() => void copy()} data-testid="share-copy">
            <Icon name="copy" size={16} />
            {t('share.copy')}
          </button>
        </>
      ) : (
        <>
          <button type="button" className="btn btn-lg btn-primary" disabled={!card} onClick={() => void copy()} data-testid="share-copy" data-autofocus>
            <Icon name="copy" size={18} />
            {t('share.copy')}
          </button>
          <button type="button" className="btn btn-lg btn-outline" disabled={!card} onClick={() => void download()} data-testid="share-download">
            <Icon name="deposit" size={18} />
            {t('share.download')}
          </button>
          {desktop && (
            <span className="share-links">
              <a className="btn btn-ghost btn-sm" href={tg} target="_blank" rel="noopener noreferrer" aria-disabled={!card} data-testid="share-tg">
                {t('share.telegram')}
              </a>
              <a className="btn btn-ghost btn-sm" href={x} target="_blank" rel="noopener noreferrer" aria-disabled={!card} data-testid="share-x">
                {t('share.x')}
              </a>
            </span>
          )}
        </>
      )}
    </div>
  );

  return (
    <Modal open title={title} onClose={onClose} footer={footer} size="lg" variant="sheet">
      <div className="share-body" data-testid="share-dialog">
        {amountToggle && (
          <label className="share-amount">
            <input type="checkbox" checked={showAmount} onChange={(e) => setShowAmount(e.target.checked)} data-testid="share-amount" />
            <span>{t('share.showAmount')}</span>
          </label>
        )}
        {s.error && !card ? (
          <ErrorState error={s.error} onRetry={s.reload} compact />
        ) : (
          <div className={`share-frame${img === 'ok' ? ' ok' : ''}`} aria-busy={!card || img === 'loading'}>
            {card && (
              <img
                key={card.imageUrl}
                className="share-img"
                src={card.imageUrl}
                alt={card.title}
                width={1200}
                height={630}
                onLoad={() => setImg('ok')}
                onError={() => setImg('error')}
                data-testid="share-img"
              />
            )}
            {(!card || img === 'loading') && <div className="share-skeleton" aria-hidden="true" />}
            {img === 'error' && <p className="share-img-err">{t('share.imageError')}</p>}
          </div>
        )}
        {card && (
          <div className="share-link">
            <input readOnly value={card.url} aria-label={t('share.link')} onFocus={(e) => e.currentTarget.select()} data-testid="share-url" />
          </div>
        )}
        {card && card.url.includes('ref=') && <p className="share-note muted">{t('share.refNote')}</p>}
      </div>
    </Modal>
  );
}
