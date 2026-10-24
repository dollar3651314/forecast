import type { ReactNode } from 'react';
import { formatAmount, toScaled } from '../utils/money';

/** 等宽数字 */
export function Num({ children, className, strong }: { children: ReactNode; className?: string; strong?: boolean }) {
  return <span className={`num${className ? ' ' + className : ''}${strong ? ' strong' : ''}`}>{children}</span>;
}

/** 金额：千分位；signed=true 时正数绿色带 +，负数红色 */
export function Amount({ value, decimals = 2, signed, strong }: { value: string | null | undefined; decimals?: number; signed?: boolean; strong?: boolean }) {
  const scaled = value ? toScaled(value, decimals) : null;
  const cls = signed && scaled !== null ? (scaled > 0n ? 'up' : scaled < 0n ? 'down' : '') : '';
  return (
    <Num className={cls} strong={strong}>
      {formatAmount(value, { decimals, showPlus: signed })}
    </Num>
  );
}
