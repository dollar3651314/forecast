/**
 * v2 提现自动审批（技术方案/v2-提现自动审批.md 第 9 节）：出金审核列表的"规则判定"单元格、详情里的"规则判定"卡片。
 * 判定记录里的数值和阈值是判定时刻的快照（后端 wallet_withdraw_rule_check.results_json），这里只负责按规则的模板显示，不重新计算。
 * 每条规则的数值键见后端 wallet/withdraw/AutoApproveEngine.java（例如 R1 是 amount、limit）。
 */
import type { ReactNode } from 'react';
import { Tooltip } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, MinusCircleOutlined, QuestionCircleFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Link } from 'react-router-dom';
import type { AutoReviewDetail, AutoReviewSummary, AutoRuleCode, AutoRuleResult } from '../../api/types';
import { Pill } from '../../components/Pill';
import { formatAmount } from '../../utils/money';
import { shortTime } from '../../utils/display';

export const AUTO_RULES_PATH = '/funds/withdrawals/auto-rules';

/** 金额去掉末尾的 .00：800 > 500 比 800.00 > 500.00 好读 */
export function fmtUsdt(v: string | undefined): string {
  return formatAmount(v).replace(/\.00$/, '');
}

// forecast S10：R13 的组成改成 payout / compensation / stake / fee（复制源的 realized、swap 不再有）；R14 的 limit 是金额
const AMOUNT_KEYS = new Set(['amount', 'used', 'total', 'volume', 'deposit', 'withdrawn', 'pnl', 'cap', 'profit', 'limitAbs', 'principal', 'balance', 'need', 'net', 'payout', 'compensation', 'stake', 'fee']);
const AMOUNT_LIMIT: AutoRuleCode[] = ['R1', 'R2', 'R3', 'R13', 'R14'];

/** 一条规则的判定说明，例如"单笔 800 > 上限 500" */
export function ruleLine(t: TFunction, r: AutoRuleResult): string {
  if (r.status === 'disabled') return t('autoReview.disabledLine');
  const v: Record<string, string> = {};
  for (const [k, raw] of Object.entries(r.values)) v[k] = AMOUNT_KEYS.has(k) || (k === 'limit' && AMOUNT_LIMIT.includes(r.code)) ? fmtUsdt(raw) : raw;
  if (r.code === 'R6' && r.values.event) v.event = t(`autoRule.event.${r.values.event}`);
  if (r.code === 'R7' && r.values.kyc) v.kyc = t(`kycStatus.${r.values.kyc}`);
  if (r.status === 'unknown') return t('autoReview.unknownLine', { why: r.values.why ?? '—' });
  // R8：近 30 天没有充值时通过、不检查
  if (r.code === 'R8' && r.values.note === 'noDeposit') return t('autoRule.R8.noDeposit');
  if (r.code === 'R12' && r.status === 'failed') {
    return (r.values.issues ?? '')
      .split(',')
      .filter(Boolean)
      .map((x) => t(`autoRule.R12.issue.${x}`, v))
      .join(t('autoReview.sep'));
  }
  return t(`autoRule.${r.code}.${r.status === 'passed' ? 'pass' : 'fail'}`, v);
}

const ruleName = (t: TFunction, code: AutoRuleCode) => t(`autoRule.${code}.name`);

/** 列表里的"规则判定"单元格 */
export function AutoReviewCell({ review }: { review: AutoReviewSummary | null | undefined }) {
  const { t } = useTranslation();
  if (!review) {
    return (
      <span className="muted" title={t('autoReview.noRecord')}>
        —
      </span>
    );
  }
  const n = review.failed.length;
  const pre = review.phase === 'precheck';
  const codes = n ? (
    <small className="ar-codes num" aria-hidden>
      {review.failed.join(' · ')}
    </small>
  ) : null;
  const tip = n ? (
    <div className="ar-tip">
      {pre ? <div className="ar-tip-h">{t('autoReview.precheckTip')}</div> : null}
      {review.failed.map((c) => (
        <div key={c}>
          {c} {ruleName(t, c)}
        </div>
      ))}
    </div>
  ) : pre ? (
    t('autoReview.precheckTip')
  ) : undefined;

  let pill: ReactNode;
  if (review.outcome === 'master_off') {
    pill = (
      <>
        <Pill tone="muted">{t('autoReview.masterOffShort')}</Pill>
        <small className="ar-codes">{n ? t('autoReview.ifOnTrigger', { count: n }) : t('autoReview.ifOnPass')}</small>
      </>
    );
  } else if (n === 0) {
    pill = <Pill tone={pre ? 'info' : 'ok'}>{pre ? t('autoReview.prePass') : t('autoReview.outcome.auto_approved')}</Pill>;
  } else {
    pill = (
      <>
        <Pill tone={pre ? 'warn' : 'danger'}>{pre ? t('autoReview.preTrigger', { count: n }) : t('autoReview.trigger', { count: n })}</Pill>
        {codes}
      </>
    );
  }
  const label = n ? `${t('autoReview.trigger', { count: n })}：${review.failed.map((c) => `${c} ${ruleName(t, c)}`).join('、')}` : undefined;
  return (
    <Tooltip title={tip}>
      <span className="ar-cell" aria-label={label}>
        {pill}
      </span>
    </Tooltip>
  );
}

const ORDER: Record<AutoRuleResult['status'], number> = { failed: 0, unknown: 0, passed: 1, disabled: 2 };

/** 详情里的"规则判定"卡片：未通过的标红放在最上面，然后是通过的、未启用的 */
export function AutoReviewCard({ detail }: { detail: AutoReviewDetail | null | undefined }) {
  const { t } = useTranslation();
  if (!detail) {
    return (
      <section className="ar-card" aria-label={t('autoReview.title')}>
        <div className="ar-head">
          <b>{t('autoReview.title')}</b>
        </div>
        <div className="ar-meta">{t('autoReview.noRecordLong')}</div>
      </section>
    );
  }
  const rules = [...detail.rules].sort((a, b) => ORDER[a.status] - ORDER[b.status]);
  const n = detail.failed.length;
  const passed = detail.rules.filter((r) => r.status === 'passed').length;
  const enabled = detail.rules.filter((r) => r.status !== 'disabled').length;
  const pre = detail.phase === 'precheck';

  let banner: ReactNode = null;
  if (!detail.masterEnabled) {
    banner = (
      <div className="ar-banner muted" role="note">
        <b>{t('autoReview.masterOffBanner')}</b>
        <div>{n ? t('autoReview.masterOffWouldTrigger', { count: n, codes: detail.failed.join('、') }) : t('autoReview.masterOffWouldPass')}</div>
      </div>
    );
  } else if (pre) {
    banner = (
      <div className="ar-banner info" role="note">
        <b>{n ? t('autoReview.preTrigger', { count: n }) : t('autoReview.prePass')}</b>
        <div>{t('autoReview.precheckNote')}</div>
      </div>
    );
  } else if (detail.outcome === 'auto_approved') {
    banner = (
      <div className="ar-banner ok" role="note">
        <b>{t('autoReview.autoBanner', { passed, enabled })}</b>
        <div>{t('autoReview.autoBannerSub')}</div>
      </div>
    );
  } else {
    banner = (
      <div className="ar-banner danger" role="note">
        <b>{t('autoReview.manualBanner', { count: n })}</b>
        <div>{t('autoReview.manualBannerSub')}</div>
      </div>
    );
  }

  const outcomePill =
    detail.outcome === 'master_off' ? (
      <Pill tone="muted">{t('autoReview.masterOffShort')}</Pill>
    ) : n === 0 ? (
      <Pill tone={pre ? 'info' : 'ok'}>{pre ? t('autoReview.prePass') : t('autoReview.outcome.auto_approved')}</Pill>
    ) : (
      <Pill tone={pre ? 'warn' : 'danger'}>{pre ? t('autoReview.preTrigger', { count: n }) : t('autoReview.trigger', { count: n })}</Pill>
    );

  const preLine =
    detail.precheck && !pre
      ? detail.precheck.failed.join(',') === detail.failed.join(',')
        ? null
        : t('autoReview.precheckDiff', {
            result: detail.precheck.failed.length ? `${t('autoReview.trigger', { count: detail.precheck.failed.length })}（${detail.precheck.failed.join('、')}）` : t('autoReview.prePassShort'),
          })
      : null;

  return (
    <section className="ar-card" aria-label={t('autoReview.title')}>
      <div className="ar-head">
        <b>{t('autoReview.title')}</b>
        {outcomePill}
      </div>
      <div className="ar-meta">
        {pre ? t('autoReview.phasePre') : t('autoReview.phaseFinal')} · <span className="num">{shortTime(detail.decidedAt)}</span>
        <br />
        {t('autoReview.configLine', { version: detail.configVersion, by: detail.configChangedBy ?? '—', time: shortTime(detail.configChangedAt) })}
      </div>
      {banner}
      {preLine ? <div className="ar-pre">{preLine}</div> : null}
      <ul className="ar-list">
        {rules.map((r) => (
          <li key={r.code} className={`ar-row ${r.status}`}>
            <span className="ar-ico" aria-hidden>
              {r.status === 'failed' ? <CloseCircleFilled /> : r.status === 'unknown' ? <QuestionCircleFilled /> : r.status === 'passed' ? <CheckCircleFilled /> : <MinusCircleOutlined />}
            </span>
            <span className="ar-code num">{r.code}</span>
            <span className="ar-body">
              <span className="ar-name">
                {ruleName(t, r.code)}
                <span className="sr-only"> · {t(`autoReview.status.${r.status}`)}</span>
              </span>
              <span className="ar-val">{ruleLine(t, r)}</span>
            </span>
          </li>
        ))}
      </ul>
      <div className="ar-foot">
        {t('autoReview.snapshotNote')} <Link to={AUTO_RULES_PATH}>{t('autoReview.viewRules')}</Link>
      </div>
    </section>
  );
}
