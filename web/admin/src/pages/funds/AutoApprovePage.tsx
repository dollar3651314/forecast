/**
 * v2 提现自动审批规则设置（技术方案/v2-提现自动审批.md 第 7、9 节）：总开关（默认关闭）、全部规则（条数按 AUTO_RULES，现在 13 条；开关 + 数值 + 说明 + 默认值）、
 * 保存时高危确认（必须填原因，写审计 withdrawal.auto_rule_update；权限 withdrawal:auto_rule_edit）、最近修改记录。从出金审核页进入（菜单归在"出金审核"下）。
 * 保存带上打开页面时的版本号：别人先改了会返回 409 CONFIG_CHANGED，这时重新读取最新配置。
 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Alert, Button, Empty, Input, Skeleton, Switch } from 'antd';
import { ArrowLeftOutlined, HistoryOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { useNavigate } from 'react-router-dom';
import { getAutoApproveSettings, listAutoApproveChanges, updateAutoApproveSettings } from '../../api';
import { ApiError, type AutoApproveChange, type AutoApproveSettings, type AutoRuleCode, type AutoRuleSetting } from '../../api/types';
import { AUTO_RULE_EDIT_PERM, AUTO_RULES, crossRuleProblems, ruleParamProblem, type RuleParamDef } from '../../config/autoApprove';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { useCan } from '../../components/PermButton';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { ErrorState } from '../../components/ErrorState';
import { Pill } from '../../components/Pill';
import { shortTime } from '../../utils/display';
import { fmtUsdt } from './AutoReview';

const DEF = Object.fromEntries(AUTO_RULES.flatMap((r) => r.params.map((p) => [`${r.code}.${p.key}`, p]))) as Record<string, RuleParamDef>;

export function fmtParam(t: TFunction, def: RuleParamDef | undefined, value: string): string {
  if (!def) return value;
  return def.unit === 'usdt' ? `${fmtUsdt(value)} USDT` : t(`autoApprove.unit.${def.unit}`, { v: value });
}

/** 修改记录里的一项："R1 单笔上限：1,000 USDT → 500 USDT" */
export function changeText(t: TFunction, c: AutoApproveChange['changes'][number]): string {
  const onOff = (v: string) => (v === 'true' ? t('common.on') : t('common.off'));
  if (c.field === 'master') return t('autoApprove.changeLine', { field: t('autoApprove.master'), before: onOff(c.before), after: onOff(c.after) });
  const [code, key] = c.field.split('.');
  const name = `${code} ${t(`autoRule.${code}.name`)}`;
  if (key === 'enabled') return t('autoApprove.changeLine', { field: name, before: onOff(c.before), after: onOff(c.after) });
  const def = DEF[c.field];
  return t('autoApprove.changeLine', { field: `${name} · ${t(`autoRule.${code}.param.${key}`)}`, before: fmtParam(t, def, c.before), after: fmtParam(t, def, c.after) });
}

interface Draft {
  masterEnabled: boolean;
  rules: AutoRuleSetting[];
}

const toDraft = (s: AutoApproveSettings): Draft => ({ masterEnabled: s.masterEnabled, rules: s.rules.map((r) => ({ ...r, params: { ...r.params } })) });

function diffOf(saved: AutoApproveSettings, d: Draft): AutoApproveChange['changes'] {
  const out: AutoApproveChange['changes'] = [];
  if (saved.masterEnabled !== d.masterEnabled) out.push({ field: 'master', before: String(saved.masterEnabled), after: String(d.masterEnabled) });
  for (const r of d.rules) {
    const s = saved.rules.find((x) => x.code === r.code);
    if (!s) continue;
    if (s.enabled !== r.enabled) out.push({ field: `${r.code}.enabled`, before: String(s.enabled), after: String(r.enabled) });
    for (const [k, v] of Object.entries(r.params)) {
      if (v.trim() !== '' && Number(v) === Number(s.params[k])) continue;
      out.push({ field: `${r.code}.${k}`, before: s.params[k], after: v.trim() });
    }
  }
  return out;
}

export function AutoApprovePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const back = (
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/funds/withdrawals')}>
      {t('autoApprove.back')}
    </Button>
  );
  return <AutoApproveEditor back={back} />;
}

function AutoApproveEditor({ back }: { back: ReactNode }) {
  const { t } = useTranslation();
  const can = useCan();
  const hr = useHighRisk();
  const settingsFetcher = useCallback(() => getAutoApproveSettings(), []);
  const changesFetcher = useCallback(() => listAutoApproveChanges(), []);
  const settings = useApi(settingsFetcher);
  const history = useApi(changesFetcher);
  const [draft, setDraft] = useState<Draft | null>(null);

  useEffect(() => {
    if (settings.data) setDraft(toDraft(settings.data));
  }, [settings.data]);

  const saved = settings.data;
  const diff = useMemo(() => (saved && draft ? diffOf(saved, draft) : []), [saved, draft]);
  const problems = useMemo(() => {
    const out: Record<string, string> = {};
    if (!draft) return out;
    for (const r of draft.rules) {
      for (const def of AUTO_RULES.find((x) => x.code === r.code)!.params) {
        const p = ruleParamProblem(def, r.params[def.key]);
        if (p) out[`${r.code}.${def.key}`] = t(p.key, p.params);
      }
    }
    const params = Object.fromEntries(draft.rules.map((r) => [r.code, r.params])) as Partial<Record<AutoRuleCode, Record<string, string>>>;
    for (const c of crossRuleProblems(params)) out[`${c.code}.${c.key}`] ??= t(c.msg);
    return out;
  }, [draft, t]);

  if (settings.error) {
    return (
      <>
        <PageHeader title={t('autoApprove.title')} extra={back} />
        <div className="card">
          <ErrorState error={settings.error} onRetry={settings.reload} />
        </div>
      </>
    );
  }
  if (!saved || !draft) {
    return (
      <>
        <PageHeader title={t('autoApprove.title')} extra={back} />
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 12 }} />
        </div>
      </>
    );
  }

  const editable = can(AUTO_RULE_EDIT_PERM);
  const setRule = (code: AutoRuleCode, patch: Partial<AutoRuleSetting>) =>
    setDraft({ ...draft, rules: draft.rules.map((r) => (r.code === code ? { ...r, ...patch, params: { ...r.params, ...(patch.params ?? {}) } } : r)) });
  const hasProblems = Object.keys(problems).length > 0;
  const enabledCount = draft.rules.filter((r) => r.enabled).length;

  const save = () =>
    hr.ask({
      title: t('autoApprove.saveTitle'),
      description: (
        <div>
          <div>{t('autoApprove.saveDesc', { count: diff.length, version: saved.version + 1 })}</div>
          <ul className="aa-diff">
            {diff.map((c) => (
              <li key={c.field}>{changeText(t, c)}</li>
            ))}
          </ul>
          {draft.masterEnabled ? null : <div className="text-danger">{t('autoApprove.saveMasterOff')}</div>}
        </div>
      ),
      confirmText: t('autoApprove.saveConfirm'),
      successText: t('autoApprove.saved'),
      onConfirm: async (v) => {
        try {
          await updateAutoApproveSettings({ masterEnabled: draft.masterEnabled, rules: draft.rules, reason: v.reason, expectedVersion: saved.version });
        } catch (e) {
          // 别人先保存了（版本变了）：读取最新配置和修改记录，提示核对后重新修改
          if (e instanceof ApiError && (e.code === 'CONFIG_CHANGED' || e.reasonKey === 'errors.configChanged')) {
            settings.refresh();
            history.refresh();
          }
          throw e;
        }
      },
      onDone: () => {
        settings.reload();
        history.reload();
      },
    });

  return (
    <>
      <PageHeader title={t('autoApprove.title')} sub={t('autoApprove.sub')} extra={back} />

      <section className={`card aa-master${draft.masterEnabled ? ' on' : ''}`} aria-label={t('autoApprove.master')}>
        <div className="aa-master-main">
          <Switch
            checked={draft.masterEnabled}
            disabled={!editable}
            onChange={(v) => setDraft({ ...draft, masterEnabled: v })}
            aria-label={t('autoApprove.master')}
          />
          <div>
            <div className="aa-master-title">
              {t('autoApprove.master')}{' '}
              <Pill tone={draft.masterEnabled ? 'ok' : 'muted'}>{draft.masterEnabled ? t('autoApprove.masterOn') : t('autoApprove.masterOff')}</Pill>
              <span className="muted aa-default">{t('autoApprove.masterDefault')}</span>
            </div>
            <div className="aa-master-desc">{draft.masterEnabled ? t('autoApprove.masterOnDesc', { count: enabledCount }) : t('autoApprove.masterOffDesc')}</div>
          </div>
        </div>
        <div className="aa-master-meta">
          {t('autoApprove.versionLine', { version: saved.version, by: saved.updatedBy ?? '—', time: shortTime(saved.updatedAt) })}
        </div>
      </section>

      <Alert type="info" showIcon message={t('autoApprove.notLoosen')} description={t('autoApprove.notLoosenDesc')} />

      <section className="card aa-rules" aria-label={t('autoApprove.rulesTitle', { n: draft.rules.length })}>
        <div className="aa-rules-head">
          <h2>{t('autoApprove.rulesTitle', { n: draft.rules.length })}</h2>
          <span className="muted">{t('autoApprove.rulesSub', { enabled: enabledCount, total: draft.rules.length })}</span>
        </div>
        <div className="aa-grid-head" aria-hidden>
          <span>{t('autoApprove.colRule')}</span>
          <span>{t('autoApprove.colValue')}</span>
          <span>{t('autoApprove.colEnabled')}</span>
        </div>
        {AUTO_RULES.map((def) => {
          const r = draft.rules.find((x) => x.code === def.code)!;
          const s = saved.rules.find((x) => x.code === def.code)!;
          const changed = diff.some((c) => c.field.startsWith(`${def.code}.`));
          return (
            <div key={def.code} className={`aa-row${r.enabled ? '' : ' off'}${changed ? ' changed' : ''}`} data-rule={def.code}>
              <div className="aa-rule">
                <span className="aa-code num">{def.code}</span>
                <div>
                  <div className="aa-name">
                    {t(`autoRule.${def.code}.name`)} {changed ? <Pill tone="warn">{t('autoApprove.modified')}</Pill> : null}
                    {r.enabled ? null : <Pill tone="muted">{t('autoApprove.ruleOff')}</Pill>}
                  </div>
                  <div className="aa-desc">{t(`autoRule.${def.code}.desc`)}</div>
                </div>
              </div>
              <div className="aa-params">
                {def.params.length ? (
                  def.params.map((p) => {
                    const id = `${def.code}.${p.key}`;
                    const err = problems[id];
                    return (
                      <div key={p.key} className="aa-param">
                        <label htmlFor={`aa-${id}`}>{t(`autoRule.${def.code}.param.${p.key}`)}</label>
                        <Input
                          id={`aa-${id}`}
                          value={r.params[p.key]}
                          disabled={!editable}
                          inputMode="decimal"
                          status={err ? 'error' : undefined}
                          suffix={<span className="muted">{t(`autoApprove.unitShort.${p.unit}`)}</span>}
                          onChange={(e) => setRule(def.code, { params: { [p.key]: e.target.value } })}
                          aria-invalid={!!err}
                        />
                        {err ? (
                          <div className="aa-err" role="alert">
                            {err}
                          </div>
                        ) : (
                          <div className="aa-hint">
                            {t('autoApprove.defaultIs', { v: fmtParam(t, p, p.defaultValue) })}
                            {Number(s.params[p.key]) !== Number(r.params[p.key]) ? ` · ${t('autoApprove.savedIs', { v: fmtParam(t, p, s.params[p.key]) })}` : ''}
                          </div>
                        )}
                      </div>
                    );
                  })
                ) : (
                  <div className="aa-hint aa-noparam">{t(`autoRule.${def.code}.fixed`)}</div>
                )}
              </div>
              <div className="aa-switch">
                <Switch
                  size="small"
                  checked={r.enabled}
                  disabled={!editable}
                  onChange={(v) => setRule(def.code, { enabled: v })}
                  aria-label={t('autoApprove.ruleSwitch', { code: def.code, name: t(`autoRule.${def.code}.name`) })}
                />
                <span className="aa-hint">{t('autoApprove.defaultOn')}</span>
              </div>
            </div>
          );
        })}
      </section>

      <div className={`aa-savebar card${diff.length ? ' dirty' : ''}`}>
        <span>{diff.length ? t('autoApprove.dirty', { count: diff.length }) : t('autoApprove.clean')}</span>
        {hasProblems ? <span className="text-danger">{t('autoApprove.fixErrors')}</span> : null}
        <span className="aa-savebar-acts">
          <Button icon={<UndoOutlined />} disabled={!diff.length} onClick={() => setDraft(toDraft(saved))}>
            {t('autoApprove.discard')}
          </Button>
          <PermButton
            perm={AUTO_RULE_EDIT_PERM}
            type="primary"
            icon={<SaveOutlined />}
            disabled={!diff.length || hasProblems}
            disabledReason={hasProblems ? t('autoApprove.fixErrors') : t('autoApprove.clean')}
            onClick={save}
          >
            {t('autoApprove.save')}
          </PermButton>
        </span>
      </div>

      <section className="card card-pad aa-history" aria-label={t('autoApprove.historyTitle')}>
        <h2 className="card-title">
          <span>
            <HistoryOutlined /> {t('autoApprove.historyTitle')}
          </span>
          <span className="muted aa-hint">{t('autoApprove.historySub')}</span>
        </h2>
        {history.error ? (
          <ErrorState error={history.error} onRetry={history.reload} compact />
        ) : !history.data ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : !history.data.length ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('autoApprove.historyEmpty')} />
        ) : (
          <ol className="aa-hist-list">
            {history.data.map((h) => (
              <li key={h.id}>
                <div className="aa-hist-meta">
                  <span className="num">{shortTime(h.time)}</span> · <b>{h.adminName}</b> · <span className="aa-ver num">v{h.version}</span>
                </div>
                <ul className="aa-hist-changes">
                  {h.changes.map((c) => (
                    <li key={c.field}>{changeText(t, c)}</li>
                  ))}
                </ul>
                <div className="aa-hist-reason">{t('autoApprove.reasonLine', { reason: h.reason })}</div>
              </li>
            ))}
          </ol>
        )}
      </section>
      {hr.element}
    </>
  );
}
