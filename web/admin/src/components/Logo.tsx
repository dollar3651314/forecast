import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { BRAND_NAME } from '../lib/brand';

/**
 * 文字 Logo：青色渐变圆角方块 + 三根柱子（占位图形，品牌定了以后换），后面是品牌名。
 * 和客户端、public/favicon.svg 是同一个图形；内联 SVG，不加载外部资源。品牌名不翻译。
 */
export function Logo({ compact }: { compact?: boolean }) {
  const { t } = useTranslation();
  const id = `logo-g-${useId().replace(/:/g, '')}`;
  return (
    <div className="logo">
      <svg className="logo-mark" width="24" height="24" viewBox="0 0 512 512" aria-hidden>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#22d3ee" />
            <stop offset="1" stopColor="#0891b2" />
          </linearGradient>
        </defs>
        <rect width="512" height="512" rx="112" fill={`url(#${id})`} />
        <rect x="136" y="236" width="64" height="140" rx="20" fill="#04121a" />
        <rect x="224" y="156" width="64" height="220" rx="20" fill="#04121a" />
        <rect x="312" y="196" width="64" height="180" rx="20" fill="#04121a" />
      </svg>
      <span>{BRAND_NAME}</span>
      {!compact ? <small>{t('app.adminConsole')}</small> : null}
    </div>
  );
}
