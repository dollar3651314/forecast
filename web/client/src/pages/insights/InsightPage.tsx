/**
 * 一个 SEO 页面 /insights/:slug（forecast S15，MF-11）：
 * - 焦点战：市场的实时奖池（和市场详情同一来源）+ 审核发布过的说明和赛前数据短文；
 * - BTC 每小时涨跌统计：过去 24 小时 / 7 天收涨的小时数、按 UTC 小时的比例，写明数据来源、取数时间和校验值；
 * - 文章：审核发布过的正文；这种语言还没有时显示另一种语言并说明。
 * 只陈述数据，不暗示结果（EQ-8）。不登录可看；页面不存在或被隐藏时显示"找不到"。
 */
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { getInsight } from '../../api/public';
import type { BtcStats, InsightMarket, Lang } from '../../api/types';
import { ErrorState, Loading } from '../../components/AsyncView';
import { GuestGate } from '../../components/GuestGate';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { useTitle } from '../../hooks/useTitle';
import { barWidth, pickText, utcText } from '../../lib/market';

export default function InsightPage() {
  const { t } = useTranslation();
  const lang = useLang();
  const { slug = '' } = useParams();
  const s = useAsync(() => getInsight(slug, lang), `insight|${slug}|${lang}`);
  useTitle(s.data?.title ?? t('ins.title'));
  return (
    <div className="page page-insight">
      <PageHeader title={s.data?.title ?? t('ins.title')} back="/insights" />
      <GuestGate error={s.error} />
      {s.error && !s.data ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !s.data ? (
        <section className="card">
          <Loading rows={6} />
        </section>
      ) : (
        <article className="ins-article" data-testid={`insight-${s.data.kind}`}>
          {s.data.fallbackLang && <p className="ins-note muted">{t('ins.fallback')}</p>}
          {s.data.intro && <p className="ins-intro">{s.data.intro}</p>}
          {s.data.market && <FocusMarket m={s.data.market} lang={lang} />}
          {s.data.preview && (
            <section className="card ins-sec">
              <h2>{s.data.preview.title}</h2>
              <Paragraphs text={s.data.preview.body} />
              <p className="muted small num">{t('ins.published', { at: utcText(s.data.preview.publishedAt, true) })}</p>
            </section>
          )}
          {s.data.btc && <Btc b={s.data.btc} />}
          {s.data.body && (
            <section className="card ins-sec ins-body">
              <Paragraphs text={s.data.body} />
            </section>
          )}
          <p className="ins-note muted small">{t('ins.disclaimer')}</p>
          <p className="ins-note muted small num">{t('ins.updated', { at: utcText(s.data.updatedAt, true) })}</p>
        </article>
      )}
      <PublicLinks />
    </div>
  );
}

/** 概率："58.3" → "58.3%"；"<1%" ">99%" "—" 原样 */
const pctText = (p: string): string => (p === '—' || p.endsWith('%') ? p : `${p}%`);

function Paragraphs({ text }: { text: string }) {
  return (
    <>
      {text
        .split(/\n{2,}/)
        .map((p) => p.trim())
        .filter(Boolean)
        .map((p, i) => (
          <p key={i}>{p}</p>
        ))}
    </>
  );
}

function FocusMarket({ m, lang }: { m: InsightMarket; lang: Lang }) {
  const { t } = useTranslation();
  return (
    <section className="card ins-sec" data-testid="insight-market">
      <h2>{pickText(m.title, lang)}</h2>
      <dl className="ins-facts">
        <div>
          <dt>{t('ins.total')}</dt>
          <dd className="num">{m.total} USDT</dd>
        </div>
        <div>
          <dt>{t('ins.bettors')}</dt>
          <dd className="num">{m.bettors}</dd>
        </div>
        <div>
          <dt>{t('ins.closeAt')}</dt>
          <dd className="num">{utcText(m.closeAt, true)}</dd>
        </div>
      </dl>
      <ul className="ins-options">
        {m.options.map((o) => (
          <li key={o.optionNo}>
            <div className="ins-opt-head">
              <span>{pickText(o.label, lang)}</span>
              <span className="num">
                {o.amount} USDT · {pctText(o.pct)}
                {o.mult ? ` · ${o.mult}x` : ''}
              </span>
            </div>
            <div className="bar" aria-hidden="true">
              <i style={{ width: `${o.pct === '—' ? 0 : barWidth(o.pct)}%` }} />
            </div>
          </li>
        ))}
      </ul>
      <p className="muted small">{t('ins.poolNote', { at: utcText(m.at, true) })}</p>
      <Link to={`/markets/${m.publicNo}`} className="btn btn-primary">
        {t('ins.toMarket')}
      </Link>
    </section>
  );
}

function Btc({ b }: { b: BtcStats }) {
  const { t } = useTranslation();
  return (
    <section className="card ins-sec" data-testid="insight-btc">
      <dl className="ins-facts">
        <div>
          <dt>{t('ins.btc.up24')}</dt>
          <dd className="num">
            {b.up24} / {b.up24 + b.down24}（{b.up24Pct}%）
          </dd>
        </div>
        <div>
          <dt>{t('ins.btc.up7d')}</dt>
          <dd className="num">
            {b.up7d} / {b.up7d + b.down7d}（{b.up7dPct}%）
          </dd>
        </div>
        <div>
          <dt>{t('ins.btc.change24')}</dt>
          <dd className="num">{b.change24Pct}%</dd>
        </div>
        <div>
          <dt>{t('ins.btc.lastClose')}</dt>
          <dd className="num">{b.lastClose} USDT</dd>
        </div>
      </dl>
      <h2>{t('ins.btc.byHour')}</h2>
      <div className="ins-table-wrap">
        <table className="ins-table">
          <thead>
            <tr>
              <th>{t('ins.btc.hour')}</th>
              <th>{t('ins.btc.upDays')}</th>
              <th>{t('ins.btc.pct')}</th>
            </tr>
          </thead>
          <tbody>
            {b.byHour.map((h) => (
              <tr key={h.hour}>
                <td className="num">{String(h.hour).padStart(2, '0')}:00</td>
                <td className="num">
                  {h.up} / {h.total}
                </td>
                <td className="num">{h.pct}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">{t('ins.btc.rule')}</p>
      <p className="muted small ins-source">
        {t('ins.btc.source', { source: b.source, from: utcText(b.from, true), to: utcText(b.to, true), at: utcText(b.fetchedAt, true), bars: b.bars })}
        <br />
        <span className="num">SHA-256 {b.sha256.slice(0, 16)}…</span>
      </p>
    </section>
  );
}
