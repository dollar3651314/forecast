/**
 * 新建 / 编辑市场草稿（规则模板，需求第 4.1 节；字段同后端 MarketAdminApi.DraftInput）：品类、题目（三语）、选项和判定条件（2～10 个，三语）、
 * 规则全文（判定条件、取消作废条件、数据修正，三语）、裁决方式和来源、四个时间（UTC）、争议窗口（平台裁决）、单客户上限。
 * 费率不在这里：新建时按品类默认值带出，之后在市场详情"改费率"（fee:edit）。
 * 右栏：上架检查清单（后端的 missing + 人工勾选的三项）和操作：保存草稿、提交上架（market:edit）、检查后上架 / 退回（market:publish）、删除草稿。
 * 上架后题目、选项、时间、来源不能改（AC-12），这里只编辑草稿；不是草稿时跳到详情页。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { Alert, App, Button, Card, Checkbox, Col, Form, Input, InputNumber, Radio, Row, Select, Space, Tag } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { createMarket, deleteMarket, getMarket, publishMarket, returnMarket, submitMarket, updateMarket } from '../../api';
import { CATEGORIES, type DraftInput, type I18nText, type MarketDetail } from '../../api/fcTypes';
import type { FieldError } from '../../api/types';
import { ErrorState } from '../../components/ErrorState';
import { EMPTY_TEXT, I18nInputs } from '../../components/I18nInputs';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useNoteDialog } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { fromInput, toInput, utc } from '../../utils/market';

const HOUR = 3_600_000;

function blank(): DraftInput {
  const base = Math.ceil(Date.now() / HOUR) * HOUR;
  const iso = (ms: number) => new Date(ms).toISOString().replace('.000Z', 'Z');
  return {
    category: 'FOOTBALL',
    title: { ...EMPTY_TEXT },
    rules: { ...EMPTY_TEXT },
    resolutionMode: 'PLATFORM',
    sourcePrimary: '',
    sourceBackup: '',
    sourceRef: null,
    openAt: iso(base + HOUR),
    closeAt: iso(base + 49 * HOUR),
    eventStartAt: iso(base + 49 * HOUR + 5 * 60_000),
    eventEndAt: iso(base + 51 * HOUR),
    userCap: null,
    disputeWindowMinutes: 120,
    coverPath: null,
    options: [
      { label: { ...EMPTY_TEXT }, winCondition: { ...EMPTY_TEXT } },
      { label: { ...EMPTY_TEXT }, winCondition: { ...EMPTY_TEXT } },
    ],
  };
}

function toDraft(d: MarketDetail): DraftInput {
  return {
    category: d.category,
    title: d.title,
    rules: d.rules,
    resolutionMode: d.resolutionMode,
    sourcePrimary: d.sourcePrimary,
    sourceBackup: d.sourceBackup,
    sourceRef: d.sourceRef,
    openAt: d.openAt,
    closeAt: d.closeAt,
    eventStartAt: d.eventStartAt,
    eventEndAt: d.eventEndAt,
    userCap: d.userCap,
    disputeWindowMinutes: d.disputeWindowMinutes,
    coverPath: d.coverPath,
    options: d.options.map((o) => ({ label: o.label, winCondition: o.winCondition })),
  };
}

const full = (x: I18nText) => !!(x.zhCN.trim() && x.en.trim() && x.zhTW.trim());

/** 和后端 MarketService.missing 同一口径（提交上架、上架时检查的必填项），页面上马上显示，不等保存 */
function localMissing(d: DraftInput): string[] {
  const m: string[] = [];
  if (!full(d.title)) m.push('title');
  if (d.options.length < 2 || d.options.some((o) => !full(o.label) || !full(o.winCondition))) m.push('options');
  if (!full(d.rules)) m.push('rules');
  if (!d.resolutionMode) m.push('mode');
  if (!d.sourcePrimary.trim() || !d.sourceBackup.trim()) m.push('source');
  if (d.resolutionMode === 'PLATFORM' && !d.disputeWindowMinutes) m.push('dispute');
  const t = [d.openAt, d.closeAt, d.eventStartAt, d.eventEndAt].map((x) => Date.parse(x));
  if (t.some(Number.isNaN) || !(t[0] < t[1] && t[1] <= t[2] && t[2] < t[3])) m.push('time');
  return m;
}

/** 后端字段错误 → 清单的哪一项 */
function group(field: string): string {
  if (field.startsWith('title')) return 'title';
  if (field.startsWith('options')) return 'options';
  if (field.startsWith('rules')) return 'rules';
  if (field.startsWith('source')) return 'source';
  if (field.startsWith('dispute')) return 'dispute';
  if (field === 'resolutionMode') return 'mode';
  if (/At$/.test(field)) return 'time';
  return field;
}

export function MarketEditPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { no } = useParams();
  const fetcher = useCallback(() => (no ? getMarket(no) : Promise.resolve(null)), [no]);
  const { data, loading, error, reload } = useApi(fetcher);
  const [d, setD] = useState<DraftInput | null>(no ? null : blank());
  const [serverMissing, setServerMissing] = useState<FieldError[]>([]);
  const [checks, setChecks] = useState({ cover: false, edge: false, banned: false });
  const [busy, setBusy] = useState<string | null>(null);
  const note = useNoteDialog();
  useEffect(() => {
    if (data) {
      setD(toDraft(data));
      setServerMissing(data.missing);
    }
  }, [data]);
  const missing = useMemo(() => (d ? localMissing(d) : []), [d]);

  if (no && error) return <ErrorState error={error} onRetry={reload} />;
  if (no && data && data.status !== 'draft') return <Navigate to={`/markets/${no}`} replace />;
  if (!d || (no && loading && !data)) return <Card loading />;

  const set = (patch: Partial<DraftInput>) => setD({ ...d, ...patch });
  const setOpt = (i: number, patch: Partial<DraftInput['options'][number]>) => set({ options: d.options.map((o, j) => (j === i ? { ...o, ...patch } : o)) });
  const setTime = (k: 'openAt' | 'closeAt' | 'eventStartAt' | 'eventEndAt', v: string) => set({ [k]: fromInput(v) } as Partial<DraftInput>);

  const run = async (key: string, fn: () => Promise<MarketDetail | void>, ok: string) => {
    setBusy(key);
    try {
      const r = await fn();
      void message.success(ok);
      if (r && 'publicNo' in r) {
        setServerMissing(r.missing);
        if (r.status !== 'draft') navigate(`/markets/${r.publicNo}`, { replace: true });
        else if (!no) navigate(`/markets/${r.publicNo}/edit`, { replace: true });
        else reload();
      }
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(null);
    }
  };
  const save = () => (no ? updateMarket(no, d) : createMarket(d));
  const saveAndThen = async (then: (n: string) => Promise<MarketDetail>) => {
    const saved = await save();
    return then(saved.publicNo);
  };

  const keys = ['title', 'options', 'rules', 'mode', 'source', 'time', 'dispute'] as const;
  const bad = new Set([...missing, ...serverMissing.map((f) => group(f.field))]);
  const ready = keys.every((k) => !bad.has(k));
  const manualOk = checks.cover && checks.edge && checks.banned;
  const submitted = data?.submitted ?? false;

  return (
    <>
      <PageHeader
        title={no ? t('mke.titleEdit') : t('mke.titleNew')}
        sub={no ? <span className="num">{no}</span> : t('mke.subNew')}
        extra={
          <Space>
            <Pill tone={submitted ? 'pend' : 'muted'}>{submitted ? t('mk.status.review') : t('mk.status.draft')}</Pill>
            {data ? <Tag>{t('mke.version', { v: data.version })}</Tag> : null}
            {data ? <Tag>{t('mke.feeNow', { rate: data.feeRate })}</Tag> : null}
          </Space>
        }
      />
      <Row gutter={16} align="top">
        <Col xs={24} xl={16}>
          <Form layout="vertical" className="mke-form" requiredMark>
            <Card size="small" title={t('mke.sec.basic')}>
              <Row gutter={12}>
                <Col xs={24} sm={12}>
                  <Form.Item label={t('mk.category')} required>
                    <Select value={d.category} disabled={!!no} onChange={(v) => set({ category: v })} options={CATEGORIES.map((c) => ({ value: c, label: t(`mk.cats.${c}`) }))} />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item label={t('mke.userCap')} extra={t('mke.userCapHint')}>
                    <Input value={d.userCap ?? ''} placeholder={t('mke.userCapDefault')} suffix="USDT" onChange={(e) => set({ userCap: e.target.value.trim() || null })} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label={t('mke.titleField')} required extra={t('mke.titleHint')}>
                <I18nInputs value={d.title} onChange={(v) => set({ title: v })} placeholder={t('mke.titleField')} />
              </Form.Item>
            </Card>

            <Card size="small" title={t('mke.sec.options', { n: d.options.length })} extra={<span className="muted">{t('mke.optionsHint')}</span>}>
              {d.options.map((o, i) => (
                <div key={i} className="mke-opt">
                  <div className="mke-opt-n">{i + 1}</div>
                  <div className="mke-opt-body">
                    <I18nInputs value={o.label} onChange={(v) => setOpt(i, { label: v })} placeholder={t('mke.optLabel')} />
                    <I18nInputs value={o.winCondition} onChange={(v) => setOpt(i, { winCondition: v })} placeholder={t('mke.optWin')} />
                  </div>
                  <Button type="text" icon={<DeleteOutlined />} disabled={d.options.length <= 2} aria-label={t('common.delete')} onClick={() => set({ options: d.options.filter((_, j) => j !== i) })} />
                </div>
              ))}
              <Button icon={<PlusOutlined />} disabled={d.options.length >= 10} onClick={() => set({ options: [...d.options, { label: { ...EMPTY_TEXT }, winCondition: { ...EMPTY_TEXT } }] })}>
                {t('mke.addOption')}
              </Button>
            </Card>

            <Card size="small" title={t('mke.sec.rules')} extra={<span className="muted">{t('mke.rulesHint')}</span>}>
              <I18nInputs multiline rows={5} value={d.rules} onChange={(v) => set({ rules: v })} placeholder={t('mke.rulesPh')} />
            </Card>

            <Card size="small" title={t('mke.sec.resolve')}>
              <Row gutter={12}>
                <Col xs={24} md={8}>
                  <Form.Item label={t('mke.mode')} required>
                    <Radio.Group value={d.resolutionMode} onChange={(e) => set({ resolutionMode: e.target.value, disputeWindowMinutes: e.target.value === 'PLATFORM' ? d.disputeWindowMinutes ?? 120 : null })}>
                      <Radio.Button value="AUTO">{t('mk.mode.auto')}</Radio.Button>
                      <Radio.Button value="PLATFORM">{t('mk.mode.platform')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item label={t('mke.sourcePrimary')} required>
                    <Input value={d.sourcePrimary} maxLength={300} onChange={(e) => set({ sourcePrimary: e.target.value })} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item label={t('mke.sourceBackup')} required>
                    <Input value={d.sourceBackup} maxLength={300} onChange={(e) => set({ sourceBackup: e.target.value })} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={12}>
                {(['openAt', 'closeAt', 'eventStartAt', 'eventEndAt'] as const).map((k) => (
                  <Col xs={24} sm={12} lg={6} key={k}>
                    <Form.Item label={t(`mke.time.${k}`)} required>
                      <Input type="datetime-local" value={toInput(d[k])} suffix="UTC" aria-label={t(`mke.time.${k}`)} onChange={(e) => setTime(k, e.target.value)} />
                    </Form.Item>
                  </Col>
                ))}
              </Row>
              <Form.Item label={t('mke.disputeWindow')} extra={d.resolutionMode === 'AUTO' ? t('mke.disputeAuto') : t('mke.disputeHint')}>
                <InputNumber min={1} max={10080} disabled={d.resolutionMode === 'AUTO'} value={d.disputeWindowMinutes ?? undefined} addonAfter={t('mke.minutes')} onChange={(v) => set({ disputeWindowMinutes: v ?? null })} />
              </Form.Item>
            </Card>
          </Form>
        </Col>

        <Col xs={24} xl={8}>
          <div className="mke-side">
            <Card size="small" title={t('mke.checklist')}>
              <ul className="mke-check" data-testid="checklist">
                {keys.map((k) => (
                  <li key={k} className={bad.has(k) ? 'bad' : 'ok'}>
                    {bad.has(k) ? <CloseCircleFilled /> : <CheckCircleFilled />}
                    <span>{t(`mke.ck.${k}`)}</span>
                  </li>
                ))}
              </ul>
              <div className="mke-manual">
                <Checkbox checked={checks.cover} onChange={(e) => setChecks({ ...checks, cover: e.target.checked })}>
                  {t('mke.ck.cover')}
                </Checkbox>
                <Checkbox checked={checks.edge} onChange={(e) => setChecks({ ...checks, edge: e.target.checked })}>
                  {t('mke.ck.edge')}
                </Checkbox>
                <Checkbox checked={checks.banned} onChange={(e) => setChecks({ ...checks, banned: e.target.checked })}>
                  {t('mke.ck.banned')}
                </Checkbox>
              </div>
              {!ready && <Alert type="warning" showIcon message={t('mke.notReady', { n: keys.filter((k) => bad.has(k)).length })} />}
              <div className="mke-times">
                <span>{t('mke.time.closeAt')}</span>
                <b className="num">{utc(d.closeAt, true)}</b>
              </div>
            </Card>
            <div className="mke-actions">
              <PermButton perm="market:edit" loading={busy === 'save'} onClick={() => void run('save', save, t('mke.saved'))}>
                {t('mke.saveDraft')}
              </PermButton>
              <PermButton perm="market:edit" loading={busy === 'submit'} disabled={!ready || submitted} disabledReason={submitted ? t('mke.alreadySubmitted') : t('mke.fillFirst')} onClick={() => void run('submit', () => saveAndThen(submitMarket), t('mke.submitted'))}>
                {t('mke.submit')}
              </PermButton>
              <PermButton perm="market:publish" type="primary" loading={busy === 'publish'} disabled={!ready || !manualOk} disabledReason={t('mke.checkFirst')} onClick={() => void run('publish', () => saveAndThen((n) => publishMarket(n, true)), t('mke.published'))}>
                {t('mke.publish')}
              </PermButton>
            </div>
            {no ? (
              <div className="mke-more">
                {submitted ? (
                  <PermButton
                    perm="market:publish"
                    size="small"
                    onClick={() =>
                      note.ask({
                        title: t('mke.returnTitle'),
                        label: t('mke.returnReason'),
                        onConfirm: (r) => returnMarket(no, r),
                        onDone: reload,
                      })
                    }
                  >
                    {t('mke.return')}
                  </PermButton>
                ) : null}
                <PermButton
                  perm="market:edit"
                  size="small"
                  danger
                  onClick={() =>
                    note.ask({
                      title: t('mke.deleteTitle'),
                      label: t('mke.deleteNote'),
                      danger: true,
                      onConfirm: () => deleteMarket(no),
                      onDone: () => navigate('/markets', { replace: true }),
                    })
                  }
                >
                  {t('mke.delete')}
                </PermButton>
              </div>
            ) : null}
          </div>
        </Col>
      </Row>
      {note.element}
    </>
  );
}
