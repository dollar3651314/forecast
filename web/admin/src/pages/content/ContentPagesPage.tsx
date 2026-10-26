/**
 * 内容中心 → 网站页面（forecast S15；MF-11、EQ-7）：焦点战页（每场一页，定时任务建）、BTC 每小时涨跌统计页（每天更新数据）、文章页（文章审核发布时建）。
 * 数据部分自动；说明文字要起草 → 审核发布后才显示（没审核的页面只显示数据）；每天新增页面最多 N 个（默认 5）。显示 / 隐藏是 content:publish（高危）。
 */
import { useCallback } from 'react';
import { App, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { generateContentDrafts, listContentPages, refreshBtcPage, setPageVisible } from '../../api';
import type { ContentPageRow } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useAction, useHighRisk } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';
import { GeminiBanner } from './common';

export function ContentPagesPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const { data, loading, error, reload } = useApi(useCallback(() => listContentPages(), []));
  const act = useAction();
  const hr = useHighRisk();
  return (
    <>
      <PageHeader
        title={t('ct.pages.title')}
        sub={data ? t('ct.pages.sub', { n: data.createdToday, max: data.dailyLimit }) : t('ct.pages.subLoading')}
        extra={
          <PermButton perm="content:edit" onClick={() => void act(() => refreshBtcPage(), t('ct.pages.refreshed'), reload)} data-testid="btc-refresh">
            {t('ct.pages.refreshBtc')}
          </PermButton>
        }
      />
      <GeminiBanner />
      <DataTable<ContentPageRow>
        rowKey="slug"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        emptyText={t('ct.pages.empty')}
        columns={[
          { title: t('ct.pages.kind'), dataIndex: 'kind', render: (v: string) => <Tag color={v === 'ARTICLE' ? 'purple' : v === 'FOCUS' ? 'blue' : 'gold'}>{t(`ct.pageKind.${v}`)}</Tag> },
          {
            title: t('ct.pages.titleCol'),
            key: 't',
            render: (_, p) => (
              <a href={p.path} target="_blank" rel="noreferrer">
                {p.titleZhCN || p.titleEn}
              </a>
            ),
          },
          { title: t('ct.pages.path'), dataIndex: 'path' },
          {
            title: t('ct.pages.text'),
            key: 'x',
            render: (_, p) => {
              const langs = p.kind === 'ARTICLE' ? p.bodyLangs : p.introLangs;
              return langs.length ? langs.map((l) => <Tag key={l}>{t(`ct.lang.${l}`)}</Tag>) : <span className="muted">{t('ct.pages.dataOnly')}</span>;
            },
          },
          { title: t('ct.pages.dataAt'), dataIndex: 'dataAt', render: (v: string | null) => utc(v) },
          { title: t('ct.pages.created'), dataIndex: 'createdDay' },
          { title: t('ct.pages.status'), dataIndex: 'status', render: (v: string) => <Pill tone={v === 'VISIBLE' ? 'ok' : 'muted'}>{t(`ct.pageStatus.${v}`)}</Pill> },
          {
            title: t('common.actions'),
            key: 'a',
            render: (_, p) => (
              <Space wrap>
                {p.kind !== 'ARTICLE' ? (
                  <PermButton
                    perm="content:edit"
                    size="small"
                    onClick={async () => {
                      try {
                        await generateContentDrafts({ kind: 'SEO_INTRO', pageSlug: p.slug });
                        void message.success(t('ct.pages.introStarted'));
                      } catch (e) {
                        void message.error(errorText(t, e));
                      }
                    }}
                  >
                    {t('ct.pages.draftIntro')}
                  </PermButton>
                ) : null}
                <PermButton
                  perm="content:publish"
                  size="small"
                  danger={p.status === 'VISIBLE'}
                  onClick={() =>
                    hr.ask({
                      title: t(p.status === 'VISIBLE' ? 'ct.pages.hide' : 'ct.pages.show'),
                      description: p.path,
                      onConfirm: (v) => setPageVisible(p.slug, p.status !== 'VISIBLE', v.reason),
                      onDone: reload,
                    })
                  }
                >
                  {t(p.status === 'VISIBLE' ? 'ct.pages.hide' : 'ct.pages.show')}
                </PermButton>
              </Space>
            ),
          },
        ]}
      />
      {hr.element}
    </>
  );
}
