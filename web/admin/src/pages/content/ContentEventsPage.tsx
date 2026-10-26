/**
 * 内容中心 → 事件候选（forecast S15；自动化需求第 3 节、第 7 节"事件候选"，AC-100、AC-101、AC-103）：
 * - 经济日历（FOMC、美国 CPI，EQ-6）：运营录入发布时间（CPI 还要上个月公布的同比），发布前 7 天程序按题型模板生成<b>市场草稿</b>，
 *   要有上架权限的人在"市场管理 → 待上架"检查后上架；也可以"立即生成"。
 * - 焦点战建议（规则排的，附理由；只是建议，生成草稿在"体育赛程"里由人操作，MQ-1、MQ-10）。
 * - 赛程（S8，只读）和已过滤（敏感词、品类不在白名单，记原因）。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Card, DatePicker, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import utcPlugin from 'dayjs/plugin/utc';
import { useTranslation } from 'react-i18next';
import { addEcon, cancelEcon, draftEcon, getCandidates } from '../../api';
import type { CandidateFixture, EconEvent, EconKind, EconStatus, FilteredItem, FocusSuggestion } from '../../api/fcTypes';
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill, type PillTone } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useAction } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

dayjs.extend(utcPlugin);

const ECON_TONE: Record<EconStatus, PillTone> = {
  SCHEDULED: 'pend',
  DRAFTING: 'info',
  DRAFTED: 'ok',
  FILTERED: 'warn',
  FAILED: 'danger',
  CANCELLED: 'muted',
  EXPIRED: 'muted',
};

export function ContentEventsPage() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi(useCallback(() => getCandidates(), []));
  const [add, setAdd] = useState(false);
  const act = useAction();

  if (error) return <ErrorState error={error} onRetry={reload} />;
  return (
    <>
      <PageHeader
        title={t('ct.events.title')}
        sub={t('ct.events.sub')}
        extra={
          <PermButton perm="content:edit" type="primary" icon={<PlusOutlined />} onClick={() => setAdd(true)} data-testid="econ-add">
            {t('ct.events.addEcon')}
          </PermButton>
        }
      />
      <Card size="small" title={t('ct.events.econ')} className="ct-card" extra={<span className="muted">{t('ct.events.econHint')}</span>}>
        <Table<EconEvent>
          size="middle"
          rowKey="id"
          loading={loading && !data}
          dataSource={data?.econ}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: t('ct.events.econEmpty') }}
          columns={[
            { title: t('ct.events.kind'), dataIndex: 'kind', render: (v: EconKind) => <Tag color="gold">{t(`ct.econKind.${v}`)}</Tag> },
            { title: t('ct.events.period'), dataIndex: 'period' },
            { title: t('ct.events.releaseAt'), dataIndex: 'releaseAt', render: (v: string) => utc(v, true) },
            { title: t('ct.events.prev'), dataIndex: 'prevValue', render: (v: string | null) => (v ? `${v}%` : '—') },
            { title: t('ct.events.dueAt'), dataIndex: 'draftDueAt', render: (v: string) => utc(v, true) },
            { title: t('ct.events.status'), key: 's', render: (_, e) => <Pill tone={ECON_TONE[e.status]} title={e.note ?? undefined}>{t(`ct.econStatus.${e.status}`)}</Pill> },
            {
              title: t('ct.events.market'),
              key: 'm',
              render: (_, e) =>
                e.marketNo ? (
                  <Space size={4}>
                    <Link to={`/markets/${e.marketNo}`}>{e.marketNo}</Link>
                    {e.marketStatus ? <Tag>{t(`mk.status.${e.marketStatus}`, { defaultValue: e.marketStatus })}</Tag> : null}
                  </Space>
                ) : (
                  <span className="muted">{e.note ?? '—'}</span>
                ),
            },
            {
              title: t('common.actions'),
              key: 'a',
              render: (_, e) =>
                e.status === 'SCHEDULED' || e.status === 'FAILED' ? (
                  <Space>
                    <PermButton perm="content:edit" size="small" onClick={() => void act(() => draftEcon(e.id), t('ct.events.drafted'), reload)} data-testid={`econ-draft-${e.id}`}>
                      {t('ct.events.draftNow')}
                    </PermButton>
                    <PermButton perm="content:edit" size="small" danger onClick={() => void act(() => cancelEcon(e.id), undefined, reload)}>
                      {t('ct.events.cancel')}
                    </PermButton>
                  </Space>
                ) : null,
            },
          ]}
        />
      </Card>

      <Card size="small" title={t('ct.events.focus')} className="ct-card" extra={<Link to="/markets/sports">{t('ct.events.toSports')}</Link>}>
        {data?.sportsError ? <Alert type="warning" showIcon message={data.sportsError} /> : null}
        {data && !data.focus.length ? <p className="muted">{t('ct.events.focusEmpty')}</p> : null}
        <div className="ct-focus">
          {(data?.focus ?? []).map((f) => (
            <FocusCard key={f.fixtureNo} f={f} />
          ))}
        </div>
      </Card>

      <Card size="small" title={t('ct.events.fixtures')} className="ct-card">
        <Table<CandidateFixture>
          size="small"
          rowKey="fixtureNo"
          loading={loading && !data}
          dataSource={data?.fixtures}
          scroll={{ x: 'max-content' }}
          pagination={{ pageSize: 10, size: 'small', hideOnSinglePage: true }}
          columns={[
            { title: t('ct.events.kickoff'), dataIndex: 'kickoffAt', render: (v: string) => utc(v) },
            { title: t('ct.events.match'), key: 'm', render: (_, f) => `${f.home} vs ${f.away}` },
            { title: t('ct.events.league'), dataIndex: 'competitionName' },
            { title: t('ct.events.mapping'), dataIndex: 'mappingConfirmed', render: (v: boolean) => (v ? <Tag color="green">{t('ct.events.mapped')}</Tag> : <Tag>{t('ct.events.unmapped')}</Tag>) },
            { title: t('ct.events.markets'), dataIndex: 'markets' },
            { title: t('ct.events.filtered'), key: 'f', render: (_, f) => (f.filtered ? <Tag color="red">{t('ct.events.filteredBy', { by: f.filteredBy })}</Tag> : '—') },
          ]}
        />
      </Card>

      <Card size="small" title={t('ct.events.filteredLog')} className="ct-card">
        <Table<FilteredItem>
          size="small"
          rowKey={(f) => `${f.source}-${f.ref}`}
          dataSource={data?.filtered}
          scroll={{ x: 'max-content' }}
          pagination={{ pageSize: 10, size: 'small', hideOnSinglePage: true }}
          locale={{ emptyText: t('ct.events.filteredEmpty') }}
          columns={[
            { title: t('ct.events.time'), dataIndex: 'createdAt', render: (v: string) => utc(v) },
            { title: t('ct.events.source'), dataIndex: 'source', render: (v: string) => t(`ct.source.${v}`) },
            { title: t('ct.events.subject'), dataIndex: 'title' },
            { title: t('ct.events.reason'), key: 'r', render: (_, f) => `${t(`ct.filterReason.${f.reason}`)}：${f.matched}` },
          ]}
        />
      </Card>
      <EconModal open={add} onClose={() => setAdd(false)} onDone={() => { setAdd(false); reload(); }} />
    </>
  );
}

function FocusCard({ f }: { f: FocusSuggestion }) {
  const { t } = useTranslation();
  return (
    <div className="card ct-focus-card" data-testid={`focus-${f.fixtureNo}`}>
      <div className="ct-focus-head">
        <b>
          {f.home} vs {f.away}
        </b>
        <Tag>{f.sport === 'NBA' ? 'NBA' : t('ct.events.football')}</Tag>
      </div>
      <div className="muted">
        {utc(f.kickoffAt)} · {t('ct.events.play', { play: f.suggestedPlay })}
      </div>
      <ul>
        {f.reasons.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
    </div>
  );
}

function EconModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ kind: EconKind; period: string; release: dayjs.Dayjs; prevValue?: string; sourceUrl?: string }>();
  const kind = Form.useWatch('kind', form);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const submit = async () => {
    const v = await form.validateFields();
    setBusy(true);
    setErr(null);
    try {
      await addEcon({
        kind: v.kind,
        period: v.period.trim(),
        releaseAt: dayjs.utc(v.release.format('YYYY-MM-DD HH:mm'), 'YYYY-MM-DD HH:mm').toISOString(),
        prevValue: v.prevValue?.trim() || undefined,
        sourceUrl: v.sourceUrl?.trim() || undefined,
      });
      form.resetFields();
      onDone();
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} title={t('ct.events.addEcon')} onCancel={onClose} onOk={() => void submit()} confirmLoading={busy} okText={t('common.save')} destroyOnClose>
      <Form form={form} layout="vertical" initialValues={{ kind: 'FOMC' }}>
        <Form.Item name="kind" label={t('ct.events.kind')} rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'FOMC', label: t('ct.econKind.FOMC') },
              { value: 'CPI', label: t('ct.econKind.CPI') },
            ]}
          />
        </Form.Item>
        <Form.Item name="period" label={t(kind === 'CPI' ? 'ct.events.periodCpi' : 'ct.events.periodFomc')} rules={[{ required: true, pattern: /^20\d{2}-(0[1-9]|1[0-2])$/, message: 'YYYY-MM' }]}>
          <Input placeholder="2026-12" />
        </Form.Item>
        <Form.Item name="release" label={t('ct.events.releaseUtc')} rules={[{ required: true, message: t('validation.required') }]} extra={t('ct.events.releaseHint')}>
          <DatePicker showTime={{ format: 'HH:mm' }} format="YYYY-MM-DD HH:mm" style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="prevValue"
          label={t(kind === 'CPI' ? 'ct.events.prevCpi' : 'ct.events.prevFomc')}
          rules={kind === 'CPI' ? [{ required: true, pattern: /^-?\d{1,2}\.\d$/, message: t('ct.events.prevCpiFmt') }] : [{ pattern: /^\d{1,2}\.\d{1,2}$/, message: '4.50' }]}
        >
          <Input placeholder={kind === 'CPI' ? '3.0' : '4.50'} />
        </Form.Item>
        <Form.Item name="sourceUrl" label={t('ct.events.sourceUrl')} rules={[{ pattern: /^https:\/\//, message: 'https://' }]}>
          <Input placeholder="https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm" />
        </Form.Item>
      </Form>
      {err ? <Typography.Paragraph type="danger">{errorText(t, err)}</Typography.Paragraph> : null}
    </Modal>
  );
}
