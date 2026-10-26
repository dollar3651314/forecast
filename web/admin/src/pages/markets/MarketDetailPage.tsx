/**
 * 市场详情（需求第 6.2 节"市场详情"）：基本信息和规则、各选项奖池和人数、前 20 大下注、全部下注（分页）、状态历史、费率修改记录、补充说明、结算单。
 * 操作（都在页头，C79 首屏可见；每个都是高危：原因必填、勾选确认、写审计）：
 * 暂停 / 恢复、提前截止（market:pause）；取消（market:cancel，截止前）；补充说明（market:clarify，三语）；改费率（fee:edit，结算开始前）；
 * 作废（resolve:void，待裁决 / 公示中 / 争议中）；补偿（settle:compensate，已结算：选正确结果 → 预览每个客户应补多少 → 确认）。
 * 草稿跳到编辑页。
 * S13（MF-3）：标"今日场次"；已上架、已暂停的市场可以"加入今日场次 / 移出今日场次"（market:publish，写审计）。
 */
import { useCallback, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { Alert, App, Card, Col, Descriptions, Form, Input, Radio, Row, Segmented, Skeleton, Space, Table, Tag } from 'antd';
import { AuditOutlined, CalendarOutlined, ClockCircleOutlined, MessageOutlined, PauseCircleOutlined, PercentageOutlined, PlayCircleOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { advanceClose, cancelMarket, changeFee, clarifyMarket, compensate, getMarket, getMarketBets, getSettlement, pauseMarket, previewCompensation, resumeMarket, setTodaySlate, voidMarket } from '../../api';
import type { CompPlan, I18nText, MarketDetail, MarketStatus } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { EMPTY_TEXT, I18nInputs, I18nView } from '../../components/I18nInputs';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { fromInput, statusTone, textOf, toInput, utc } from '../../utils/market';

const OPEN: MarketStatus[] = ['listed', 'paused'];
const VOIDABLE: MarketStatus[] = ['pending_resolution', 'publicizing', 'disputed'];
const FEE_LOCKED: MarketStatus[] = ['settled', 'cancelled', 'voided'];

function Bets({ no, lang, m }: { no: string; lang: string; m: MarketDetail }) {
  const { t } = useTranslation();
  const [sort, setSort] = useState<'amount' | 'time'>('amount');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const fetcher = useCallback(() => getMarketBets(no, { sort, page, pageSize: size }), [no, sort, page, size]);
  const { data, loading, error, reload } = useApi(fetcher);
  const label = (optionNo: string) => textOf(m.options.find((o) => o.optionNo === optionNo)?.label, lang);
  return (
    <Card size="small" title={t('mkd.pool')} extra={data ? <span className="muted">{data.frozen ? t('mkd.frozen') : t('mkd.live')}</span> : null}>
      {error ? (
        <ErrorState error={error} onRetry={reload} compact />
      ) : !data ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : (
        <>
          <div className="mkd-pool">
            <div>
              <span className="muted">{t('mkd.total')}</span>
              <Amount value={data.total} strong /> <small className="muted">USDT</small>
            </div>
            <div>
              <span className="muted">{t('mkd.bettors')}</span>
              <Num strong>{data.bettors}</Num>
            </div>
          </div>
          <Table
            size="small"
            rowKey="optionNo"
            pagination={false}
            scroll={{ x: 'max-content' }}
            dataSource={data.options}
            columns={[
              { title: '#', dataIndex: 'seq', width: 40 },
              { title: t('mkd.option'), key: 'l', render: (_, r) => label(r.optionNo) },
              { title: t('mkd.optionPool'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} /> },
              { title: t('mkd.people'), dataIndex: 'bettors', align: 'right', render: (v: number) => <Num>{v}</Num> },
              { title: t('mkd.final'), dataIndex: 'finalAmount', align: 'right', render: (v: string | null) => (v ? <Amount value={v} /> : <span className="muted">—</span>) },
            ]}
          />
          <div className="fc-bar fc-bar-tight">
            <b>{sort === 'amount' ? t('mkd.top') : t('mkd.allBets')}</b>
            <Segmented
              size="small"
              value={sort}
              onChange={(v) => {
                setSort(v as 'amount' | 'time');
                setPage(1);
              }}
              options={[
                { value: 'amount', label: t('mkd.byAmount') },
                { value: 'time', label: t('mkd.byTime') },
              ]}
            />
          </div>
          <DataTable
            bare
            rowKey={(r) => r.bet.betNo}
            loading={loading}
            dataSource={data.bets.items}
            total={data.bets.total}
            page={page}
            pageSize={size}
            onPageChange={(p, s) => {
              setPage(p);
              setSize(s);
            }}
            emptyText={t('mkd.noBets')}
            columns={[
              { title: t('mkd.betNo'), key: 'no', render: (_, r) => <Num className="muted">{r.bet.betNo}</Num> },
              { title: t('mkd.customer'), key: 'c', render: (_, r) => <Link to={`/customers/${r.bet.userId}`}>{r.name ?? r.email ?? r.bet.userId}</Link> },
              { title: t('mkd.option'), key: 'o', render: (_, r) => label(r.bet.optionNo) },
              { title: t('mkd.amount'), key: 'a', align: 'right', render: (_, r) => <Amount value={r.bet.amount} strong /> },
              { title: t('mkd.received'), key: 't', render: (_, r) => <Num>{utc(r.bet.receivedAt, true)}</Num> },
              { title: t('mkd.betStatus'), key: 's', render: (_, r) => (r.bet.status === 'active' ? <Pill tone="ok">{t('mkd.active')}</Pill> : <Pill tone="muted">{t('mkd.refunded', { why: r.bet.refundReason ?? '' })}</Pill>) },
            ]}
          />
        </>
      )}
    </Card>
  );
}

function Settlement({ no }: { no: string }) {
  const { t } = useTranslation();
  const { data, error, reload } = useApi(useCallback(() => getSettlement(no), [no]));
  if (error) return <ErrorState error={error} onRetry={reload} compact />;
  if (!data) return <Skeleton active paragraph={{ rows: 2 }} />;
  const run = data.payout ?? data.refund;
  if (!run) return <p className="muted">{t('mkd.noRun')}</p>;
  return (
    <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
      <Descriptions.Item label={t('sr.runNo')}>
        <Link to={`/settle/runs?run=${run.runNo}`}>{run.runNo}</Link>
      </Descriptions.Item>
      <Descriptions.Item label={t('sr.status')}>
        <Pill tone={run.status === 'abnormal' ? 'danger' : run.status === 'done' ? 'ok' : 'pend'}>{t(`sr.st.${run.status}`)}</Pill>
      </Descriptions.Item>
      <Descriptions.Item label={t('sr.pool')}>
        <Amount value={run.poolTotal} />
      </Descriptions.Item>
      <Descriptions.Item label={t('sr.payout')}>
        <Amount value={run.sumPayout} />
      </Descriptions.Item>
      <Descriptions.Item label={t('sr.fee')}>
        <Amount value={run.sumFee} />
      </Descriptions.Item>
      <Descriptions.Item label={t('sr.dust')}>
        <Amount value={run.dust} />
      </Descriptions.Item>
    </Descriptions>
  );
}

export function MarketDetailPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { no = '' } = useParams();
  const fetcher = useCallback(() => getMarket(no), [no]);
  const { data: m, error, reload } = useApi(fetcher);
  const hr = useHighRisk();
  const lang = i18n.language;
  const { message } = App.useApp();
  const [slateBusy, setSlateBusy] = useState(false);

  if (error) return <ErrorState error={error} onRetry={reload} />;
  if (!m) return <Card loading />;
  if (m.status === 'draft') return <Navigate to={`/markets/${no}/edit`} replace />;
  const title = textOf(m.title, lang);
  const desc = (
    <span>
      <b>{title}</b> <Num className="muted">{m.publicNo}</Num>
    </span>
  );

  const pause = () =>
    hr.ask({ title: m.status === 'paused' ? t('mkd.resume') : t('mkd.pause'), description: desc, onConfirm: (v) => (m.status === 'paused' ? resumeMarket(no, v.reason) : pauseMarket(no, v.reason)), onDone: reload });
  const cancel = () => hr.ask({ title: t('mkd.cancel'), description: <>{desc}<Alert type="warning" showIcon style={{ marginTop: 8 }} message={t('mkd.cancelNote')} /></>, onConfirm: (v) => cancelMarket(no, v.reason), onDone: reload });
  const close = () =>
    hr.ask({
      title: t('mkd.advance'),
      description: <>{desc} · {t('mkd.closeNow', { t: utc(m.closeAt, true) })}</>,
      initialValues: { closeAt: toInput(m.closeAt) },
      extra: (
        <Form.Item name="closeAt" label={t('mkd.newClose')} extra={t('mkd.advanceNote')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input type="datetime-local" suffix="UTC" />
        </Form.Item>
      ),
      onConfirm: (v) => advanceClose(no, fromInput(String(v.closeAt)), v.reason),
      onDone: reload,
    });
  const fee = () =>
    hr.ask({
      title: t('mkd.fee'),
      description: <>{desc} · {t('mkd.feeNow', { rate: m.feeRate })}</>,
      initialValues: { feeRate: m.feeRate },
      extra: (
        <Form.Item name="feeRate" label={t('mkd.newFee')} extra={t('mkd.feeNote')} rules={[{ required: true, message: t('validation.required') }, { pattern: /^\d{1,2}(\.\d{1,2})?$/, message: t('pm.feeHint') }]}>
          <Input suffix="%" />
        </Form.Item>
      ),
      onConfirm: (v) => changeFee(no, String(v.feeRate).trim(), v.reason),
      onDone: reload,
    });
  const clarify = () => {
    let text: I18nText = { ...EMPTY_TEXT };
    hr.ask({
      title: t('mkd.clarify'),
      description: <>{desc}<p className="muted">{t('mkd.clarifyNote')}</p></>,
      extra: (
        <Form.Item label={t('mkd.clarifyText')} required>
          <ClarifyInputs onChange={(v) => (text = v)} />
        </Form.Item>
      ),
      onConfirm: (v) => clarifyMarket(no, text, v.reason),
      onDone: reload,
    });
  };
  const doVoid = () => hr.ask({ title: t('mkd.void'), description: <>{desc}<Alert type="warning" showIcon style={{ marginTop: 8 }} message={t('mkd.voidNote')} /></>, onConfirm: (v) => voidMarket(no, v.reason), onDone: reload });
  const comp = () =>
    hr.ask({
      title: t('mkd.compensate'),
      description: <>{desc}<p className="muted">{t('mkd.compNote')}</p></>,
      extra: (
        <Form.Item name="correct" label={t('mkd.correct')} rules={[{ required: true, message: t('validation.required') }]}>
          <CompPicker no={no} options={[...m.options.map((o) => ({ value: o.optionNo, label: textOf(o.label, lang) })), { value: 'void', label: t('mkd.shouldVoid') }]} />
        </Form.Item>
      ),
      onConfirm: (v) => compensate(no, String(v.correct), v.reason),
      onDone: reload,
    });

  const toggleSlate = async () => {
    setSlateBusy(true);
    try {
      await setTodaySlate(no, !m.todaySlate);
      void message.success(m.todaySlate ? t('mkd.slateOff') : t('mkd.slateOn'));
      reload();
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setSlateBusy(false);
    }
  };

  const isOpen = OPEN.includes(m.status);
  return (
    <>
      <PageHeader
        title={title}
        sub={
          <Space size={6} wrap>
            <Num>{m.publicNo}</Num>
            <Tag>{t(`mk.cats.${m.category}`)}</Tag>
            <Pill tone={statusTone(m.status)}>{t(`mk.status.${m.status}`)}</Pill>
            {m.resolutionMode ? <Tag>{t(`mk.mode.${m.resolutionMode.toLowerCase()}`)}</Tag> : null}
            {m.todaySlate ? <Tag color="gold">{t('mk.slateTag')}</Tag> : null}
          </Space>
        }
        extra={
          <>
            {isOpen ? (
              <PermButton perm="market:pause" icon={m.status === 'paused' ? <PlayCircleOutlined /> : <PauseCircleOutlined />} onClick={pause}>
                {m.status === 'paused' ? t('mkd.resume') : t('mkd.pause')}
              </PermButton>
            ) : null}
            {isOpen ? (
              <PermButton perm="market:pause" icon={<ClockCircleOutlined />} onClick={close}>
                {t('mkd.advance')}
              </PermButton>
            ) : null}
            {isOpen ? (
              <PermButton perm="market:publish" icon={<CalendarOutlined />} loading={slateBusy} onClick={() => void toggleSlate()}>
                {m.todaySlate ? t('mkd.slateRemove') : t('mkd.slateAdd')}
              </PermButton>
            ) : null}
            {isOpen ? (
              <PermButton perm="market:clarify" icon={<MessageOutlined />} onClick={clarify}>
                {t('mkd.clarify')}
              </PermButton>
            ) : null}
            {!FEE_LOCKED.includes(m.status) && m.settlePhase === 'NONE' ? (
              <PermButton perm="fee:edit" icon={<PercentageOutlined />} onClick={fee}>
                {t('mkd.fee')}
              </PermButton>
            ) : null}
            {isOpen ? (
              <PermButton perm="market:cancel" danger icon={<StopOutlined />} onClick={cancel}>
                {t('mkd.cancel')}
              </PermButton>
            ) : null}
            {VOIDABLE.includes(m.status) ? (
              <PermButton perm="resolve:propose" type="primary" icon={<AuditOutlined />} onClick={() => navigate(`/resolve?market=${no}`)}>
                {t('mk.toResolve')}
              </PermButton>
            ) : null}
            {VOIDABLE.includes(m.status) ? (
              <PermButton perm="resolve:void" danger onClick={doVoid}>
                {t('mkd.void')}
              </PermButton>
            ) : null}
            {m.status === 'settled' ? (
              <PermButton perm="settle:compensate" onClick={comp}>
                {t('mkd.compensate')}
              </PermButton>
            ) : null}
          </>
        }
      />
      <Row gutter={[12, 12]} className="mkd-grid">
        <Col xs={24} xl={14}>
          <Bets no={no} lang={lang} m={m} />
        </Col>
        <Col xs={24} xl={10}>
          <Card size="small" title={t('mkd.info')}>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label={t('mke.time.openAt')}>
                <Num>{utc(m.openAt, true)}</Num>
              </Descriptions.Item>
              <Descriptions.Item label={t('mke.time.closeAt')}>
                <Num>{utc(m.closeAt, true)}</Num>
              </Descriptions.Item>
              <Descriptions.Item label={t('mke.time.eventStartAt')}>
                <Num>{utc(m.eventStartAt, true)}</Num>
              </Descriptions.Item>
              <Descriptions.Item label={t('mke.time.eventEndAt')}>
                <Num>{utc(m.eventEndAt, true)}</Num>
              </Descriptions.Item>
              <Descriptions.Item label={t('mkd.feeRate')}>
                <Num>{m.feeRate}%</Num>
              </Descriptions.Item>
              <Descriptions.Item label={t('mke.userCap')}>
                <Amount value={m.userCap} /> USDT
              </Descriptions.Item>
              <Descriptions.Item label={t('mke.sourcePrimary')}>{m.sourcePrimary}</Descriptions.Item>
              <Descriptions.Item label={t('mke.sourceBackup')}>{m.sourceBackup}</Descriptions.Item>
              {m.disputeWindowMinutes ? <Descriptions.Item label={t('mke.disputeWindow')}>{t('pm.minutes', { n: m.disputeWindowMinutes })}</Descriptions.Item> : null}
              {m.cancelReason ? <Descriptions.Item label={t('mkd.cancelReason')}>{m.cancelReason}</Descriptions.Item> : null}
              {m.voidReason ? <Descriptions.Item label={t('mkd.voidReason')}>{t(`mkd.voidWhy.${m.voidReason}`, { defaultValue: m.voidReason })}</Descriptions.Item> : null}
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} xl={14}>
          <Card size="small" title={t('mkd.options')}>
            {m.options.map((o) => (
              <div key={o.optionNo} className="mkd-opt">
                <b>
                  {o.seq}. {textOf(o.label, lang)}
                </b>
                <span className="muted">{textOf(o.winCondition, lang)}</span>
              </div>
            ))}
          </Card>
          <Card size="small" title={t('mkd.rules')} style={{ marginTop: 12 }}>
            <I18nView value={m.rules} />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          {['settled', 'cancelled', 'voided'].includes(m.status) ? (
            <Card size="small" title={t('mkd.settlement')} style={{ marginBottom: 12 }}>
              <Settlement no={no} />
            </Card>
          ) : null}
          <Card size="small" title={t('mkd.clarifications', { n: m.clarifications.length })} style={{ marginBottom: 12 }}>
            {m.clarifications.length ? (
              m.clarifications.map((c, i) => (
                <div key={i} className="mkd-log">
                  <Num className="muted">{utc(c.time, true)}</Num> {textOf(c.text, lang)}
                </div>
              ))
            ) : (
              <span className="muted">{t('mkd.none')}</span>
            )}
          </Card>
          <Card size="small" title={t('mkd.feeChanges', { n: m.feeChanges.length })} style={{ marginBottom: 12 }}>
            {m.feeChanges.length ? (
              m.feeChanges.map((c, i) => (
                <div key={i} className="mkd-log">
                  <Num className="muted">{utc(c.time, true)}</Num> {c.oldRate}% → <b>{c.newRate}%</b> · {c.reason} {c.notified ? <Tag>{t('mkd.notified')}</Tag> : null}
                </div>
              ))
            ) : (
              <span className="muted">{t('mkd.none')}</span>
            )}
          </Card>
          <Card size="small" title={t('mkd.statusLog')}>
            {m.statusLog.map((s, i) => (
              <div key={i} className="mkd-log">
                <Num className="muted">{utc(s.time, true)}</Num> {s.from ? t(`mk.status.${s.from.toLowerCase()}`, { defaultValue: s.from }) : '—'} → <b>{t(`mk.status.${s.to.toLowerCase()}`, { defaultValue: s.to })}</b>{' '}
                <span className="muted">
                  · {s.actor}
                  {s.reason ? ` · ${s.reason}` : ''}
                </span>
              </div>
            ))}
          </Card>
        </Col>
      </Row>
      {hr.element}
    </>
  );
}

/** 补充说明的三语输入（放在高危确认弹窗里；值通过回调交给父组件） */
function ClarifyInputs({ onChange }: { onChange: (v: I18nText) => void }) {
  const { t } = useTranslation();
  const [v, setV] = useState<I18nText>({ ...EMPTY_TEXT });
  return (
    <I18nInputs
      multiline
      rows={2}
      value={v}
      placeholder={t('mkd.clarifyText')}
      onChange={(x) => {
        setV(x);
        onChange(x);
      }}
    />
  );
}

/** 补偿：选正确结果 → 马上预览每个客户应补多少（只补不扣，E13） */
function CompPicker({ no, options, value, onChange }: { no: string; options: Array<{ value: string; label: string }>; value?: string; onChange?: (v: string) => void }) {
  const { t } = useTranslation();
  const [plan, setPlan] = useState<CompPlan | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  return (
    <div>
      <Radio.Group
        value={value}
        options={options}
        onChange={(e) => {
          onChange?.(e.target.value);
          setPlan(null);
          setErr(null);
          setLoading(true);
          previewCompensation(no, e.target.value)
            .then(setPlan, setErr)
            .finally(() => setLoading(false));
        }}
      />
      {loading ? <Skeleton active paragraph={{ rows: 1 }} /> : null}
      {err ? <Alert type="error" showIcon style={{ marginTop: 8 }} message={errorText(t, err)} /> : null}
      {plan ? (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 8 }}
          data-testid="comp-plan"
          message={t('mkd.compPlan', { total: plan.total, n: plan.userCount })}
          description={
            plan.lines.length ? (
              <Table
                size="small"
                rowKey="userId"
                pagination={plan.lines.length > 5 ? { pageSize: 5, size: 'small' } : false}
                dataSource={plan.lines}
                columns={[
                  { title: t('mkd.customer'), dataIndex: 'userId' },
                  { title: t('mkd.should'), dataIndex: 'shouldPayout', align: 'right', render: (v: string) => <Amount value={v} /> },
                  { title: t('mkd.actual'), dataIndex: 'actualPayout', align: 'right', render: (v: string) => <Amount value={v} /> },
                  { title: t('mkd.topUp'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} strong /> },
                ]}
              />
            ) : (
              t('mkd.compNothing')
            )
          }
        />
      ) : null}
    </div>
  );
}
