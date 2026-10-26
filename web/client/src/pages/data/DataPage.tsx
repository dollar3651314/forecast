/**
 * P13 数据页兼上线前等候页（S16，MF-8、MF-17；需求第 5.2 节 P13、AC-95、AC-96；SQ-22）。不登录可看、可被搜索收录（服务器版本：growth/board/DataPage，Nginx SSI）。
 * - ① BTC 现价和"过去 N 小时每小时涨跌的比例"（币安公开行情，写明来源和更新时间；收盘 ≥ 开盘算涨，和 BTC 场次的规则一样）；
 * - ② Polymarket 的概率：数据开关在 S15 做、默认关，这里不接（后端 polymarket.enabled 为 false 时不显示这一块）；
 * - ③ 我们今天的场次：正式上线以后显示两边的人数和概率（和市场详情同一份数字）；上线前显示"即将上线"和留邮箱（只发一封"正式上线了"，SQ-22）。
 * 照实写玩法（用 USDT 押注、赢家分输方奖池、平台只从赢家盈利里抽成，F-1、F-2）；不放 X 的数据。
 */
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { getBoard, joinWaitlist } from '../../api/board';
import { errorKey, isApiError } from '../../api/errors';
import type { Board, BoardItem } from '../../api/types';
import { useAuth } from '../../app/contexts';
import { ErrorState, Loading } from '../../components/AsyncView';
import { TextField } from '../../components/Form';
import { PageHeader } from '../../components/Layout';
import { PublicLinks } from '../../components/PublicLinks';
import { StatusTag } from '../../components/market/parts';
import { useAsync } from '../../hooks/useAsync';
import { useLang } from '../../hooks/useLang';
import { useTitle } from '../../hooks/useTitle';
import { readAttribution } from '../../lib/attribution';
import { formatAmount, formatDateTime } from '../../lib/format';
import { pickText } from '../../lib/market';
import { validateEmail } from '../../lib/validators';

function BtcBlock({ b }: { b: Board }) {
  const { t } = useTranslation();
  const btc = b.btc;
  return (
    <section className="card data-btc" aria-labelledby="data-btc-h">
      <h2 id="data-btc-h">{t('data.btcTitle')}</h2>
      {!btc.available ? (
        <p className="muted" data-testid="btc-none">
          {t('data.btcNone')}
        </p>
      ) : (
        <>
          <div className="data-price">
            <span className="muted">{t('data.btcPrice')}</span>
            <b className="num" data-testid="btc-price">
              {formatAmount(btc.price)} <small>USDT</small>
            </b>
          </div>
          <div className="data-hours" data-testid="btc-hours">
            <div className="data-hours-head">
              <span>{t('data.btcHours', { hours: btc.hours })}</span>
              <span className="num">
                <span className="up">{t('data.upN', { n: btc.up, pct: btc.upPct })}</span>
                {' · '}
                <span className="down">{t('data.downN', { n: btc.down, pct: btc.downPct })}</span>
              </span>
            </div>
            <div className="data-bar" role="img" aria-label={t('data.barLabel', { up: btc.upPct, down: btc.downPct })}>
              <span className="data-bar-up" style={{ width: `${btc.upPct}%` }} />
              <span className="data-bar-down" style={{ width: `${btc.downPct}%` }} />
            </div>
          </div>
          <p className="data-note">{t('data.btcRule')}</p>
          <p className="data-note muted">{t('data.btcSource', { time: formatDateTime(btc.updatedAt) })}</p>
        </>
      )}
    </section>
  );
}

function SlateItem({ m }: { m: BoardItem }) {
  const { t } = useTranslation();
  const lang = useLang();
  const time = m.eventStartAt ?? m.closeAt;
  return (
    <li className="data-slate-item">
      <Link to={`/markets/${m.publicNo}`} className="data-slate-title">
        <span className="num muted">{formatDateTime(time, { short: true })}</span> {pickText(m.title, lang)}
      </Link>
      <div className="data-slate-status">
        {m.upcoming ? <span className="mk-status s-wait">{t('mk.status.upcoming')}</span> : <StatusTag status={m.status} />}
      </div>
      <div className="data-slate-opts">
        {m.options.map((o) => (
          <span key={o.optionNo} className="data-opt">
            <b>{pickText(o.label, lang)}</b> <span className="num">{o.pct}</span> <span className="muted">{t('data.bettors', { n: o.bettors })}</span>
          </span>
        ))}
      </div>
      <div className="muted data-note">
        {m.upcoming ? t('data.upcoming') : t('data.pool', { pool: formatAmount(m.poolTotal), n: m.bettors })}
      </div>
    </li>
  );
}

function Waitlist() {
  const { t } = useTranslation();
  const lang = useLang();
  const [email, setEmail] = useState('');
  const [website, setWebsite] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const errs = validateEmail(email);
  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    setErr(null);
    if (errs.length) return;
    setBusy(true);
    try {
      await joinWaitlist(email, lang, website, readAttribution());
      setDone(true);
    } catch (x) {
      setErr(isApiError(x) && x.fieldErrors?.email ? t('validation.emailFormat') : t(errorKey(x)));
    } finally {
      setBusy(false);
    }
  };
  if (done) {
    return (
      <div className="notice info" role="status" data-testid="wl-done">
        <div>
          <b>{t('data.wlDoneTitle')}</b>
          <span>{t('data.wlDone', { email: email.trim() })}</span>
        </div>
      </div>
    );
  }
  return (
    <form className="data-wl" onSubmit={onSubmit} noValidate>
      <TextField
        label={t('data.wlEmail')}
        type="email"
        autoComplete="email"
        inputMode="email"
        value={email}
        onChange={setEmail}
        errors={submitted ? errs : undefined}
        hint={t('data.wlHint')}
        addon={
          <button type="submit" className="btn btn-primary" disabled={busy} data-testid="wl-submit">
            {busy ? t('common.submitting') : t('data.wlSubmit')}
          </button>
        }
      />
      {/* 防刷：正常人看不到、不会填的隐藏字段（有值时服务器当成机器提交，照样回答、什么都不做） */}
      <div className="hp-field" aria-hidden="true">
        <label htmlFor="wl-website">Website</label>
        <input id="wl-website" tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} />
      </div>
      {err && (
        <div className="notice error" role="alert">
          {err}
        </div>
      )}
    </form>
  );
}

export default function DataPage() {
  const { t } = useTranslation();
  const { session } = useAuth();
  useTitle(t('data.title'));
  const s = useAsync(() => getBoard(), 'board');
  const b = s.data;
  return (
    <div className="page page-data">
      <PageHeader title={t('data.title')} />
      <p className="howto-intro">{t('data.intro')}</p>
      {s.error && !b ? (
        <section className="card">
          <ErrorState error={s.error} onRetry={s.reload} />
        </section>
      ) : !b ? (
        <section className="card">
          <Loading rows={5} />
        </section>
      ) : (
        <div className="data-grid">
          <BtcBlock b={b} />
          <section className="card data-slate" aria-labelledby="data-slate-h">
            {b.launched ? (
              <>
                <h2 id="data-slate-h">{t('data.slateTitle')}</h2>
                {!b.slate.available ? (
                  <p className="muted">{t('data.slateLogin')}</p>
                ) : b.slate.items.length === 0 ? (
                  <p className="muted" data-testid="slate-empty">
                    {t('data.slateNone')}
                    {b.slate.next ? ` ${t('data.slateNext', { time: formatDateTime(b.slate.next.eventStartAt ?? b.slate.next.closeAt) })}` : ''}
                  </p>
                ) : (
                  <ul className="data-slate-list" data-testid="slate-list">
                    {b.slate.items.map((m) => (
                      <SlateItem key={m.publicNo} m={m} />
                    ))}
                  </ul>
                )}
                {!session && (
                  <div className="howto-cta">
                    <Link to="/register" className="btn btn-primary">
                      {t('guest.register')}
                    </Link>
                  </div>
                )}
              </>
            ) : (
              <>
                <h2 id="data-slate-h">{t('data.launchTitle')}</h2>
                <p>{t('data.launchText')}</p>
                <Waitlist />
              </>
            )}
            {(b.telegram.channelZh || b.telegram.channelEn || b.telegram.bot) && (
              <p className="data-tg">
                {t('data.telegram')}{' '}
                {(
                  [
                    [b.telegram.channelZh, 'data.tgChannelZh'],
                    [b.telegram.channelEn, 'data.tgChannelEn'],
                    [b.telegram.bot, 'data.tgBot'],
                  ] as const
                )
                  .filter(([href]) => !!href)
                  .map(([href, key], i) => (
                    <span key={key}>
                      {i > 0 && ' · '}
                      <a href={href!} target="_blank" rel="noreferrer">
                        {t(key)}
                      </a>
                    </span>
                  ))}
              </p>
            )}
          </section>
        </div>
      )}
      <PublicLinks />
    </div>
  );
}
