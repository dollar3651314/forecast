/**
 * 对账页上方的热钱包余额（2026-10-17 热钱包余额告警）：三个热钱包（ERC20、BSC、TRON）的 USDT 和手续费币当前余额、告警下限、最后查询时间。
 * 余额由后端每 5 分钟查一次；下限在"系统配置"页改（hotWallet.*，0 = 不告警，默认）。没有配置热钱包时显示一行说明。
 */
import { useCallback } from 'react';
import { Button, Table } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { listHotWallets } from '../../api';
import type { HotWalletBalance } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { ChainTag, Pill, type PillTone } from '../../components/Pill';
import { Num } from '../../components/Num';
import { ErrorState } from '../../components/ErrorState';
import { shortHash, shortTime } from '../../utils/display';

const TONE: Record<HotWalletBalance['status'], PillTone> = { ok: 'ok', low: 'danger', off: 'muted', unknown: 'warn' };

export function HotWalletCard() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => listHotWallets(), []);
  const { data, loading, error, reload } = useApi(fetcher);

  return (
    <div className="card card-pad" style={{ marginBottom: 12 }}>
      <div className="card-title">
        <span>{t('hotWallet.title')}</span>
        <Button size="small" icon={<ReloadOutlined />} onClick={reload} aria-label={t('hotWallet.reload')}>
          {t('hotWallet.reload')}
        </Button>
      </div>
      <p className="muted" style={{ margin: '0 0 8px', fontSize: 12 }}>{t('hotWallet.hint')}</p>
      {error ? (
        <ErrorState error={error} onRetry={reload} compact />
      ) : (
        <Table<HotWalletBalance>
          size="small"
          rowKey={(r) => `${r.chain}-${r.asset}`}
          loading={loading}
          dataSource={data ?? []}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: t('hotWallet.empty') }}
          columns={[
            { title: t('hotWallet.chain'), dataIndex: 'chain', render: (v: string) => <ChainTag chain={v} /> },
            { title: t('hotWallet.asset'), dataIndex: 'asset', render: (v: string, r) => (r.gas ? `${v} · ${t('hotWallet.gas')}` : v) },
            { title: t('hotWallet.address'), dataIndex: 'address', render: (v: string) => <Num className="muted"><span title={v}>{shortHash(v, 8, 6)}</span></Num> },
            { title: t('hotWallet.balance'), dataIndex: 'balance', align: 'right', render: (v: string | null) => <Num strong>{v ?? '—'}</Num> },
            {
              title: t('hotWallet.threshold'),
              dataIndex: 'threshold',
              align: 'right',
              render: (v: string, r) => (r.status === 'off' ? <span className="muted">{t('hotWallet.off')}</span> : <Num>{v}</Num>),
            },
            {
              title: t('hotWallet.status'),
              dataIndex: 'status',
              render: (v: HotWalletBalance['status'], r) => (
                <Pill tone={TONE[v]} title={r.error ?? undefined}>
                  {t(`hotWalletStatus.${v}`)}
                </Pill>
              ),
            },
            {
              title: t('hotWallet.checkedAt'),
              dataIndex: 'checkedAt',
              render: (v: string | null, r) => (
                <span>
                  <Num>{shortTime(v)}</Num>
                  {r.error ? (
                    <small className="ellipsis" style={{ display: 'block', maxWidth: 220, color: 'var(--danger)' }} title={r.error}>
                      {t('hotWallet.queryFailed')}
                    </small>
                  ) : null}
                </span>
              ),
            },
          ]}
        />
      )}
    </div>
  );
}
