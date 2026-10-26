/**
 * 仪表盘（需求第 6.2 节）：待处理（待上架、待提议、待确认、自动裁决失败、超时、争议中、结算异常、彩池对账差异、出金待审核、KYC）；
 * 今日 / 近 7 天的下注额、下注人数、平台收入（抽成 + 尾差）、补偿支出、新注册、充值、提现。
 * C41：彩池部分（GET /api/admin/dashboard/markets）和资金部分（GET /api/admin/dashboard，复制源）分开取，一处慢或出错只影响自己那一块，
 * 各自有"重试"。每 30 秒自动刷新（v2 E7），可暂停，页面不可见时停止。"今天"按 UTC。
 * S13（MF-3，SQ-21）：场次两项：同时开放 X / 上限 Y（超过时标红）、今日场次 N（今天 UTC 开始、列入今日场次的市场）。
 */
import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { Button, Skeleton } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getDashboard, getMarketDashboard } from '../api';
import type { DashPeriod } from '../api/fcTypes';
import { useApi } from '../hooks/useApi';
import { useAutoRefresh } from '../hooks/useAutoRefresh';
import { AutoRefreshControl } from '../components/AutoRefreshControl';
import { PageHeader } from '../components/PageHeader';
import { ErrorState } from '../components/ErrorState';
import { Amount, Num } from '../components/Num';
import { fullTime } from '../utils/display';
import { useAuth } from '../auth/AuthContext';

interface Todo {
  key: string;
  count: number;
  to: string;
  perm: string;
  tone: 'orange' | 'red' | 'violet' | '';
}

function TodoCard({ todo }: { todo: Todo }) {
  const { t } = useTranslation();
  const { can } = useAuth();
  return (
    <div className={`card kpi ${todo.count > 0 ? todo.tone : 'green'}`} data-testid={`todo-${todo.key}`}>
      <div className="k">{t(`dash.todo.${todo.key}`)}</div>
      <div className="v">
        <b>{todo.count}</b>
        {can(todo.perm) && todo.count > 0 ? (
          <Link className="kpi-link" to={todo.to}>
            {t('dash.handle')}
          </Link>
        ) : null}
      </div>
    </div>
  );
}

function PeriodCard({ title, p }: { title: string; p: DashPeriod }) {
  const { t } = useTranslation();
  return (
    <div className="card card-pad dash-period">
      <div className="card-title">
        <span>{title}</span>
        <span className="muted">{p.from === p.to ? `${p.from} UTC` : `${p.from} ~ ${p.to} UTC`}</span>
      </div>
      <dl className="dash-dl">
        <div>
          <dt>{t('dash.betAmount')}</dt>
          <dd>
            <Amount value={p.betAmount} strong /> <small className="muted">USDT</small>
          </dd>
        </div>
        <div>
          <dt>{t('dash.bettors')}</dt>
          <dd>
            <Num strong>{p.bettors}</Num> <small className="muted">{t('dash.bets', { n: p.bets })}</small>
          </dd>
        </div>
        <div>
          <dt>{t('dash.revenue')}</dt>
          <dd>
            <Amount value={p.revenue} strong /> <small className="muted">{t('dash.revenueNote')}</small>
          </dd>
        </div>
        <div>
          <dt>{t('dash.compensation')}</dt>
          <dd>
            <Amount value={p.compensation} />
          </dd>
        </div>
        <div>
          <dt>{t('dash.registrations')}</dt>
          <dd>
            <Num strong>{p.registrations}</Num>
          </dd>
        </div>
        <div>
          <dt>{t('dash.deposit')}</dt>
          <dd>
            <Amount value={p.deposit} /> <small className="muted">{t('dashboard.txCount', { count: p.depositCount })}</small>
          </dd>
        </div>
        <div>
          <dt>{t('dash.withdraw')}</dt>
          <dd>
            <Amount value={p.withdraw} /> <small className="muted">{t('dashboard.txCount', { count: p.withdrawCount })}</small>
          </dd>
        </div>
      </dl>
    </div>
  );
}

export function DashboardPage() {
  const { t } = useTranslation();
  const markets = useApi(useCallback(() => getMarketDashboard(), []));
  const funds = useApi(useCallback(() => getDashboard(), []));
  const refreshMarkets = markets.refresh;
  const refreshFunds = funds.refresh;
  const refreshAll = useCallback(() => {
    refreshMarkets();
    refreshFunds();
  }, [refreshMarkets, refreshFunds]);
  const auto = useAutoRefresh(refreshAll, 30_000);
  const m = markets.data;
  const f = funds.data;
  const todos: Todo[] = m
    ? [
        { key: 'review', count: m.pending.review, to: '/markets/review', perm: 'market:publish', tone: 'orange' },
        { key: 'awaitingProposal', count: m.pending.awaitingProposal, to: '/resolve?tab=awaiting_proposal', perm: 'resolve:propose', tone: 'orange' },
        { key: 'awaitingConfirm', count: m.pending.awaitingConfirm, to: '/resolve?tab=awaiting_confirm', perm: 'resolve:propose', tone: 'orange' },
        { key: 'autoFailed', count: m.pending.autoFailed, to: '/resolve?tab=auto_failed', perm: 'resolve:propose', tone: 'red' },
        { key: 'overdue', count: m.pending.overdue, to: '/resolve?tab=overdue', perm: 'resolve:propose', tone: 'red' },
        { key: 'disputed', count: m.pending.disputed, to: '/resolve/disputes', perm: 'dispute:view', tone: 'violet' },
        { key: 'settleAbnormal', count: m.pending.settleAbnormal, to: '/settle/runs?status=abnormal', perm: 'pool_recon:view', tone: 'red' },
        { key: 'poolReconOpen', count: m.pending.poolReconOpen, to: '/funds/pool-recon', perm: 'pool_recon:view', tone: 'red' },
        { key: 'withdrawalsPending', count: m.pending.withdrawalsPending, to: '/funds/withdrawals', perm: 'withdrawal:view', tone: 'orange' },
      ]
    : [];

  return (
    <>
      <PageHeader
        title={t('dashboard.title')}
        sub={m ? t('dashboard.updatedAt', { time: fullTime(m.updatedAt) }) : t('dash.sub')}
        extra={
          <>
            <AutoRefreshControl auto={auto} />
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                markets.reload();
                funds.reload();
              }}
              loading={markets.loading || funds.loading}
            >
              {t('common.refresh')}
            </Button>
          </>
        }
      />
      <h2 className="dash-h">{t('dash.pending')}</h2>
      {markets.error ? (
        <div className="card">
          <ErrorState error={markets.error} onRetry={markets.reload} compact />
        </div>
      ) : !m ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 2 }} />
        </div>
      ) : (
        <section className="kpis dash-todos" aria-label={t('dash.pending')}>
          {todos.map((x) => (
            <TodoCard key={x.key} todo={x} />
          ))}
          {f ? <TodoCard todo={{ key: 'kyc', count: f.pendingKyc, to: '/kyc', perm: 'kyc:view', tone: 'orange' }} /> : null}
        </section>
      )}
      {m ? (
        <>
          <h2 className="dash-h">{t('dash.slate')}</h2>
          <section className="detail-grid" aria-label={t('dash.slate')}>
            <div className={`card stat ${m.slate.limit > 0 && m.slate.open > m.slate.limit ? 'stat-red' : ''}`} data-testid="dash-open">
              <div className="k">{t('dash.openNow')}</div>
              <div className="v">
                {m.slate.open}
                <small className="muted"> / {m.slate.limit > 0 ? m.slate.limit : t('dash.noLimit')}</small>
              </div>
              <div className="muted">
                <Link to="/markets?tab=active">{t('common.view')}</Link>
              </div>
            </div>
            <div className="card stat" data-testid="dash-slate">
              <div className="k">{t('dash.todaySlate')}</div>
              <div className="v">{m.slate.today}</div>
              <div className="muted">
                <Link to="/markets?slate=1">{t('common.view')}</Link>
              </div>
            </div>
          </section>
        </>
      ) : null}
      <h2 className="dash-h">{t('dash.numbers')}</h2>
      {markets.error ? null : !m ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      ) : (
        <section className="dash-periods">
          <PeriodCard title={t('dash.today')} p={m.today} />
          <PeriodCard title={t('dash.week')} p={m.week} />
        </section>
      )}
      <h2 className="dash-h">{t('dash.funds')}</h2>
      {funds.error ? (
        <div className="card">
          <ErrorState error={funds.error} onRetry={funds.reload} compact />
        </div>
      ) : !f ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 2 }} />
        </div>
      ) : (
        <section className="detail-grid" aria-label={t('dash.funds')}>
          <div className="card stat">
            <div className="k">{t('dashboard.todayRegistrations')}</div>
            <div className="v">{f.todayRegistrations}</div>
            <div className="muted">{t('dashboard.online', { count: f.onlineCustomers })}</div>
          </div>
          <div className={`card stat ${f.failedTasks > 0 ? 'stat-red' : ''}`}>
            <div className="k">{t('dashboard.failedTasks')}</div>
            <div className="v">{f.failedTasks}</div>
            <div className="muted">
              <Link to="/funds/tasks">{t('common.view')}</Link>
            </div>
          </div>
          <div className={`card stat ${f.openReconciliations > 0 ? 'stat-red' : ''}`}>
            <div className="k">{t('dashboard.openRecon')}</div>
            <div className="v">{f.openReconciliations}</div>
            <div className="muted">
              <Link to="/funds/reconciliation">{t('common.view')}</Link>
            </div>
          </div>
        </section>
      )}
    </>
  );
}
