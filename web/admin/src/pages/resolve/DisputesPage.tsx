/**
 * 公示与争议（需求第 4.4、6.2 节；后端 ResolveDeskApi，S7）：
 * 争议中的市场（待处理，处理时限）、公示中的市场（争议截止倒计时）、全部争议记录（市场、争议人编号、主张的结果、理由、时间、状态）。
 * 选中一个市场：公示的结果、每个争议的主张和理由、链接；处理（dispute:handle ★）：维持 / 改判（选新结果）/ 无法判定（作废，全额退款），
 * 结论和理由必填（公开在市场详情，不显示争议人），原因必填、勾选确认；一个有权限的人确认即可，处理完直接结算（E12，不再公示第二轮）。
 */
import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Card, Empty, Form, Input, Radio, Segmented, Skeleton, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { decideDisputes, getDisputeCase, listCases, listDisputes } from '../../api';
import type { CaseItem, Dispute } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { minutesLeft, textOf, utc } from '../../utils/market';

type View = 'disputed' | 'publicizing' | 'records';

function Countdown({ iso }: { iso: string | null }) {
  const { t } = useTranslation();
  if (!iso) return <span className="muted">—</span>;
  const m = minutesLeft(iso);
  return <span className={m < 0 ? 'rs-due late' : m < 60 ? 'rs-due soon' : 'rs-due'}>{m < 0 ? t('ds.ended') : t('ds.left', { h: Math.floor(m / 60), m: m % 60 })}</span>;
}

function DecidePanel({ no, onDone }: { no: string; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const { data, error, reload } = useApi(useCallback(() => getDisputeCase(no), [no]));
  const hr = useHighRisk();
  const [decision, setDecision] = useState<'uphold' | 'overturn' | 'void'>('uphold');
  const [option, setOption] = useState<string | null>(null);
  const [note, setNote] = useState('');
  if (error) return <ErrorState error={error} onRetry={reload} compact />;
  if (!data) return <Skeleton active paragraph={{ rows: 6 }} />;
  const s = data.summary;
  const label = (o: string | null, voidClaim = false) => (voidClaim || !o ? t('ds.claimVoid') : textOf(data.options.find((x) => x.optionNo === o)?.label, i18n.language));
  const open = data.disputes.filter((d) => d.status === 'open');
  return (
    <Card size="small" title={<Link to={`/markets/${no}`}>{textOf(s.title, i18n.language)}</Link>} extra={<Num className="muted">{no}</Num>}>
      <div className="rs-meta">
        <Pill tone="info">{t(`mk.status.${s.marketStatus}`)}</Pill>
        <span>
          {t('ds.result')} <b>{label(s.resultOptionNo)}</b>
        </span>
        {s.stage === 'publicizing' ? (
          <span>
            {t('ds.deadline')} <Num>{utc(s.disputeDeadlineAt)}</Num> <Countdown iso={s.disputeDeadlineAt} />
          </span>
        ) : (
          <span>
            {t('ds.handleDue')} <Num>{utc(s.disputeDueAt)}</Num> {s.disputeOverdue ? <span className="rs-due late">{t('rs.overdue')}</span> : null}
          </span>
        )}
      </div>
      {data.disputes.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ds.noDispute')} />
      ) : (
        <div className="ds-list">
          {data.disputes.map((d) => (
            <div key={d.disputeNo} className="ds-item">
              <div>
                <b>{t('ds.claim', { opt: label(d.claimedOptionNo, d.claimVoid) })}</b> <span className="muted">· {t('ds.by', { id: d.userId })} · <Num>{utc(d.receivedAt)}</Num></span> <Pill tone={d.status === 'open' ? 'pend' : 'muted'}>{t(`ds.st.${d.status}`, { defaultValue: d.status })}</Pill>
              </div>
              <div>{d.reason}</div>
              {d.link ? (
                <a href={d.link} target="_blank" rel="noreferrer noopener">
                  {d.link}
                </a>
              ) : null}
              {d.decisionNote ? <div className="muted">{t('ds.conclusion', { note: d.decisionNote })}</div> : null}
            </div>
          ))}
        </div>
      )}
      {s.stage === 'disputed' && open.length ? (
        <section className="rs-decide" data-testid="dispute-decide">
          <h3>{t('ds.decide', { n: open.length })}</h3>
          <Radio.Group value={decision} onChange={(e) => setDecision(e.target.value)}>
            <Space direction="vertical">
              <Radio value="uphold">{t('ds.uphold', { opt: label(s.resultOptionNo) })}</Radio>
              <Radio value="overturn">
                {t('ds.overturn')}
                {decision === 'overturn' ? (
                  <Radio.Group
                    size="small"
                    className="rs-pick"
                    optionType="button"
                    value={option}
                    onChange={(e) => setOption(e.target.value)}
                    options={data.options.filter((o) => o.optionNo !== s.resultOptionNo).map((o) => ({ value: o.optionNo, label: textOf(o.label, i18n.language) }))}
                  />
                ) : null}
              </Radio>
              <Radio value="void">{t('ds.void')}</Radio>
            </Space>
          </Radio.Group>
          <Form layout="vertical">
            <Form.Item label={t('ds.note')} required extra={t('ds.noteHint')}>
              <Input.TextArea rows={3} maxLength={1000} showCount value={note} onChange={(e) => setNote(e.target.value)} />
            </Form.Item>
          </Form>
          <PermButton
            perm="dispute:handle"
            type="primary"
            danger={decision === 'void'}
            disabled={!note.trim() || (decision === 'overturn' && !option)}
            disabledReason={t('ds.fillFirst')}
            onClick={() =>
              hr.ask({
                title: t(`ds.dtitle.${decision}`),
                description: <Alert type="warning" showIcon message={t('ds.afterNote')} />,
                onConfirm: (v) => decideDisputes(no, decision, decision === 'overturn' ? option : null, note.trim(), v.reason),
                onDone: () => {
                  reload();
                  onDone();
                },
              })
            }
          >
            {t('ds.submit')}
          </PermButton>
        </section>
      ) : null}
      {hr.element}
    </Card>
  );
}

function CaseList({ tab, sel, onSel }: { tab: 'disputed' | 'publicizing'; sel: string | null; onSel: (no: string) => void }) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const { data, loading, error, reload } = useApi(useCallback(() => listCases(tab, page, size), [tab, page, size]));
  return (
    <DataTable<CaseItem>
      rowKey="marketNo"
      loading={loading}
      error={error}
      onRetry={reload}
      dataSource={data?.items}
      total={data?.total}
      page={page}
      pageSize={size}
      onPageChange={(p, s) => {
        setPage(p);
        setSize(s);
      }}
      emptyText={tab === 'disputed' ? t('ds.emptyDisputed') : t('ds.emptyPublic')}
      rowClassName={(r) => (r.marketNo === sel ? 'row-selected' : '')}
      onRow={(r) => ({ onClick: () => onSel(r.marketNo) })}
      columns={[
        { title: t('mk.col.title'), key: 't', render: (_, r) => <b>{textOf(r.title, i18n.language)}</b> },
        tab === 'disputed'
          ? { title: t('ds.open'), dataIndex: 'openDisputes', align: 'right', render: (v: number) => <Num strong>{v}</Num> }
          : { title: t('ds.deadline'), dataIndex: 'disputeDeadlineAt', render: (v: string | null) => <Countdown iso={v} /> },
        tab === 'disputed' ? { title: t('ds.handleDue'), dataIndex: 'disputeDueAt', render: (v: string | null, r) => (r.disputeOverdue ? <span className="rs-due late">{t('rs.overdue')}</span> : <Num>{utc(v)}</Num>) } : { title: t('mk.col.category'), dataIndex: 'category', render: (v: string) => <Tag>{t(`mk.cats.${v}`)}</Tag> },
      ]}
    />
  );
}

function Records({ onSel }: { onSel: (no: string) => void }) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<'open' | 'handled' | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const { data, loading, error, reload } = useApi(useCallback(() => listDisputes(status, page, size), [status, page, size]));
  return (
    <>
      <Segmented
        value={status ?? 'all'}
        onChange={(v) => {
          setStatus(v === 'all' ? undefined : (v as 'open' | 'handled'));
          setPage(1);
        }}
        options={[
          { value: 'all', label: t('ds.all') },
          { value: 'open', label: t('ds.st.open') },
          { value: 'handled', label: t('ds.handled') },
        ]}
        style={{ marginBottom: 8 }}
      />
      <DataTable<Dispute>
        rowKey="disputeNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => {
          setPage(p);
          setSize(s);
        }}
        emptyText={t('ds.emptyRecords')}
        columns={[
          { title: t('ds.no'), dataIndex: 'disputeNo', render: (v: string) => <Num className="muted">{v}</Num> },
          { title: t('ds.market'), dataIndex: 'marketNo', render: (v: string) => <a onClick={() => onSel(v)}>{v}</a> },
          { title: t('ds.who'), dataIndex: 'userId', render: (v: number) => <Link to={`/customers/${v}`}>{v}</Link> },
          { title: t('ds.claimCol'), key: 'c', render: (_, d) => (d.claimVoid ? t('ds.claimVoid') : <Num>{d.claimedOptionNo}</Num>) },
          { title: t('ds.reason'), dataIndex: 'reason', render: (v: string) => <span className="ellipsis" style={{ maxWidth: 260 }}>{v}</span> },
          { title: t('ds.time'), dataIndex: 'receivedAt', render: (v: string) => <Num>{utc(v)}</Num> },
          { title: t('ds.status'), dataIndex: 'status', render: (v: string) => <Pill tone={v === 'open' ? 'pend' : 'muted'}>{t(`ds.st.${v}`, { defaultValue: v })}</Pill> },
        ]}
      />
    </>
  );
}

export function DisputesPage() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const view = (['disputed', 'publicizing', 'records'].includes(sp.get('view') ?? '') ? sp.get('view') : 'disputed') as View;
  const sel = sp.get('market');
  const [tick, setTick] = useState(0);
  const set = (k: string, v: string | null) => {
    const n = new URLSearchParams(sp);
    if (v) n.set(k, v);
    else n.delete(k);
    setSp(n, { replace: true });
  };
  return (
    <>
      <PageHeader title={t('ds.title')} sub={t('ds.sub')} />
      <div className="fc-bar">
        <Segmented<View>
          value={view}
          onChange={(v) => set('view', v === 'disputed' ? null : v)}
          options={[
            { value: 'disputed', label: t('ds.view.disputed') },
            { value: 'publicizing', label: t('ds.view.publicizing') },
            { value: 'records', label: t('ds.view.records') },
          ]}
        />
      </div>
      <div className="ds-layout">
        <div className="ds-left" key={`${view}-${tick}`}>
          {view === 'records' ? <Records onSel={(no) => set('market', no)} /> : <CaseList tab={view} sel={sel} onSel={(no) => set('market', no)} />}
        </div>
        <div className="ds-right">
          {sel ? (
            <DecidePanel key={sel} no={sel} onDone={() => setTick((x) => x + 1)} />
          ) : (
            <Card size="small">
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ds.pick')} />
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
