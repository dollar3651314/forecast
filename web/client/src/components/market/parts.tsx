/** 市场页面的小部件：状态标签、剩余时间、UTC + 本地时间、概率条。手机和电脑共用。 */
import { useTranslation } from 'react-i18next';
import type { MarketCategory, MarketStatus } from '../../api/types';
import { useRemainText } from '../../hooks/useLang';
import { barWidth, localIsUtc, localText, statusTone, utcText } from '../../lib/market';
import { Icon, type IconName } from '../Icon';

export function StatusTag({ status }: { status: MarketStatus }) {
  const { t } = useTranslation();
  return <span className={`mk-status s-${statusTone(status)}`}>{t(`mk.status.${status}`)}</span>;
}

const CAT_ICON: Record<MarketCategory, IconName> = { FOOTBALL: 'ball', NBA: 'hoop', CRYPTO_15M: 'coin', CRYPTO_1H: 'coin', MACRO: 'bank' };

export function CategoryIcon({ category, size = 16 }: { category: MarketCategory; size?: number }) {
  return (
    <span className={`mk-cat-ic c-${category.toLowerCase()}`} aria-hidden="true">
      <Icon name={CAT_ICON[category]} size={size} strokeWidth={2} />
    </span>
  );
}

/** 截止时间：一天以内显示"还剩 xx"，更远显示"截止 11-01 14:55 UTC"；还没开放的显示"11-02 10:00 UTC 开放"（S11） */
export function CloseLabel({ closeAt, openAt, now, status }: { closeAt: string; openAt?: string; now: number; status: MarketStatus }) {
  const { t } = useTranslation();
  const left = useRemainText(closeAt, now);
  const ms = Date.parse(closeAt) - now;
  if (status !== 'listed' && status !== 'paused') return null;
  if (openAt && Date.parse(openAt) > now) {
    return (
      <span className="mk-close upcoming">
        <Icon name="clock" size={13} strokeWidth={2.2} />
        {t('mk.opensAt', { t: utcText(openAt) })}
      </span>
    );
  }
  if (left && ms < 24 * 3_600_000) {
    return (
      <span className={`mk-close${ms < 3_600_000 ? ' soon' : ''}`}>
        <Icon name="clock" size={13} strokeWidth={2.2} />
        {left}
      </span>
    );
  }
  return <span className="mk-close">{t('mk.closesAt', { t: utcText(closeAt) })}</span>;
}

/** "11-01 14:55 UTC（你的时间 22:55）"（需求第 3.7 节） */
export function TimePair({ iso }: { iso: string }) {
  const { t } = useTranslation();
  return (
    <span className="time-pair">
      <span className="num">{utcText(iso)}</span>
      {!localIsUtc() && <span className="muted">（{t('md.yourTime', { t: localText(iso) })}）</span>}
    </span>
  );
}

/** 概率条：底色 --track，填充按选项（选中 / 赢 / 涨跌）上色；数字更新时不做颜色过渡（C84） */
export function ProbFill({ pct, tone }: { pct: string; tone?: 'up' | 'down' | 'win' | 'sel' | 'base' }) {
  return <span className={`prob-fill t-${tone ?? 'base'}`} style={{ width: `${barWidth(pct)}%` }} aria-hidden="true" />;
}
