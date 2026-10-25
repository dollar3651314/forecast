/**
 * 裁决台（需求第 4.4、6.2 节，线框图 W4；后端 ResolveDeskApi / ResolveOps，S7）：
 * 左边列表（分组：全部、待提议、待确认、自动裁决失败、等待中（E6 / E7）、超时、公示中、争议中；数量来自 /api/admin/resolve/counts；超时标红）；
 * 右边选中市场：进度和时限、提议（结果、依据、实际开赛时间、依据链接、存档截图、理由）、自动裁决失败时两个来源的原始返回、
 * 按提议（或选中）结果的结算预览（派彩合计 + 抽成合计 + 尾差 = 奖池合计，守恒检查）、裁决记录。
 * 操作：提议（resolve:propose）；确认 / 退回（resolve:confirm ★；作废的提议还要 resolve:void）；直接确认（resolve:confirm ★）；
 * E6 / E7 标记等待和解除（resolve:propose）；人工作废（resolve:void ★）。一个有权限的人就能完成（RQ-17）。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, App, Button, Card, Empty, Form, Input, Radio, Select, Skeleton, Space, Table, Tag, Upload } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, LinkOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { attachmentUrl, confirmProposal, decideDirect, getCase, getCaseCounts, getEvidence, holdCase, listCases, previewSettle, propose, releaseCase, returnProposal, uploadAttachment, voidMarket } from '../../api';
import { CASE_TABS, RESULT_BASES, VOID_BASES, type CaseDetail, type CaseItem, type CaseTab, type ProposalInput, type SettlePreview } from '../../api/fcTypes';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk, useNoteDialog } from '../../hooks/useConfirmDialogs';
import { useAuth } from '../../auth/AuthContext';
import { errorText } from '../../utils/errors';
import { fromInput, minutesLeft, textOf, utc } from '../../utils/market';

function Due({ iso, overdue }: { iso: string | null; overdue?: boolean }) {
  const { t } = useTranslation();
  if (!iso) return null;
  const m = minutesLeft(iso);
  const a = Math.abs(m);
  const span = a >= 60 ? t('rs.hm', { h: Math.floor(a / 60), m: a % 60 }) : t('rs.mOnly', { m: a });
  const late = overdue ?? m < 0;
  return <span className={late ? 'rs-due late' : m < 60 ? 'rs-due soon' : 'rs-due'}>{late ? t('rs.overdueBy', { t: span }) : t('rs.dueIn', { t: span })}</span>;
}

function stageOf(c: CaseItem): string {
  if (c.hold) return 'on_hold';
  if (c.stage === 'publicizing' || c.stage === 'disputed') return c.stage;
  if (c.proposal) return 'awaiting_confirm';
  return c.origin === 'auto_failed' ? 'auto_failed' : 'awaiting_proposal';
}

export function Preview({ p }: { p: SettlePreview | null | undefined }) {
  const { t } = useTranslation();
  if (!p) return <Skeleton active paragraph={{ rows: 4 }} />;
  return (
    <div className="rs-preview" data-testid="settle-preview">
      <div className="rs-pv-head">
        <b>{t('rs.preview')}</b>
        <span className="muted">{t('rs.previewNote', { rate: p.feeRate })}</span>
      </div>
      {p.outcome !== 'settle' ? <Alert type="warning" showIcon message={t(`rs.outcome.${p.outcome}`)} style={{ marginBottom: 6 }} /> : null}
      <dl className="rs-pv">
        <div>
          <dt>{t('rs.winnerPool')}</dt>
          <dd>
            <Amount value={p.winnerPool} /> <span className="muted">· {t('rs.winners', { n: p.winnerPositions })}</span>
          </dd>
        </div>
        <div>
          <dt>{t('rs.loserPool')}</dt>
          <dd>
            <Amount value={p.loserPool} />
          </dd>
        </div>
        <div>
          <dt>{t('rs.payoutTotal')}</dt>
          <dd>
            <Amount value={p.sumPayout} strong />
          </dd>
        </div>
        <div>
          <dt>{t('rs.feeTotal')}</dt>
          <dd>
            <Amount value={p.sumFee} />
          </dd>
        </div>
        <div>
          <dt>{t('rs.dust')}</dt>
          <dd>
            <Amount value={p.dust} />
          </dd>
        </div>
        {p.lateBets > 0 ? (
          <div>
            <dt>{t('rs.late')}</dt>
            <dd>
              <Amount value={p.lateAmount} /> <span className="muted">· {t('rs.lateN', { n: p.lateBets })}</span>
            </dd>
          </div>
        ) : null}
        <div className="sum">
          <dt>{t('rs.sum')}</dt>
          <dd>
            <Amount value={p.poolTotal} strong /> {p.conserved ? <CheckCircleFilled className="ok" /> : <CloseCircleFilled className="bad" />}
            <span className={p.conserved ? 'ok' : 'bad'}>{p.conserved ? t('rs.balanced') : t('rs.unbalanced')}</span>
          </dd>
        </div>
      </dl>
    </div>
  );
}

/** 提议表单（也用于"直接确认"） */
function ProposalForm({ d, onDone }: { d: CaseDetail; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const { can } = useAuth();
  const hr = useHighRisk();
  const no = d.summary.marketNo;
  const [p, setP] = useState<ProposalInput>({ optionNo: d.options[0]?.optionNo ?? null, voidMarket: false, basis: 'NORMAL', actualStartAt: null, evidenceUrls: [], attachmentNos: [], note: '' });
  const [links, setLinks] = useState('');
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const preview = useApi(useCallback(() => (p.optionNo && !p.voidMarket ? previewSettle(no, p.optionNo, p.actualStartAt) : Promise.resolve(null)), [no, p.optionNo, p.voidMarket, p.actualStartAt]));
  const input = (): ProposalInput => ({ ...p, evidenceUrls: links.split(/\s+/).map((x) => x.trim()).filter(Boolean) });
  const submit = async () => {
    setBusy(true);
    try {
      await propose(no, input());
      void message.success(t('rs.proposed'));
      onDone();
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };
  const direct = () =>
    hr.ask({
      title: t('rs.directTitle'),
      description: p.voidMarket ? t('rs.directVoid') : t('rs.directNote', { opt: textOf(d.options.find((o) => o.optionNo === p.optionNo)?.label, i18n.language) }),
      onConfirm: (v) => decideDirect(no, input(), v.reason),
      onDone,
    });
  return (
    <section className="rs-decide" data-testid="proposal-form">
      <h3>{t('rs.proposeTitle')}</h3>
      <Radio.Group
        value={p.voidMarket ? 'void' : p.optionNo}
        onChange={(e) => (e.target.value === 'void' ? setP({ ...p, voidMarket: true, optionNo: null, basis: 'E10_UNDETERMINED' }) : setP({ ...p, voidMarket: false, optionNo: e.target.value, basis: 'NORMAL' }))}
        optionType="button"
        options={[...d.options.map((o) => ({ value: o.optionNo, label: textOf(o.label, i18n.language) })), { value: 'void', label: t('rs.voidOption') }]}
      />
      <Form layout="vertical" className="rs-form">
        <Form.Item label={t('rs.basis')}>
          <Select value={p.basis ?? undefined} onChange={(v) => setP({ ...p, basis: v })} options={(p.voidMarket ? VOID_BASES : RESULT_BASES).map((b) => ({ value: b, label: t(`rs.bases.${b}`) }))} />
        </Form.Item>
        {d.summary.category === 'FOOTBALL' || d.summary.category === 'NBA' ? (
          <Form.Item label={t('rs.actualStart')} extra={t('rs.actualStartHint')}>
            <Input type="datetime-local" suffix="UTC" onChange={(e) => setP({ ...p, actualStartAt: fromInput(e.target.value) || null })} />
          </Form.Item>
        ) : null}
        <Form.Item label={t('rs.evidence')} required extra={t('rs.evidenceHint')}>
          <Input.TextArea rows={2} value={links} onChange={(e) => setLinks(e.target.value)} placeholder="https://" />
        </Form.Item>
        <Form.Item label={t('rs.screens', { n: p.attachmentNos.length })} extra={t('rs.screensHint')}>
          <Upload
            accept="image/png,image/jpeg"
            showUploadList={false}
            disabled={!can('resolve:propose') || p.attachmentNos.length >= 5}
            beforeUpload={(file) => {
              setUploading(true);
              uploadAttachment(no, file)
                .then((a) => setP((x) => ({ ...x, attachmentNos: [...x.attachmentNos, a.attachmentNo] })))
                .catch((e) => void message.error(errorText(t, e)))
                .finally(() => setUploading(false));
              return false;
            }}
          >
            <Button icon={<UploadOutlined />} loading={uploading} disabled={!can('resolve:propose') || p.attachmentNos.length >= 5}>
              {t('rs.upload')}
            </Button>
          </Upload>
          <Space wrap style={{ marginTop: 6 }}>
            {p.attachmentNos.map((a) => (
              <Tag key={a} closable onClose={() => setP({ ...p, attachmentNos: p.attachmentNos.filter((x) => x !== a) })}>
                {a}
              </Tag>
            ))}
          </Space>
        </Form.Item>
        <Form.Item label={t('rs.note')} required extra={t('rs.noteHint')}>
          <Input.TextArea rows={2} maxLength={1000} showCount value={p.note} onChange={(e) => setP({ ...p, note: e.target.value })} />
        </Form.Item>
      </Form>
      {!p.voidMarket ? <Preview p={preview.data ?? undefined} /> : null}
      <div className="rs-submit">
        <PermButton perm="resolve:propose" type="primary" loading={busy} onClick={() => void submit()}>
          {t('rs.submitProposal')}
        </PermButton>
        <PermButton perm={p.voidMarket ? 'resolve:void' : 'resolve:confirm'} onClick={direct}>
          {t('rs.direct')}
        </PermButton>
      </div>
      {hr.element}
    </section>
  );
}

function Detail({ no, onDone }: { no: string; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;
  const fetcher = useCallback(() => getCase(no), [no]);
  const { data, error, reload } = useApi(fetcher);
  const hr = useHighRisk();
  const note = useNoteDialog();
  const auto = data?.detail.summary.origin === 'auto_failed';
  const ev = useApi(useCallback(() => (auto ? getEvidence(no) : Promise.resolve(null)), [auto, no]));
  const done = () => {
    reload();
    onDone();
  };
  if (error) return <ErrorState error={error} onRetry={reload} />;
  if (!data) return <Card loading />;
  const d = data.detail;
  const s = d.summary;
  const label = (o?: string | null) => (o ? textOf(d.options.find((x) => x.optionNo === o)?.label, lang) : t('rs.voidOption'));
  const desc = (
    <span>
      <b>{textOf(s.title, lang)}</b> <Num className="muted">{s.marketNo}</Num>
    </span>
  );
  const p = s.proposal;
  return (
    <Card
      size="small"
      className="rs-detail"
      title={
        <span className="rs-title">
          <Link to={`/markets/${s.marketNo}`}>{textOf(s.title, lang)}</Link>
        </span>
      }
      extra={<Num className="muted">{s.marketNo}</Num>}
    >
      <div className="rs-meta">
        <Tag>{t(`mk.cats.${s.category}`)}</Tag>
        <Tag color={s.origin === 'auto_failed' ? 'red' : 'blue'}>{t(`rs.origin.${s.origin}`)}</Tag>
        <Pill tone="info">{t(`rs.tab.${stageOf(s)}`)}</Pill>
        <span>
          {t('rs.eventEnd')} <Num>{utc(s.eventEndAt)}</Num>
        </span>
        {s.stage === 'open' && !p && !s.hold ? (
          <span>
            {t('rs.proposeDue')} <Due iso={s.proposeDueAt} overdue={s.proposeOverdue} />
          </span>
        ) : null}
        {s.hold ? (
          <Pill tone="warn">
            {t(`rs.hold.${s.hold}`)} · {t('rs.holdUntil', { t: utc(s.holdUntil) })}
          </Pill>
        ) : null}
      </div>
      <div className="rs-grid">
        <div className="rs-left">
          {p ? (
            <section className="rs-sec" data-testid="proposal">
              <h3>{t('rs.proposal')}</h3>
              <dl className="rs-dl">
                <div>
                  <dt>{t('rs.proposer')}</dt>
                  <dd>
                    <Num strong>{p.proposedBy}</Num> · <Num>{utc(p.proposedAt)}</Num> · <Due iso={p.confirmDueAt} overdue={p.confirmOverdue} />
                  </dd>
                </div>
                <div>
                  <dt>{t('rs.result')}</dt>
                  <dd>
                    <b>{p.outcome === 'void' ? t('rs.voidOption') : label(p.optionNo)}</b> <span className="muted">· {t(`rs.bases.${p.basis}`, { defaultValue: p.basis })}</span>
                  </dd>
                </div>
                {p.actualStartAt ? (
                  <div>
                    <dt>{t('rs.actualStart')}</dt>
                    <dd>
                      <Num>{utc(p.actualStartAt, true)}</Num>
                    </dd>
                  </div>
                ) : null}
                <div>
                  <dt>{t('rs.evidence')}</dt>
                  <dd className="rs-links">
                    {p.evidenceUrls.map((u) => (
                      <a key={u} href={u} target="_blank" rel="noreferrer noopener">
                        <LinkOutlined /> {u.replace(/^https?:\/\//, '').slice(0, 48)}
                      </a>
                    ))}
                    <span className="rs-shots">
                      {p.attachmentNos.map((a) => (
                        <a key={a} href={attachmentUrl(a)} target="_blank" rel="noreferrer noopener">
                          <img src={attachmentUrl(a)} alt={t('rs.shotAlt', { no: a })} />
                        </a>
                      ))}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt>{t('rs.note')}</dt>
                  <dd>{p.note}</dd>
                </div>
              </dl>
            </section>
          ) : s.stage === 'open' && !s.hold ? null : null}
          {auto ? (
            <section className="rs-sec">
              <h3>{t('rs.sources')}</h3>
              {ev.error ? (
                <ErrorState error={ev.error} onRetry={ev.reload} compact />
              ) : !ev.data ? (
                <Skeleton active paragraph={{ rows: 2 }} />
              ) : (
                <>
                  {ev.data.task?.lastReason ? <Alert type="warning" showIcon message={ev.data.task.lastReason} style={{ marginBottom: 6 }} /> : null}
                  {ev.data.sports?.lastReason ? <Alert type="warning" showIcon message={ev.data.sports.lastReason} style={{ marginBottom: 6 }} /> : null}
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={ev.data.fetches.length > 6 ? { pageSize: 6, size: 'small' } : false}
                    scroll={{ x: 'max-content' }}
                    dataSource={ev.data.fetches}
                    locale={{ emptyText: t('rs.noFetch') }}
                    columns={[
                      { title: t('rs.src'), dataIndex: 'source' },
                      { title: t('rs.result'), key: 'r', render: (_, f) => (f.outcome === 'OK' ? <CheckCircleFilled className="ok" /> : <span className="bad">{f.outcome}</span>) },
                      { title: t('rs.parsed'), key: 'p', render: (_, f) => <Num>{f.parsed ? JSON.stringify(f.parsed) : f.error ?? '—'}</Num> },
                      { title: t('rs.fetchedAt'), dataIndex: 'fetchedAt', render: (v: string) => <Num>{utc(v)}</Num> },
                    ]}
                  />
                </>
              )}
            </section>
          ) : null}
          <section className="rs-sec">
            <h3>{t('rs.records')}</h3>
            {d.records.length ? (
              d.records.map((r) => (
                <div key={r.id} className="mkd-log">
                  <Num className="muted">{utc(r.time)}</Num> <b>{t(`rs.kind.${r.kind}`, { defaultValue: r.kind })}</b> {r.optionNo ? `· ${label(r.optionNo)}` : ''} <span className="muted">· {r.actor}</span>
                  {r.note ? <div className="muted">{r.note}</div> : null}
                </div>
              ))
            ) : (
              <span className="muted">{t('mkd.none')}</span>
            )}
          </section>
        </div>
        <div className="rs-right">
          {p ? (
            <>
              {p.outcome === 'result' ? <Preview p={data.proposalPreview} /> : <Alert type="warning" showIcon message={t('rs.voidProposal')} />}
              <section className="rs-decide">
                <h3>{t('rs.decide')}</h3>
                <div className="rs-submit">
                  <PermButton
                    perm={p.outcome === 'void' ? 'resolve:void' : 'resolve:confirm'}
                    type="primary"
                    disabled={p.outcome === 'result' && data.proposalPreview?.conserved === false}
                    disabledReason={t('rs.unbalanced')}
                    onClick={() =>
                      hr.ask({
                        title: p.outcome === 'void' ? t('rs.confirmVoid') : t('rs.confirmTitle'),
                        description: <>{desc} · {p.outcome === 'void' ? t('rs.voidOption') : label(p.optionNo)}</>,
                        onConfirm: (v) => confirmProposal(s.marketNo, p.proposalId, v.reason),
                        onDone: done,
                      })
                    }
                  >
                    {p.outcome === 'void' ? t('rs.confirmVoid') : t('rs.confirm')}
                  </PermButton>
                  <PermButton perm="resolve:confirm" onClick={() => note.ask({ title: t('rs.returnTitle'), label: t('rs.returnReason'), onConfirm: (r) => returnProposal(s.marketNo, p.proposalId, r), onDone: done })}>
                    {t('rs.return')}
                  </PermButton>
                </div>
                <span className="muted">{t('rs.permNote')}</span>
              </section>
            </>
          ) : s.stage === 'open' && !s.hold ? (
            <ProposalForm d={d} onDone={done} />
          ) : s.stage === 'publicizing' || s.stage === 'disputed' ? (
            <Alert
              type="info"
              showIcon
              message={s.stage === 'publicizing' ? t('rs.publicizing', { opt: label(s.resultOptionNo), t: utc(s.disputeDeadlineAt) }) : t('rs.disputed', { n: s.openDisputes })}
              description={<Link to={`/resolve/disputes?market=${s.marketNo}`}>{t('rs.toDisputes')}</Link>}
            />
          ) : s.hold ? (
            <Alert type="warning" showIcon message={t('rs.onHold', { t: utc(s.holdUntil, true) })} />
          ) : null}
          <div className="rs-more">
            {s.stage === 'open' ? (
              s.hold ? (
                <PermButton perm="resolve:propose" size="small" onClick={() => note.ask({ title: t('rs.release'), label: t('rs.releaseReason'), onConfirm: (r) => releaseCase(s.marketNo, r), onDone: done })}>
                  {t('rs.release')}
                </PermButton>
              ) : (
                <>
                  <PermButton perm="resolve:propose" size="small" disabled={!!p} disabledReason={t('rs.holdNoProposal')} onClick={() => note.ask({ title: t('rs.hold.postponed'), label: t('rs.holdReason'), onConfirm: (r) => holdCase(s.marketNo, 'postponed', r), onDone: done })}>
                    {t('rs.markE6')}
                  </PermButton>
                  <PermButton perm="resolve:propose" size="small" disabled={!!p} disabledReason={t('rs.holdNoProposal')} onClick={() => note.ask({ title: t('rs.hold.abandoned'), label: t('rs.holdReason'), onConfirm: (r) => holdCase(s.marketNo, 'abandoned', r), onDone: done })}>
                    {t('rs.markE7')}
                  </PermButton>
                </>
              )
            ) : null}
            {s.stage !== 'closed' ? (
              <PermButton perm="resolve:void" size="small" danger onClick={() => hr.ask({ title: t('mkd.void'), description: <>{desc}<Alert type="warning" showIcon style={{ marginTop: 8 }} message={t('mkd.voidNote')} /></>, onConfirm: (v) => voidMarket(s.marketNo, v.reason), onDone: done })}>
                {t('mkd.void')}
              </PermButton>
            ) : null}
          </div>
        </div>
      </div>
      {hr.element}
      {note.element}
    </Card>
  );
}

export function ResolveDeskPage() {
  const { t, i18n } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const tab: CaseTab = (CASE_TABS as string[]).includes(sp.get('tab') ?? '') ? (sp.get('tab') as CaseTab) : 'all';
  const [page, setPage] = useState(1);
  const fetcher = useCallback(() => listCases(tab, page, 20), [tab, page]);
  const { data, loading, error, reload } = useApi(fetcher);
  const counts = useApi(useCallback(() => getCaseCounts(), []));
  const sel = sp.get('market');
  const items = useMemo(() => data?.items ?? [], [data]);
  const current = sel ?? items[0]?.marketNo ?? null;
  const setParam = (k: string, v: string | null) => {
    const n = new URLSearchParams(sp);
    if (v) n.set(k, v);
    else n.delete(k);
    setSp(n, { replace: true });
  };
  useEffect(() => setPage(1), [tab]);
  const refresh = () => {
    reload();
    counts.reload();
  };

  return (
    <>
      <PageHeader title={t('rs.title')} sub={t('rs.sub')} />
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : (
        <div className="rs-layout">
          <div className="rs-list">
            <div className="rs-stages">
              {CASE_TABS.map((s) => (
                <button key={s} type="button" className={`rs-stage s-${s}${tab === s ? ' on' : ''}`} onClick={() => setSp(new URLSearchParams(s === 'all' ? {} : { tab: s }), { replace: true })}>
                  <span>{t(`rs.tab.${s}`)}</span>
                  <b className="num">{counts.data ? counts.data[s] : '—'}</b>
                </button>
              ))}
            </div>
            {loading && !data ? (
              <Card size="small" loading />
            ) : items.length === 0 ? (
              <Card size="small">
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tab === 'all' ? t('rs.empty') : t('rs.emptyTab')} />
              </Card>
            ) : (
              <>
                <ul className="rs-items">
                  {items.map((c) => (
                    <li key={c.marketNo}>
                      <button type="button" className={current === c.marketNo ? 'on' : ''} onClick={() => setParam('market', c.marketNo)}>
                        <span className="rs-it-top">
                          <span className={`rs-badge s-${stageOf(c)}${c.overdue ? ' s-overdue' : ''}`}>{t(`rs.tab.${stageOf(c)}`)}</span>
                          {c.overdue ? <span className="rs-due late">{t('rs.overdue')}</span> : <Due iso={c.proposal?.confirmDueAt ?? c.proposeDueAt} />}
                        </span>
                        <b>{textOf(c.title, i18n.language)}</b>
                        <span className="muted">
                          {t(`mk.cats.${c.category}`)} · {t(`rs.origin.${c.origin}`)}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
                {data && data.total > data.pageSize ? (
                  <Space className="rs-pager">
                    <Button size="small" disabled={page <= 1} onClick={() => setPage(page - 1)}>
                      {t('common.prev')}
                    </Button>
                    <span className="muted">{t('rs.pageOf', { p: page, n: Math.ceil(data.total / data.pageSize), total: data.total })}</span>
                    <Button size="small" disabled={page * data.pageSize >= data.total} onClick={() => setPage(page + 1)}>
                      {t('common.next')}
                    </Button>
                  </Space>
                ) : null}
              </>
            )}
          </div>
          <div className="rs-main">
            {current ? (
              <Detail key={current} no={current} onDone={refresh} />
            ) : (
              <Card size="small">
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('rs.pick')} />
              </Card>
            )}
          </div>
        </div>
      )}
    </>
  );
}
