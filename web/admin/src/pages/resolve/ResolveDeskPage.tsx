/**
 * 裁决台（需求第 4.4、6.2 节，线框图 W4）：左边待处理列表（待提议、待确认、自动裁决失败、超时，超时标红），
 * 右边选中市场的提议详情（提议人编号、结果、依据链接、存档截图、实际开赛时间、两个来源的原始数据、规则摘录）、
 * 按结果的结算预览（派彩合计 + 抽成合计 + 尾差 = 奖池合计，守恒检查）和操作：确认（进入公示）/ 改成别的结果 / 退回提议 / 作废（高危，原因必填）。
 * 一个有 resolve:confirm 的人确认即可（RQ-17）。M1 原型：数据来自 mocks/markets.ts；接口在 S7。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, App, Card, Checkbox, Empty, Input, Radio, Space, Tag } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, LinkOutlined, PictureOutlined, WarningFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { decideResolve, listResolveQueue, previewSettle } from '../../api';
import type { ResolveCase, ResolveStage, SettlePreview } from '../../api/types';
import { ErrorState } from '../../components/ErrorState';
import { Amount, Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { useApi } from '../../hooks/useApi';
import { errorText } from '../../utils/errors';
import { minutesLeft, textOf, utc } from '../../utils/market';

const STAGES: ResolveStage[] = ['toPropose', 'toConfirm', 'autoFailed', 'overdue'];
type Action = 'confirm' | 'other' | 'return' | 'void';

function Due({ c }: { c: ResolveCase }) {
  const { t } = useTranslation();
  const m = minutesLeft(c.dueAt);
  const a = Math.abs(m);
  const span = a >= 60 ? t('rs.hm', { h: Math.floor(a / 60), m: a % 60 }) : t('rs.mOnly', { m: a });
  const txt = m < 0 ? t('rs.overdueBy', { t: span }) : t('rs.dueIn', { t: span });
  return <span className={m < 0 ? 'rs-due late' : m < 60 ? 'rs-due soon' : 'rs-due'}>{txt}</span>;
}

function Preview({ c, optionNo }: { c: ResolveCase; optionNo: string }) {
  const { t } = useTranslation();
  const fetcher = useCallback(() => previewSettle(c.publicNo, optionNo), [c.publicNo, optionNo]);
  const { data } = useApi<SettlePreview>(fetcher);
  if (!data) return <Card size="small" loading />;
  return (
    <div className="rs-preview">
      <div className="rs-pv-head">
        <b>{t('rs.preview')}</b>
        <span className="muted">{t('rs.previewNote', { rate: c.feeRate })}</span>
      </div>
      <dl className="rs-pv">
        <div>
          <dt>{t('rs.winnerPool')}</dt>
          <dd>
            <Amount value={data.winnerPool} /> <span className="muted">· {t('rs.winners', { n: data.winners })}</span>
          </dd>
        </div>
        <div>
          <dt>{t('rs.loserPool')}</dt>
          <dd>
            <Amount value={data.loserPool} />
          </dd>
        </div>
        <div>
          <dt>{t('rs.payoutTotal')}</dt>
          <dd>
            <Amount value={data.payoutTotal} strong />
          </dd>
        </div>
        <div>
          <dt>{t('rs.feeTotal')}</dt>
          <dd>
            <Amount value={data.feeTotal} />
          </dd>
        </div>
        <div>
          <dt>{t('rs.dust')}</dt>
          <dd>
            <Amount value={data.dust} />
          </dd>
        </div>
        <div className="sum">
          <dt>{t('rs.sum')}</dt>
          <dd>
            <Amount value={data.total} strong /> {data.balanced ? <CheckCircleFilled className="ok" /> : <CloseCircleFilled className="bad" />}
            <span className={data.balanced ? 'ok' : 'bad'}>{data.balanced ? t('rs.balanced') : t('rs.unbalanced')}</span>
          </dd>
        </div>
      </dl>
    </div>
  );
}

function Detail({ c, onDone }: { c: ResolveCase; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const lang = i18n.language;
  const proposed = c.proposal?.optionNo;
  const [action, setAction] = useState<Action>(proposed ? 'confirm' : 'other');
  const [pick, setPick] = useState<string>(proposed ?? c.options[0].optionNo);
  const [reason, setReason] = useState('');
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setAction(c.proposal ? 'confirm' : 'other');
    setPick(c.proposal?.optionNo ?? c.options[0].optionNo);
    setReason('');
    setAck(false);
  }, [c]);
  const chosen = action === 'confirm' ? proposed! : pick;
  const needAck = action === 'void' || action === 'other' || action === 'confirm';
  const submit = async () => {
    setBusy(true);
    try {
      await decideResolve(c.publicNo, action === 'other' ? 'confirm' : action, action === 'return' || action === 'void' ? null : chosen, reason);
      void message.success(t('rs.done'));
      onDone();
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };
  const label = (no?: string) => textOf(c.options.find((o) => o.optionNo === no)?.label, lang);

  return (
    <Card size="small" className="rs-detail" title={<span className="rs-title">{textOf(c.title, lang)}</span>} extra={<Num className="muted">{c.publicNo}</Num>}>
      <div className="rs-meta">
        <Tag color={c.mode === 'AUTO' ? 'blue' : 'purple'}>{t(`mk.mode.${c.mode}`)}</Tag>
        <span>
          {t('rs.eventEnd')} <Num>{utc(c.eventEndAt)}</Num>
        </span>
        <span>
          {t('rs.pool')} <Amount value={c.pool} strong /> · {t('rs.people', { n: c.bettors })}
        </span>
        <Due c={c} />
      </div>
      <div className="rs-grid">
        <div className="rs-left">
          {c.proposal ? (
            <section className="rs-sec">
              <h3>{t('rs.proposal')}</h3>
              <dl className="rs-dl">
                <div>
                  <dt>{t('rs.proposer')}</dt>
                  <dd>
                    <Num strong>{c.proposal.by}</Num> · <Num>{utc(c.proposal.at)}</Num>
                  </dd>
                </div>
                <div>
                  <dt>{t('rs.result')}</dt>
                  <dd>
                    <b>{label(c.proposal.optionNo)}</b>
                    {c.proposal.score && <span className="muted">（{c.proposal.score}）</span>}
                  </dd>
                </div>
                {c.proposal.actualStartAt && (
                  <div>
                    <dt>{t('rs.actualStart')}</dt>
                    <dd>
                      <Num>{utc(c.proposal.actualStartAt)}</Num>
                    </dd>
                  </div>
                )}
                <div>
                  <dt>{t('rs.evidence')}</dt>
                  <dd className="rs-links">
                    {c.proposal.evidence.map((u) => (
                      <a key={u} href={u} target="_blank" rel="noreferrer noopener">
                        <LinkOutlined /> {u.replace(/^https?:\/\//, '').slice(0, 38)}
                      </a>
                    ))}
                    <span className="muted">
                      <PictureOutlined /> {t('rs.screens', { n: c.proposal.screenshots })}
                    </span>
                  </dd>
                </div>
                <div>
                  <dt>{t('rs.note')}</dt>
                  <dd>{c.proposal.note}</dd>
                </div>
              </dl>
            </section>
          ) : (
            <Alert type="info" showIcon message={t('rs.noProposal')} />
          )}
          {c.sources && (
            <section className="rs-sec">
              <h3>{t('rs.sources')}</h3>
              <table className="rs-src">
                <tbody>
                  {c.sources.map((s) => (
                    <tr key={s.name}>
                      <td>{s.ok ? <CheckCircleFilled className="ok" /> : <WarningFilled className="bad" />}</td>
                      <td>{s.name}</td>
                      <td className="num">{s.value}</td>
                      <td className="num muted">{utc(s.fetchedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
          <section className="rs-sec">
            <h3>{t('rs.rules')}</h3>
            <p className="rs-rules">{textOf(c.rulesExcerpt, lang)}</p>
          </section>
        </div>
        <div className="rs-right">
          <Preview c={c} optionNo={chosen} />
          <section className="rs-decide">
            <h3>{t('rs.decide')}</h3>
            <Radio.Group value={action} onChange={(e) => setAction(e.target.value)} className="rs-actions">
              <Space direction="vertical" size={4}>
                {proposed && <Radio value="confirm">{t('rs.act.confirm', { opt: label(proposed), h: Math.round(c.disputeWindowMinutes / 60) })}</Radio>}
                <Radio value="other">
                  {proposed ? t('rs.act.other') : t('rs.act.self')}
                  {action === 'other' && (
                    <Radio.Group size="small" className="rs-pick" value={pick} onChange={(e) => setPick(e.target.value)} optionType="button" options={c.options.map((o) => ({ value: o.optionNo, label: textOf(o.label, lang) }))} />
                  )}
                </Radio>
                {proposed && <Radio value="return">{t('rs.act.return')}</Radio>}
                <Radio value="void">{t('rs.act.void')}</Radio>
              </Space>
            </Radio.Group>
            <Input.TextArea rows={2} maxLength={200} showCount value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('rs.reasonPh')} status={reason.trim() ? undefined : 'warning'} />
            {needAck && (
              <Checkbox checked={ack} onChange={(e) => setAck(e.target.checked)}>
                {t('rs.ack')}
              </Checkbox>
            )}
            <div className="rs-submit">
              <PermButton perm={action === 'void' ? 'resolve:void' : 'resolve:confirm'} type="primary" danger={action === 'void'} loading={busy} disabled={!reason.trim() || (needAck && !ack)} onClick={() => void submit()}>
                {t(`rs.submit.${action}`)}
              </PermButton>
              <span className="muted">{t('rs.permNote')}</span>
            </div>
          </section>
        </div>
      </div>
    </Card>
  );
}

export function ResolveDeskPage() {
  const { t, i18n } = useTranslation();
  const fetcher = useCallback(() => listResolveQueue(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const [stage, setStage] = useState<ResolveStage | 'all'>('all');
  const [sel, setSel] = useState<string | null>(null);
  const items = useMemo(() => (data?.items ?? []).filter((c) => stage === 'all' || c.stage === stage), [data, stage]);
  const current = items.find((c) => c.publicNo === sel) ?? items.find((c) => c.stage === 'toConfirm') ?? items[0];

  return (
    <>
      <PageHeader title={t('rs.title')} sub={t('rs.sub')} />
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : (
        <div className="rs-layout">
          <div className="rs-list">
            <div className="rs-stages">
              {STAGES.map((s) => (
                <button key={s} type="button" className={`rs-stage s-${s}${stage === s ? ' on' : ''}`} onClick={() => setStage(stage === s ? 'all' : s)}>
                  <span>{t(`rs.stage.${s}`)}</span>
                  <b className="num">{data?.summary[s] ?? '—'}</b>
                </button>
              ))}
            </div>
            {loading && !data ? (
              <Card size="small" loading />
            ) : items.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('rs.empty')} />
            ) : (
              <ul className="rs-items">
                {items.map((c) => (
                  <li key={c.publicNo}>
                    <button type="button" className={current?.publicNo === c.publicNo ? 'on' : ''} onClick={() => setSel(c.publicNo)}>
                      <span className="rs-it-top">
                        <span className={`rs-badge s-${c.stage}`}>{t(`rs.stage.${c.stage}`)}</span>
                        <Due c={c} />
                      </span>
                      <b>{textOf(c.title, i18n.language)}</b>
                      <span className="muted">
                        {t(`mk.cats.${c.category}`)} · {t(`mk.mode.${c.mode}`)} · <Amount value={c.pool} /> USDT
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="rs-main">{current ? <Detail key={current.publicNo} c={current} onDone={reload} /> : <Card size="small"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('rs.empty')} /></Card>}</div>
        </div>
      )}
    </>
  );
}
