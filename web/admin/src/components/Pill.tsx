import type { ReactNode } from 'react';

export type PillTone = 'pend' | 'delay' | 'ok' | 'warn' | 'danger' | 'info' | 'muted' | 'sys' | 'admin';

/** 状态标签（效果图里的圆角小标签） */
export function Pill({ tone, children, title }: { tone: PillTone; children: ReactNode; title?: string }) {
  return (
    <span className={`pill pill-${tone}`} title={title}>
      {children}
    </span>
  );
}

/** 链名小标签 */
export function ChainTag({ chain }: { chain: string }) {
  return <span className="chain-tag">{chain}</span>;
}
