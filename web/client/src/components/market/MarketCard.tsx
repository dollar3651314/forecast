/**
 * 市场卡片（需求 P1）：品类和联赛、截止时间、题目、选项和概率、奖池。手机单列、电脑网格都用这一个组件，排列由页面的网格决定（C64）。
 * - 二选一：直接显示两个按钮（概率为主，扣费后的预计倍数为辅，RQ-3）；加密涨跌用绿红，其他二选一用中性色（主色只表示"选中"和概率，方向 A）；
 * - 多选项：显示前 3 个（概率条 + 概率 + 倍数），多出的写"还有 n 个选项"；
 * - 已结算：标出结果；点选项按钮直接进详情并选中这个选项（手机弹出下注抽屉，电脑选中右栏）。
 * 赔率更新只换数字，没有闪色和跳动动画（C84）。
 */
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import type { MarketCard as Card } from '../../api/types';
import { useLang } from '../../hooks/useLang';
import { formatAmount } from '../../lib/format';
import { multText, pickText } from '../../lib/market';
import { Icon } from '../Icon';
import { CategoryIcon, CloseLabel, ProbFill, StatusTag } from './parts';

const isUpDown = (c: Card) => c.category === 'CRYPTO_15M' || c.category === 'CRYPTO_1H';

export function MarketCard({ m, now }: { m: Card; now: number }) {
  const { t } = useTranslation();
  const lang = useLang();
  const navigate = useNavigate();
  const href = `/markets/${m.publicNo}`;
  const open = m.status === 'listed';
  const pools = new Map(m.pool.options.map((o) => [o.optionNo, o]));
  const winner = m.winningOptionNo;
  const go = (optionNo?: string) => navigate(optionNo && open ? `${href}?opt=${optionNo}` : href);
  const binary = m.options.length === 2;
  const shown = binary ? m.options : m.options.slice(0, 3);

  return (
    <article className={`mk-card${open ? '' : ' is-closed'}`} data-testid="market-card">
      <div className="mk-meta">
        <span className="mk-league">
          <CategoryIcon category={m.category} size={14} />
          <span>{pickText(m.league, lang) || t(`mk.cats.${m.category}`)}</span>
        </span>
        {open ? <CloseLabel closeAt={m.closeAt} now={now} status={m.status} /> : <StatusTag status={m.status} />}
      </div>
      <h3 className="mk-title">
        <Link to={href}>{pickText(m.title, lang)}</Link>
      </h3>

      {binary ? (
        <div className="mk-bin">
          {shown.map((o, i) => {
            const p = pools.get(o.optionNo)!;
            const tone = isUpDown(m) ? (i === 0 ? 'up' : 'down') : 'neutral';
            const won = winner === o.optionNo;
            return (
              <button
                key={o.optionNo}
                type="button"
                className={`bin-btn ${tone}${won ? ' won' : ''}${winner && !won ? ' lost' : ''}`}
                onClick={() => go(o.optionNo)}
                aria-label={t('mk.probAria', { opt: pickText(o.label, lang), pct: p.pctList, mult: multText(p.mult) })}
              >
                <span className="bin-label">
                  {won && <Icon name="trophy" size={13} strokeWidth={2.2} />}
                  {pickText(o.label, lang)}
                </span>
                <span className="bin-nums">
                  <b className="num">{p.pctList}</b>
                  {p.mult !== '—' && <small className="num">{multText(p.mult)}</small>}
                </span>
              </button>
            );
          })}
        </div>
      ) : (
        <ul className="mk-opts">
          {shown.map((o) => {
            const p = pools.get(o.optionNo)!;
            const won = winner === o.optionNo;
            return (
              <li key={o.optionNo}>
                <button type="button" className={`opt-row${won ? ' won' : ''}`} onClick={() => go(o.optionNo)} aria-label={t('mk.probAria', { opt: pickText(o.label, lang), pct: p.pctList, mult: multText(p.mult) })}>
                  <span className="opt-bar">
                    <ProbFill pct={p.pct} tone={won ? 'win' : 'base'} />
                    <span className="opt-label">
                      {won && <Icon name="trophy" size={13} strokeWidth={2.2} />}
                      {pickText(o.label, lang)}
                    </span>
                    <b className="opt-pct num">{p.pctList}</b>
                  </span>
                  <span className="opt-mult num">{multText(p.mult)}</span>
                </button>
              </li>
            );
          })}
          {m.options.length > 3 && <li className="mk-more">{t('mk.more', { n: m.options.length - 3 })}</li>}
        </ul>
      )}

      <div className="mk-foot">
        <span>
          {t('mk.poolLine', { amount: formatAmount(m.pool.total, { dp: 0 }) })}
          <span className="dot">·</span>
          {t('mk.people', { n: m.pool.bettors })}
        </span>
        {m.pool.total === '0.00' && open ? <span className="mk-first">{t('mk.firstBet')}</span> : null}
      </div>
    </article>
  );
}
