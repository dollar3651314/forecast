/**
 * 加密市场自动生成（需求第 4.2、6.2 节，MQ-1 节目化；后端 MarketAutogenAdminApi）：
 * 每个模板一张卡片：币种、周期、生成方式（场次表：每天的 UTC 时刻，页面同时显示北京时间 / 连续窗口：未来至少 N 个）、提前多久生成、单客户上限、
 * 是否启用和检查记录；接下来的几个窗口（已生成的带市场编号）；按下一个窗口渲染出来的题目、规则、来源、选项（第 4.1 节清单检查时看）。
 * 新建、修改（只能在关闭时）要 market:autogen（高危）；启用要 market:publish，并勾选"已按第 4.1 节清单检查"；关闭要 market:autogen（高危）。
 * 下面是规则文本模板（加密涨跌），有启用中的自动生成用它时不能改（先关闭）。
 * S13（MF-3）：场次表的每个时刻可以只选星期几（按 UTC 日期；"13:00@1,6,7" = 周一、周六、周日的 13:00 UTC），不选 = 每天；
 * 场次表生成的市场自动列入今日场次。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Card, Checkbox, Col, Drawer, Empty, Form, Input, InputNumber, Radio, Row, Select, Skeleton, Space, Table, Tag } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { createAutogen, disableAutogen, enableAutogen, listAutogens, listRuleTemplates, updateAutogen, updateRuleTemplate } from '../../api';
import type { Autogen, AutogenInput, RuleTemplate } from '../../api/fcTypes';
import { ErrorState } from '../../components/ErrorState';
import { I18nInputs, I18nView } from '../../components/I18nInputs';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { beijing, beijingNextDay, formatSlot, parseSlot, textOf, utc } from '../../utils/market';

const toInput = (a?: Autogen): AutogenInput => ({
  symbol: a?.symbol ?? 'BTCUSDT',
  period: a?.period ?? '1H',
  mode: a?.mode ?? 'schedule',
  slots: a?.slots ?? ['13:00'],
  rollingCount: a?.rollingCount ?? null,
  generateAheadMinutes: a?.generateAheadMinutes ?? 1440,
  templateNo: a?.templateNo ?? null,
  userCap: a?.userCap ?? null,
});

/** 新建 / 修改的表单（放在高危确认弹窗里，值通过回调交出去） */
function AutogenForm({ init, onChange }: { init: AutogenInput; onChange: (v: AutogenInput) => void }) {
  const { t } = useTranslation();
  const [v, setV] = useState(init);
  const set = (p: Partial<AutogenInput>) => {
    const n = { ...v, ...p };
    setV(n);
    onChange(n);
  };
  return (
    <div className="ag-form">
      <Space wrap>
        <Select value={v.symbol} style={{ width: 130 }} onChange={(x) => set({ symbol: x })} options={['BTCUSDT', 'ETHUSDT'].map((x) => ({ value: x, label: x }))} aria-label={t('ag.symbol')} />
        <Radio.Group value={v.period} onChange={(e) => set({ period: e.target.value })} optionType="button" options={[{ value: '15M', label: t('ag.p15') }, { value: '1H', label: t('ag.p1h') }]} />
      </Space>
      <Radio.Group value={v.mode} onChange={(e) => set({ mode: e.target.value })} style={{ marginTop: 8 }}>
        <Radio value="schedule">{t('ag.schedule')}</Radio>
        <Radio value="rolling">{t('ag.rolling')}</Radio>
      </Radio.Group>
      {v.mode === 'schedule' ? (
        <Form.Item label={t('ag.slots')} extra={t('ag.slotsHint2')} style={{ marginTop: 8 }}>
          <SlotEditor slots={v.slots} onChange={(x) => set({ slots: x })} />
        </Form.Item>
      ) : (
        <Form.Item label={t('ag.rollingCount')} style={{ marginTop: 8 }}>
          <InputNumber min={1} max={96} value={v.rollingCount ?? undefined} onChange={(x) => set({ rollingCount: x ?? null })} />
        </Form.Item>
      )}
      <Space wrap>
        <Form.Item label={t('ag.ahead')} extra={t('ag.aheadHint')}>
          <InputNumber min={0} max={10080} value={v.generateAheadMinutes ?? undefined} addonAfter={t('mke.minutes')} onChange={(x) => set({ generateAheadMinutes: x ?? null })} />
        </Form.Item>
        <Form.Item label={t('mke.userCap')} extra={t('mke.userCapHint')}>
          <Input value={v.userCap ?? ''} suffix="USDT" placeholder={t('mke.userCapDefault')} onChange={(e) => set({ userCap: e.target.value.trim() || null })} />
        </Form.Item>
      </Space>
    </div>
  );
}

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];

/** 一个场次的显示：13:00 UTC · 北京 21:00 · 周一、周六（星期按 UTC） */
function slotText(s: string, t: (k: string, o?: Record<string, unknown>) => string): string {
  const p = parseSlot(s);
  const days = p.days.length ? p.days.map((d) => t(`ag.wd.${d}`)).join(t('ag.wdSep')) : t('ag.everyDay');
  return `${p.time} UTC · ${t('ag.bj')} ${beijing(p.time)}${beijingNextDay(p.time) ? t('ag.nextDay') : ''} · ${days}`;
}

/** 场次表编辑：每行一个 UTC 时刻 + 星期（不选 = 每天） */
function SlotEditor({ slots, onChange }: { slots: string[]; onChange: (v: string[]) => void }) {
  const { t } = useTranslation();
  const rows = slots.map(parseSlot);
  const put = (i: number, p: Partial<ReturnType<typeof parseSlot>>) => onChange(rows.map((r, j) => formatSlot(j === i ? { ...r, ...p } : r)));
  return (
    <div className="ag-slots" data-testid="slot-editor">
      {rows.map((r, i) => (
        <div key={i} className="ag-slot">
          <Input value={r.time} style={{ width: 110 }} suffix="UTC" placeholder="13:00" aria-label={t('ag.slotTime')} onChange={(e) => put(i, { time: e.target.value })} />
          <span className="muted">
            {t('ag.bj')} {beijing(r.time)}
            {beijingNextDay(r.time) ? t('ag.nextDay') : ''}
          </span>
          <Checkbox.Group value={r.days} onChange={(d) => put(i, { days: d as number[] })} options={WEEKDAYS.map((d) => ({ value: d, label: t(`ag.wdShort.${d}`) }))} />
          <Button type="text" icon={<DeleteOutlined />} aria-label={t('common.delete')} onClick={() => onChange(slots.filter((_, j) => j !== i))} />
        </div>
      ))}
      <Button size="small" icon={<PlusOutlined />} onClick={() => onChange([...slots, '13:00'])}>
        {t('ag.addSlot')}
      </Button>
    </div>
  );
}

function AutogenCard({ a, onDone }: { a: Autogen; onDone: () => void }) {
  const { t, i18n } = useTranslation();
  const hr = useHighRisk();
  const edit = () => {
    let input = toInput(a);
    hr.ask({
      title: t('ag.editTitle', { name: `${a.symbol} ${a.period}` }),
      extra: <AutogenForm init={input} onChange={(v) => (input = v)} />,
      onConfirm: (v) => updateAutogen(a.autogenNo, input, v.reason),
      onDone,
    });
  };
  const enable = () =>
    hr.ask({
      title: t('ag.enableTitle', { name: `${a.symbol} ${a.period}` }),
      description: <Alert type="warning" showIcon message={t('ag.enableNote')} />,
      extra: (
        <Form.Item name="checked" valuePropName="checked" rules={[{ validator: (_r, x) => (x ? Promise.resolve() : Promise.reject(new Error(t('ag.checkFirst')))) }]}>
          <Checkbox>{t('ag.checked')}</Checkbox>
        </Form.Item>
      ),
      onConfirm: (v) => enableAutogen(a.autogenNo, !!v.checked, v.reason),
      onDone,
    });
  const disable = () => hr.ask({ title: t('ag.disableTitle', { name: `${a.symbol} ${a.period}` }), description: t('ag.disableNote'), onConfirm: (v) => disableAutogen(a.autogenNo, v.reason), onDone });
  return (
    <Card
      size="small"
      className="ag-card"
      title={
        <Space wrap>
          <b>
            {a.symbol} · {a.period === '1H' ? t('ag.p1h') : t('ag.p15')}
          </b>
          <Pill tone={a.enabled ? 'ok' : 'muted'}>{a.enabled ? t('ag.enabled') : t('ag.disabled')}</Pill>
        </Space>
      }
      extra={
        <Space wrap>
          <PermButton perm="market:autogen" size="small" icon={<EditOutlined />} disabled={a.enabled} disabledReason={t('ag.disableFirst')} onClick={edit}>
            {t('common.edit')}
          </PermButton>
          {a.enabled ? (
            <PermButton perm="market:autogen" size="small" danger onClick={disable}>
              {t('ag.disable')}
            </PermButton>
          ) : (
            <PermButton perm="market:publish" size="small" type="primary" disabled={a.missing.length > 0} disabledReason={t('ag.missing')} onClick={enable}>
              {t('ag.enable')}
            </PermButton>
          )}
        </Space>
      }
    >
      <Row gutter={16}>
        <Col xs={24} lg={10}>
          <dl className="fc-dl">
            <div>
              <dt>{t('ag.mode')}</dt>
              <dd>{a.mode === 'schedule' ? t('ag.schedule') : t('ag.rollingN', { n: a.rollingCount })}</dd>
            </div>
            {a.mode === 'schedule' ? (
              <div>
                <dt>{t('ag.slots')}</dt>
                <dd>
                  {a.slots.map((s) => (
                    <Tag key={s}>{slotText(s, t)}</Tag>
                  ))}
                </dd>
              </div>
            ) : null}
            <div>
              <dt>{t('ag.ahead')}</dt>
              <dd>{t('pm.minutes', { n: a.generateAheadMinutes })}</dd>
            </div>
            <div>
              <dt>{t('mke.userCap')}</dt>
              <dd>{a.userCap ? `${a.userCap} USDT` : t('mke.userCapDefault')}</dd>
            </div>
            <div>
              <dt>{t('ag.checkedBy')}</dt>
              <dd>{a.checkedAt ? t('ag.checkedInfo', { by: a.checkedBy, at: utc(a.checkedAt, true), v: a.checkedTemplateVersion }) : <span className="muted">{t('ag.notChecked')}</span>}</dd>
            </div>
          </dl>
          {a.missing.length ? <Alert type="error" showIcon message={t('ag.missingList', { list: a.missing.map((f) => f.field).join(', ') })} /> : null}
        </Col>
        <Col xs={24} lg={14}>
          <Table
            size="small"
            rowKey="windowStart"
            pagination={false}
            scroll={{ x: 'max-content' }}
            dataSource={a.upcoming}
            locale={{ emptyText: t('ag.noWindows') }}
            columns={[
              { title: t('ag.window'), key: 'w', render: (_, w) => <Num>{`${utc(w.windowStart, true)} – ${utc(w.windowEnd).slice(6)}`}</Num> },
              { title: t('mke.time.closeAt'), dataIndex: 'closeAt', render: (v: string) => <Num>{utc(v)}</Num> },
              { title: t('ag.generated'), key: 'g', render: (_, w) => (w.generated && w.marketNo ? <Link to={`/markets/${w.marketNo}`}>{w.marketNo}</Link> : <span className="muted">{t('ag.notYet')}</span>) },
            ]}
          />
          <div className="ag-sample">
            <span className="muted">{t('ag.sample')}</span> <b>{textOf(a.sample.title, i18n.language)}</b>
          </div>
        </Col>
      </Row>
      {hr.element}
    </Card>
  );
}

function TemplateDrawer({ tpl, onClose, onDone }: { tpl: RuleTemplate | null; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const hr = useHighRisk();
  const [v, setV] = useState<RuleTemplate | null>(tpl);
  if (tpl && v?.templateNo !== tpl.templateNo) setV(tpl);
  return (
    <Drawer
      open={!!tpl}
      onClose={onClose}
      width={Math.min(720, typeof window !== 'undefined' ? window.innerWidth : 720)}
      title={tpl ? t('ag.tplEdit', { name: tpl.name }) : ''}
      destroyOnClose
      footer={
        <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <PermButton
            perm="market:autogen"
            type="primary"
            disabled={!!tpl?.usedByEnabled}
            disabledReason={t('ag.tplInUse')}
            onClick={() =>
              v &&
              hr.ask({
                title: t('ag.tplSave', { name: v.name }),
                description: t('ag.tplSaveNote'),
                onConfirm: (x) => updateRuleTemplate(v.templateNo, { name: v.name, title: v.title, rules: v.rules, sourcePrimary: v.sourcePrimary, sourceBackup: v.sourceBackup, options: v.options }, x.reason),
                onDone: () => {
                  onDone();
                  onClose();
                },
              })
            }
          >
            {t('common.save')}
          </PermButton>
        </Space>
      }
    >
      {v ? (
        <Form layout="vertical">
          {tpl?.usedByEnabled ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('ag.tplInUse')} /> : null}
          <Alert type="info" style={{ marginBottom: 12 }} message={t('ag.vars')} description={<Num>{v.variables.map((x) => `{${x}}`).join('  ')}</Num>} />
          <Form.Item label={t('ag.tplName')}>
            <Input value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('mke.titleField')}>
            <I18nInputs value={v.title} onChange={(x) => setV({ ...v, title: x })} placeholder={t('mke.titleField')} />
          </Form.Item>
          <Form.Item label={t('mke.sec.rules')}>
            <I18nInputs multiline rows={4} value={v.rules} onChange={(x) => setV({ ...v, rules: x })} placeholder={t('mke.rulesPh')} />
          </Form.Item>
          <Form.Item label={t('mke.sourcePrimary')}>
            <Input value={v.sourcePrimary} onChange={(e) => setV({ ...v, sourcePrimary: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('mke.sourceBackup')}>
            <Input value={v.sourceBackup} onChange={(e) => setV({ ...v, sourceBackup: e.target.value })} />
          </Form.Item>
          {v.options.map((o, i) => (
            <Form.Item key={o.outcome} label={`${t('mkd.option')} ${o.outcome}`}>
              <I18nInputs value={o.label} onChange={(x) => setV({ ...v, options: v.options.map((p, j) => (j === i ? { ...p, label: x } : p)) })} placeholder={t('mke.optLabel')} />
              <I18nInputs value={o.winCondition} onChange={(x) => setV({ ...v, options: v.options.map((p, j) => (j === i ? { ...p, winCondition: x } : p)) })} placeholder={t('mke.optWin')} />
            </Form.Item>
          ))}
        </Form>
      ) : null}
      {hr.element}
    </Drawer>
  );
}

export function AutogenPage() {
  const { t } = useTranslation();
  const list = useApi(useCallback(() => listAutogens(), []));
  const tpls = useApi(useCallback(() => listRuleTemplates(), []));
  const hr = useHighRisk();
  const [editing, setEditing] = useState<RuleTemplate | null>(null);
  const create = () => {
    let input = toInput();
    hr.ask({ title: t('ag.create'), extra: <AutogenForm init={input} onChange={(v) => (input = v)} />, onConfirm: (v) => createAutogen(input, v.reason), onDone: list.reload });
  };
  return (
    <>
      <PageHeader
        title={t('ag.title')}
        sub={t('ag.sub')}
        extra={
          <PermButton perm="market:autogen" type="primary" icon={<PlusOutlined />} onClick={create}>
            {t('ag.create')}
          </PermButton>
        }
      />
      {list.error ? (
        <ErrorState error={list.error} onRetry={list.reload} />
      ) : !list.data ? (
        <Card>
          <Skeleton active />
        </Card>
      ) : list.data.length === 0 ? (
        <Card>
          <Empty description={t('ag.empty')} />
        </Card>
      ) : (
        <div className="ag-list">
          {list.data.map((a) => (
            <AutogenCard key={a.autogenNo} a={a} onDone={list.reload} />
          ))}
        </div>
      )}
      <h2 className="dash-h">{t('ag.templates')}</h2>
      {tpls.error ? (
        <ErrorState error={tpls.error} onRetry={tpls.reload} compact />
      ) : (
        <Card size="small" loading={!tpls.data}>
          {(tpls.data ?? []).map((x) => (
            <div key={x.templateNo} className="ag-tpl">
              <div>
                <b>{x.name}</b> <Tag>{t('ag.version', { v: x.version })}</Tag> {x.usedByEnabled ? <Pill tone="warn">{t('ag.inUse')}</Pill> : null}
                <I18nView value={x.title} />
              </div>
              <PermButton perm="market:autogen" size="small" icon={<EditOutlined />} onClick={() => setEditing(x)}>
                {t('common.edit')}
              </PermButton>
            </div>
          ))}
          {tpls.data && !tpls.data.length ? <Empty description={t('ag.noTemplates')} /> : null}
        </Card>
      )}
      <TemplateDrawer tpl={editing} onClose={() => setEditing(null)} onDone={tpls.reload} />
      {hr.element}
    </>
  );
}
