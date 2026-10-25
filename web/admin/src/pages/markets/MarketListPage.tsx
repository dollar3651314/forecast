/**
 * 市场管理列表（需求第 6.2 节）：按分组（全部、待上架、草稿、进行中、等结果、已结束）、品类、关键字筛选；
 * 列：编号、品类、题目（来源：赛程生成 / 自动生成 / 手动）、状态、截止（UTC）、奖池、人数、费率、裁决方式、操作。
 * 待上架的草稿由有 market:publish 的人（默认超管）检查后上架；进行中的可以暂停、改费率（fee:edit，高危）、发补充说明。
 * M1 原型：数据来自 mocks/markets.ts；真实接口 GET /api/admin/markets（S3 已有）在 S9 接。
 */
import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { App, Input, Segmented, Select, Space, Tag } from 'antd';
import { CalendarOutlined, EditOutlined, PauseCircleOutlined, PlusOutlined, RobotOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { listAdminMarkets } from '../../api';
import type { AdminMarketQuery, AdminMarketRow, MarketCategory } from '../../api/types';
import { DataTable } from '../../components/DataTable';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { statusTone, textOf, utc } from '../../utils/market';

const TABS = ['all', 'review', 'draft', 'active', 'waiting', 'ended'] as const;
const CATS: MarketCategory[] = ['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];

export function MarketListPage() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [q, setQ] = useState<AdminMarketQuery>({ tab: 'all', page: 1, pageSize: 20 });
  const fetcher = useCallback(() => listAdminMarkets(q), [q]);
  const { data, loading, error, reload } = useApi(fetcher);
  const proto = () => void message.info(t('mk.protoAction'));

  return (
    <>
      <PageHeader
        title={t('mk.title')}
        sub={t('mk.sub')}
        extra={
          <>
            <PermButton perm="market:edit" icon={<CalendarOutlined />} onClick={proto}>
              {t('mk.fromSchedule')}
            </PermButton>
            <PermButton perm="market:autogen" icon={<RobotOutlined />} onClick={proto}>
              {t('mk.autogen')}
            </PermButton>
            <PermButton perm="market:edit" type="primary" icon={<PlusOutlined />} onClick={() => navigate('/markets/new')}>
              {t('mk.create')}
            </PermButton>
          </>
        }
      />
      <div className="mk-bar">
        <Segmented
          value={q.tab}
          onChange={(v) => setQ({ ...q, tab: v as AdminMarketQuery['tab'], page: 1 })}
          options={TABS.map((k) => ({ value: k, label: `${t(`mk.tabs.${k}`)}${data ? ` ${data.counts[k] ?? 0}` : ''}` }))}
        />
        <Space wrap>
          <Select
            allowClear
            style={{ width: 150 }}
            placeholder={t('mk.category')}
            value={q.category}
            onChange={(v) => setQ({ ...q, category: v, page: 1 })}
            options={CATS.map((c) => ({ value: c, label: t(`mk.cats.${c}`) }))}
          />
          <Input.Search allowClear style={{ width: 240 }} placeholder={t('mk.search')} onSearch={(v) => setQ({ ...q, keyword: v, page: 1 })} />
        </Space>
      </div>
      <DataTable<AdminMarketRow>
        rowKey="publicNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={q.page}
        pageSize={q.pageSize}
        onPageChange={(page, pageSize) => setQ({ ...q, page, pageSize })}
        emptyText={t('mk.empty')}
        columns={[
          { title: t('mk.col.no'), dataIndex: 'publicNo', render: (v: string) => <Num className="muted">{v}</Num> },
          { title: t('mk.col.category'), dataIndex: 'category', render: (v: MarketCategory) => <Tag>{t(`mk.cats.${v}`)}</Tag> },
          {
            title: t('mk.col.title'),
            key: 'title',
            render: (_, r) => (
              <div className="mk-cell-title">
                <Link to={r.status === 'draft' ? `/markets/${r.publicNo}/edit` : `/markets/${r.publicNo}/edit`}>{textOf(r.title, i18n.language)}</Link>
                <span className="muted">{t(`mk.origin.${r.origin}`)}</span>
              </div>
            ),
          },
          {
            title: t('mk.col.status'),
            key: 'status',
            render: (_, r) => <Pill tone={statusTone(r.status, r.submitted)}>{r.status === 'draft' && r.submitted ? t('mk.status.review') : t(`mk.status.${r.status}`)}</Pill>,
          },
          { title: t('mk.col.closeAt'), dataIndex: 'closeAt', render: (v: string) => <Num>{utc(v)}</Num> },
          { title: t('mk.col.pool'), dataIndex: 'pool', align: 'right', render: (v: string) => <Amount value={v} strong /> },
          { title: t('mk.col.bettors'), dataIndex: 'bettors', align: 'right', render: (v: number) => <Num>{v}</Num> },
          { title: t('mk.col.fee'), dataIndex: 'feeRate', align: 'right', render: (v: string) => <Num>{v}%</Num> },
          { title: t('mk.col.mode'), dataIndex: 'resolutionMode', render: (v: string) => t(`mk.mode.${v}`) },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <Space size={4}>
                {r.status === 'draft' ? (
                  <>
                    <PermButton perm="market:edit" size="small" icon={<EditOutlined />} onClick={() => navigate(`/markets/${r.publicNo}/edit`)}>
                      {t('common.edit')}
                    </PermButton>
                    {r.submitted && (
                      <PermButton perm="market:publish" size="small" type="primary" ghost icon={<SafetyCertificateOutlined />} onClick={() => navigate(`/markets/${r.publicNo}/edit`)}>
                        {t('mk.checkPublish')}
                      </PermButton>
                    )}
                  </>
                ) : r.status === 'listed' || r.status === 'paused' ? (
                  <>
                    <PermButton perm="market:pause" size="small" icon={<PauseCircleOutlined />} onClick={proto}>
                      {r.status === 'paused' ? t('mk.resume') : t('mk.pause')}
                    </PermButton>
                    <PermButton perm="fee:edit" size="small" onClick={proto}>
                      {t('mk.editFee')}
                    </PermButton>
                  </>
                ) : ['closed', 'pending_resolution', 'publicizing', 'disputed'].includes(r.status) ? (
                  <PermButton perm="resolve:propose" size="small" onClick={() => navigate('/resolve')}>
                    {t('mk.toResolve')}
                  </PermButton>
                ) : (
                  <PermButton perm="market:view" size="small" onClick={proto}>
                    {t('mk.view')}
                  </PermButton>
                )}
              </Space>
            ),
          },
        ]}
      />
    </>
  );
}
