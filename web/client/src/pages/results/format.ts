/** 开奖记录、周报的文字（S13）：页面组件以外的纯函数放这里（组件文件只导出组件，热更新要求） */
import type { TFunction } from 'i18next';
import type { Lang, ResultItem, WeekReport } from '../../api/types';
import { pickText } from '../../lib/market';

/** 结果：选项名；作废、取消写原因 */
export function resultLine(r: ResultItem, t: TFunction, lang: Lang): string {
  if (r.status === 'settled') return pickText(r.options.find((o) => o.optionNo === r.winningOptionNo)?.label, lang) || '—';
  const reason = r.status === 'voided' ? r.voidReason : r.cancelReason;
  const text = reason ? t(`md.res.reasons.${reason}`, { defaultValue: reason }) : '';
  return text ? `${t(`mk.status.${r.status}`)} · ${text}` : t(`mk.status.${r.status}`);
}

/** "10-26 – 11-01"（UTC 日期） */
export function weekRange(w: WeekReport): string {
  const f = new Date(w.from);
  const l = new Date(Date.parse(w.to) - 86_400_000);
  const md = (d: Date) => `${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
  return `${md(f)} – ${md(l)}`;
}

