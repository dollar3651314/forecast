/**
 * 市场管理列表（需求第 6.2 节）：分组（全部、待上架、草稿、进行中、等结果、已结束，数字来自 GET /api/admin/markets/counts）、品类、关键字筛选；
 * 列：编号、品类、题目（来源：手动 / 赛程生成 / 加密自动生成）、状态、截止（UTC）、奖池、人数、费率、裁决方式、操作。
 * review = true 时是"待上架"页（菜单"待上架"，market:publish）：只列已提交上架的草稿，操作是"检查上架"。
 * 暂停 / 恢复、取消、补充说明、改费率、提前截止、作废在市场详情页（MarketDetailPage），列表只放进入的入口。
 */
import { useCallback, useMemo } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Input, Segmented, Select, Space, Tag } from 'antd';
import { CalendarOutlined, EditOutlined, EyeOutlined, PlusOutlined, RobotOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getMarketCounts, listMarkets } from '../../api';
import { CATEGORIES, type MarketCategory, type MarketSummary } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { TAB_STATUSES, statusTone, textOf, utc } from '../../utils/market';

const TABS = ['all', 'review', 'draft', 'active', 'waiting', 'ended'] as const;
type Tab = (typeof TABS)[number];

export function MarketListPage({ review = false }: { review?: boolean }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [sp, setSp] = useSearchParams();
  const tab: Tab = review ? 'review' : ((TABS as readonly string[]).includes(sp.get('tab') ?? '') ? (sp.get('tab') as Tab) : 'all');
  const category = (sp.get('category') as MarketCategory | null) ?? undefined;
  const keyword = sp.get('q') ?? undefined;
  const page = Number(sp.get('page') ?? 1) || 1;
  const pageSize = Number(sp.get('size') ?? 20) || 20;
  const set = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === '') next.delete(k);
      else next.set(k, v);
    }
    setSp(next, { replace: true });
  };

  const fetcher = useCallback(
    () =>
      listMarkets({
        status: tab === 'review' ? 'draft' : TAB_STATUSES[tab],
        submitted: tab === 'review' ? true : undefined,
        category,
        keyword,
        page,
        pageSize,
      }),
    [tab, category, keyword, page, pageSize],
  );
  const { data, loading, error, reload } = useApi(fetcher);
  const counts = useApi(useCallback(() => getMarketCounts(), []));
  const countOf = useMemo(() => {
    const c = counts.data;
    if (!c) return () => null;
    const sum = (list: string | undefined) => (list ? list.split(',').reduce((a, s) => a + (c.byStatus[s as keyof typeof c.byStatus] ?? 0), 0) : Object.values(c.byStatus).reduce((a, b) => a + b, 0));
    return (k: Tab) => (k === 'review' ? c.review : sum(TAB_STATUSES[k]));
  }, [counts.data]);

  return (
    <>
      <PageHeader
        title={review ? t('mk.reviewTitle') : t('mk.title')}
        sub={review ? t('mk.reviewSub') : t('mk.sub')}
        extra={
          review ? undefined : (
            <>
              <PermButton perm="market:view" icon={<CalendarOutlined />} onClick={() => navigate('/markets/sports')}>
                {t('mk.fromSchedule')}
              </PermButton>
              <PermButton perm="market:view" icon={<RobotOutlined />} onClick={() => navigate('/markets/autogen')}>
                {t('mk.autogen')}
              </PermButton>
              <PermButton perm="market:edit" type="primary" icon={<PlusOutlined />} onClick={() => navigate('/markets/new')}>
                {t('mk.create')}
              </PermButton>
            </>
          )
        }
      />
      <div className="fc-bar">
        {review ? (
          <span className="muted">{t('mk.reviewHint')}</span>
        ) : (
          <Segmented<Tab>
            value={tab}
            onChange={(v) => set({ tab: v === 'all' ? undefined : v, page: undefined })}
            options={TABS.map((k) => {
              const n = countOf(k);
              return { value: k, label: `${t(`mk.tabs.${k}`)}${n === null ? '' : ` ${n}`}` };
            })}
          />
        )}
        <Space wrap>
          <Select
            allowClear
            style={{ width: 150 }}
            placeholder={t('mk.category')}
            value={category}
            onChange={(v) => set({ category: v, page: undefined })}
            options={CATEGORIES.map((c) => ({ value: c, label: t(`mk.cats.${c}`) }))}
          />
          <Input.Search allowClear defaultValue={keyword} style={{ width: 220 }} placeholder={t('mk.search')} onSearch={(v) => set({ q: v.trim() || undefined, page: undefined })} />
        </Space>
      </div>
      <DataTable<MarketSummary>
        rowKey="publicNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={pageSize}
        onPageChange={(p, s) => set({ page: String(p), size: String(s) })}
        emptyText={keyword || category ? t('mk.emptyFiltered') : review ? t('mk.reviewEmpty') : t('mk.empty')}
        columns={[
          { title: t('mk.col.no'), dataIndex: 'publicNo', render: (v: string) => <Num className="muted">{v}</Num> },
          { title: t('mk.col.category'), dataIndex: 'category', render: (v: MarketCategory) => <Tag>{t(`mk.cats.${v}`)}</Tag> },
          {
            title: t('mk.col.title'),
            key: 'title',
            render: (_, r) => (
              <div className="mk-cell-title">
                <Link to={`/markets/${r.publicNo}`}>{textOf(r.title, i18n.language)}</Link>
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
          { title: t('mk.col.mode'), dataIndex: 'resolutionMode', render: (v: string | null) => (v ? t(`mk.mode.${v}`) : <span className="muted">—</span>) },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <Space size={4}>
                {r.status === 'draft' ? (
                  r.submitted && review ? (
                    <PermButton perm="market:publish" size="small" type="primary" ghost icon={<SafetyCertificateOutlined />} onClick={() => navigate(`/markets/${r.publicNo}/edit`)}>
                      {t('mk.checkPublish')}
                    </PermButton>
                  ) : (
                    <PermButton perm="market:edit" size="small" icon={<EditOutlined />} onClick={() => navigate(`/markets/${r.publicNo}/edit`)}>
                      {t('common.edit')}
                    </PermButton>
                  )
                ) : (
                  <PermButton perm="market:view" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/markets/${r.publicNo}`)}>
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
