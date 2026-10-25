/** 资产：余额 / 可用 / 下注中 / 提现冻结；充值；最近资金流水（复制源的净值、保证金、挂单冻结在 forecast S1 删除，S2 加下注中）。 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { listLedger } from '../../api/wallet';
import { useAccount } from '../../app/contexts';
import { AsyncView, EmptyState, ErrorState, Loading } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { PageHeader } from '../../components/Layout';
import { useAsync } from '../../hooks/useAsync';
import { useServerEvent } from '../../hooks/useServerEvent';
import { formatAmount } from '../../lib/format';
import { DepositCard } from './DepositCard';
import { LedgerList } from './LedgerList';

function Overview() {
  const { t } = useTranslation();
  const { metrics, error, loading, reload } = useAccount();
  const [hidden, setHidden] = useState(false);
  const show = (v: Parameters<typeof formatAmount>[0]) => (hidden ? '****' : formatAmount(v));
  if (error && !metrics) return <section className="card"><ErrorState error={error} onRetry={reload} compact /></section>;
  if (loading || !metrics) return <section className="card"><Loading rows={2} /></section>;
  return (
    <section className="card overview">
      <div className="ov-k">
        {t('assets.balance')}
        <button type="button" className="icon-btn sm" onClick={() => setHidden((h) => !h)} aria-pressed={hidden} aria-label={hidden ? t('assets.showAmounts') : t('assets.hideAmounts')}>
          <Icon name="eye" size={15} />
        </button>
      </div>
      <div className="ov-big num">
        {show(metrics.balance)}
        <small>USDT</small>
      </div>
      <div className="ov-row">
        <div>
          {t('account.available')}
          <span className="num">{show(metrics.available)}</span>
        </div>
        <div>
          {t('assets.betLocked')}
          <span className="num">{show(metrics.betLocked)}</span>
        </div>
        <div>
          {t('assets.frozenWithdraw')}
          <span className="num">{show(metrics.withdrawFrozen)}</span>
        </div>
      </div>
    </section>
  );
}

/**
 * "充值"按钮：充值卡片就在本页下方，手机上常常已经在屏幕里，只跳锚点看起来"没有反应"。
 * 所以滚到卡片、闪一下边框，并把焦点放到第一个网络按钮上。
 */
function showDeposit() {
  const card = document.getElementById('dep-card');
  if (!card) return;
  card.scrollIntoView({ behavior: 'smooth', block: 'start' });
  card.classList.remove('flash');
  void card.offsetWidth; // 重新触发动画
  card.classList.add('flash');
  window.setTimeout(() => card.classList.remove('flash'), 1400);
  card.querySelector<HTMLButtonElement>('[role="radio"]')?.focus({ preventScroll: true });
}

export default function Assets() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const recent = useAsync(() => listLedger({ type: 'all', range: 'all', page: 1, pageSize: 5 }), 'ledger-recent');
  useServerEvent((e) => e.type === 'account' && recent.reload());
  return (
    <div className="page page-assets">
      <PageHeader title={t('nav.assets')} />
      <div className="assets-grid">
        <div>
          <Overview />
          <nav className="acts" aria-label={t('assets.actions')}>
            <button type="button" className="on" onClick={showDeposit}>
              <Icon name="deposit" size={16} strokeWidth={2.2} />
              {t('assets.deposit')}
            </button>
            <button type="button" onClick={() => navigate('/assets/withdraw')}>
              <Icon name="withdraw" size={16} strokeWidth={2.2} />
              {t('assets.withdraw')}
            </button>
            <button type="button" onClick={() => navigate('/assets/ledger')}>
              <Icon name="list" size={16} strokeWidth={2.2} />
              {t('assets.ledger')}
            </button>
          </nav>
          <DepositCard />
        </div>
        <section className="card">
          <div className="card-head">
            <h2>{t('assets.ledger')}</h2>
            <Link to="/assets/ledger" className="more">
              {t('ledger.filterAll')}
              <Icon name="chevronRight" size={14} />
            </Link>
          </div>
          <AsyncView state={recent} isEmpty={(d) => d.items.length === 0} empty={<EmptyState compact title={t('ledger.empty')} hint={t('ledger.emptyHint')} />}>
            {(d) => <LedgerList items={d.items} />}
          </AsyncView>
        </section>
      </div>
    </div>
  );
}
