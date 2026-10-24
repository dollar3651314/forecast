/** 线性图标（内联 SVG，不加载外部资源）。路径取自效果图。 */
import { useId } from 'react';
import { BRAND_NAME } from '../lib/brand';

const PATHS = {
  home: 'M3 11 12 4l9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  markets: 'M4 19V9M10 19V5M16 19v-7M22 19H2',
  trade: 'M7 4v16M7 8h-2v8h2M17 4v16M17 6h-2v6h4V6h-2',
  positions: 'M3 7h18v13H3zM8 7V4h8v3M3 12h18',
  swap: 'M7 7h13l-4-4M17 17H4l4 4',
  star: 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z',
  assets: 'M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 10h18M16 15h2',
  me: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c1-4 4.5-6 8-6s7 2 8 6',
  chevronDown: 'm6 9 6 6 6-6',
  chevronRight: 'm9 6 6 6-6 6',
  back: 'm15 5-7 7 7 7',
  close: 'M6 6l12 12M18 6 6 18',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  deposit: 'M12 4v12m-5-5 5 5 5-5M5 20h14',
  withdraw: 'M12 20V8m-5 5 5-5 5 5M5 4h14',
  list: 'M5 6h14M5 12h14M5 18h9',
  warn: 'M12 3 2 20h20L12 3zM12 10v4M12 17v.5',
  check: 'm5 12 5 5 9-10',
  checkCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8 12l3 3 5-6',
  bell: 'M6 16V11a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  copy: 'M9 9h10v12H9zM5 15V3h10',
  shield: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c1-4 4.5-6 8-6s7 2 8 6',
  lock: 'M6 11h12v10H6zM8 11V7a4 4 0 0 1 8 0v4',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18',
  moon: 'M20 15A8 8 0 1 1 9 4a7 7 0 0 0 11 11z',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10',
  plus: 'M12 5v14M5 12h14',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  phone: 'M8 2h8a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM11 18h2',
  upload: 'M12 16V4m-5 5 5-5 5 5M5 20h14',
  indicator: 'M3 17l6-6 4 4 8-8',
  pause: 'M9 5v14M15 5v14',
  flask: 'M9 3h6M10 3v6L4 19a1 1 0 0 0 1 2h14a1 1 0 0 0 1-2l-6-10V3',
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 20, strokeWidth = 1.9, className }: { name: IconName; size?: number; strokeWidth?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={PATHS[name]} />
    </svg>
  );
}

/**
 * Logo 的图形部分（占位）：复制源的渐变圆角方块保留，里面的旧品牌字母换成三根柱子（奖池），品牌和主色定了以后由 PM 给 Logo（AQ-5、AQ-6）。
 * 和 public/favicon.svg、PWA 图标是同一个图形；品牌名由调用方写在后面（见 Brand）。
 */
export function Logo({ size = 28 }: { size?: number }) {
  const id = `logo-g-${useId().replace(/:/g, '')}`;
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 512 512" aria-hidden="true" focusable="false">
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
  );
}

/** 品牌：图形 + 品牌名（lib/brand.ts，品牌名不翻译） */
export function Brand() {
  return (
    <>
      <Logo />
      <span className="brand-word">{BRAND_NAME}</span>
    </>
  );
}
