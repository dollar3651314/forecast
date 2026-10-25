/**
 * 市场编辑（规则模板，需求第 4.1 节）：品类、题目（三种语言）、选项和判定条件（2～10 个，三种语言）、规则全文、裁决方式和来源、时间（UTC）、
 * 取消 / 作废条件、数据修正、争议窗口、费率（只读，按品类默认值带出，改要 fee:edit）、单客户上限。
 * 右栏：上架检查清单（缺的必填项、人工勾选的检查项）和操作（保存草稿、提交上架；有 market:publish 的人可以直接上架）。
 * 上架后题目、选项、时间、裁决来源不能再改（AC-12），这里只编辑草稿。
 * M1 原型：数据来自 mocks/markets.ts；真实接口 POST/PUT /api/admin/markets、…/submit、…/publish（S3 已有）在 S9 接。
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Alert, App, Button, Card, Checkbox, Col, Form, Input, InputNumber, Radio, Row, Select, Space, Tabs, Tag } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getMarketDraft, saveMarketDraft } from '../../api';
import type { I18nText, MarketCategory, MarketDraft } from '../../api/types';
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

const LANGS: Array<{ k: keyof I18nText; label: string }> = [
  { k: 'zhCN', label: '简体' },
  { k: 'en', label: 'EN' },
  { k: 'zhTW', label: '繁體' },
];
const CATS: MarketCategory[] = ['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'];
const EMPTY: I18nText = { zhCN: '', en: '', zhTW: '' };

/** 三种语言并排的输入框；空的标红 */
function I18nInputs({ value, onChange, placeholder }: { value: I18nText; onChange: (v: I18nText) => void; placeholder?: string }) {
  return (
    <div className="i18n-inputs">
      {LANGS.map((l) => (
        <Input key={l.k} addonBefore={l.label} value={value[l.k]} status={value[l.k].trim() ? undefined : 'error'} placeholder={placeholder} onChange={(e) => onChange({ ...value, [l.k]: e.target.value })} />
      ))}
    </div>
  );
}

const toLocalInput = (iso: string) => iso.slice(0, 16).replace('T', ' ');

export function MarketEditPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { no } = useParams();
  const fetcher = useCallback(() => getMarketDraft(no ?? null), [no]);
  const { data, loading, error, reload } = useApi(fetcher);
  const [d, setD] = useState<MarketDraft | null>(null);
  const [checks, setChecks] = useState({ cover: false, edge: false, banned: false });
  const [busy, setBusy] = useState(false);
  useEffect(() => setD(data ?? null), [data]);

  const missing = useMemo(() => {
    if (!d) return [];
    const all = (x: I18nText) => !!(x.zhCN.trim() && x.en.trim() && x.zhTW.trim());
    const m: string[] = [];
    if (!all(d.title)) m.push('title');
    if (d.options.length < 2 || d.options.some((o) => !all(o.label) || !all(o.winCondition))) m.push('options');
    if (!all(d.rules)) m.push(d.rules.zhCN.trim() && d.rules.en.trim() ? 'rules.zhTW' : 'rules');
    if (!d.sourcePrimary.trim() || !d.sourceBackup.trim()) m.push('sourcePrimary');
    if (d.resolutionMode === 'PLATFORM' && !d.disputeWindowMinutes) m.push('disputeWindow');
    return m;
  }, [d]);

  if (error) return <ErrorState error={error} onRetry={reload} />;
  if (loading || !d) return <Card loading />;

  const set = (patch: Partial<MarketDraft>) => setD({ ...d, ...patch });
  const setOpt = (i: number, patch: Partial<MarketDraft['options'][number]>) => set({ options: d.options.map((o, j) => (j === i ? { ...o, ...patch } : o)) });
  const save = async (submit: boolean) => {
    setBusy(true);
    try {
      const r = await saveMarketDraft(d, submit);
      void message.success(submit ? t('mke.submitted') : t('mke.saved'));
      setD(r);
      if (!no) navigate(`/markets/${r.publicNo}/edit`, { replace: true });
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(false);
    }
  };

  const CHECKLIST: Array<{ key: string; ok: boolean; label: string }> = [
    { key: 'title', ok: !missing.includes('title'), label: t('mke.ck.title') },
    { key: 'options', ok: !missing.includes('options'), label: t('mke.ck.options') },
    { key: 'rules', ok: !missing.some((m) => m.startsWith('rules')), label: missing.includes('rules.zhTW') ? t('mke.ck.rulesTw') : t('mke.ck.rules') },
    { key: 'source', ok: !missing.includes('sourcePrimary'), label: t('mke.ck.source') },
    { key: 'time', ok: d.openAt < d.closeAt && d.closeAt <= d.eventStartAt && d.eventStartAt < d.eventEndAt, label: t('mke.ck.time') },
    { key: 'dispute', ok: !missing.includes('disputeWindow'), label: t('mke.ck.dispute') },
  ];
  const ready = CHECKLIST.every((c) => c.ok);
  const manualOk = checks.cover && checks.edge && checks.banned;

  return (
    <>
      <PageHeader
        title={no ? t('mke.titleEdit') : t('mke.titleNew')}
        sub={no ? <span className="num">{no}</span> : t('mke.subNew')}
        extra={
          <Space>
            <Pill tone={d.submitted ? 'pend' : 'muted'}>{d.submitted ? t('mk.status.review') : t('mk.status.draft')}</Pill>
            {no && <Tag>{t('mke.version', { v: d.version })}</Tag>}
            <Tag color="blue">{t('mk.origin.schedule')}</Tag>
          </Space>
        }
      />
      <Row gutter={16} align="top">
        <Col xs={24} xl={16}>
          <Form layout="vertical" className="mke-form" requiredMark>
            <Card size="small" title={t('mke.sec.basic')}>
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item label={t('mk.category')} required>
                    <Select value={d.category} onChange={(v) => set({ category: v })} options={CATS.map((c) => ({ value: c, label: t(`mk.cats.${c}`) }))} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label={t('mke.fee')} extra={t('mke.feeHint')}>
                    <Input value={`${d.feeRate}%`} disabled suffix={t('mke.feeOnProfit')} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label={t('mke.userCap')} extra={t('mke.userCapHint')}>
                    <Input value={d.userCap} suffix="USDT" onChange={(e) => set({ userCap: e.target.value })} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label={t('mke.titleField')} required extra={t('mke.titleHint')}>
                <I18nInputs value={d.title} onChange={(v) => set({ title: v })} />
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
              <Button icon={<PlusOutlined />} disabled={d.options.length >= 10} onClick={() => set({ options: [...d.options, { label: EMPTY, winCondition: EMPTY }] })}>
                {t('mke.addOption')}
              </Button>
            </Card>

            <Card size="small" title={t('mke.sec.rules')}>
              <Tabs
                size="small"
                items={LANGS.map((l) => ({
                  key: l.k,
                  label: (
                    <span>
                      {l.label}
                      {!d.rules[l.k].trim() && <CloseCircleFilled className="mke-miss" />}
                    </span>
                  ),
                  children: <Input.TextArea rows={4} value={d.rules[l.k]} status={d.rules[l.k].trim() ? undefined : 'error'} placeholder={t('mke.rulesPh')} onChange={(e) => set({ rules: { ...d.rules, [l.k]: e.target.value } })} />,
                }))}
              />
              <Row gutter={12}>
                <Col span={12}>
                  <Form.Item label={t('mke.voidRules')}>
                    <Input.TextArea rows={2} value={d.voidRules.zhCN} onChange={(e) => set({ voidRules: { ...d.voidRules, zhCN: e.target.value } })} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item label={t('mke.correction')}>
                    <Input.TextArea rows={2} value={d.correction.zhCN} onChange={(e) => set({ correction: { ...d.correction, zhCN: e.target.value } })} />
                  </Form.Item>
                </Col>
              </Row>
            </Card>

            <Card size="small" title={t('mke.sec.resolve')}>
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item label={t('mke.mode')} required>
                    <Radio.Group value={d.resolutionMode} onChange={(e) => set({ resolutionMode: e.target.value })}>
                      <Radio.Button value="AUTO">{t('mk.mode.AUTO')}</Radio.Button>
                      <Radio.Button value="PLATFORM">{t('mk.mode.PLATFORM')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label={t('mke.sourcePrimary')} required>
                    <Input value={d.sourcePrimary} onChange={(e) => set({ sourcePrimary: e.target.value })} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label={t('mke.sourceBackup')} required>
                    <Input value={d.sourceBackup} onChange={(e) => set({ sourceBackup: e.target.value })} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={12}>
                {(['openAt', 'closeAt', 'eventStartAt', 'eventEndAt'] as const).map((k) => (
                  <Col span={6} key={k}>
                    <Form.Item label={t(`mke.time.${k}`)} required>
                      <Input value={toLocalInput(d[k])} suffix="UTC" readOnly />
                    </Form.Item>
                  </Col>
                ))}
              </Row>
              <Form.Item label={t('mke.disputeWindow')} extra={d.resolutionMode === 'AUTO' ? t('mke.disputeAuto') : undefined}>
                <InputNumber min={30} step={30} disabled={d.resolutionMode === 'AUTO'} value={d.disputeWindowMinutes ?? undefined} addonAfter={t('mke.minutes')} onChange={(v) => set({ disputeWindowMinutes: v ?? null })} />
              </Form.Item>
            </Card>
          </Form>
        </Col>

        <Col xs={24} xl={8}>
          <div className="mke-side">
            <Card size="small" title={t('mke.checklist')}>
              <ul className="mke-check">
                {CHECKLIST.map((c) => (
                  <li key={c.key} className={c.ok ? 'ok' : 'bad'}>
                    {c.ok ? <CheckCircleFilled /> : <CloseCircleFilled />}
                    <span>{c.label}</span>
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
              {!ready && <Alert type="warning" showIcon message={t('mke.notReady', { n: CHECKLIST.filter((c) => !c.ok).length })} />}
              <div className="mke-times">
                <span>{t('mke.time.closeAt')}</span>
                <b className="num">{utc(d.closeAt, true)}</b>
              </div>
            </Card>
            <div className="mke-actions">
              <Button onClick={() => void save(false)} loading={busy}>
                {t('mke.saveDraft')}
              </Button>
              <PermButton perm="market:edit" onClick={() => void save(true)} disabled={!ready} disabledReason={t('mke.fillFirst')}>
                {t('mke.submit')}
              </PermButton>
              <PermButton perm="market:publish" type="primary" disabled={!ready || !manualOk} disabledReason={t('mke.checkFirst')} onClick={() => void message.info(t('mk.protoAction'))}>
                {t('mke.publish')}
              </PermButton>
            </div>
          </div>
        </Col>
      </Row>
    </>
  );
}
