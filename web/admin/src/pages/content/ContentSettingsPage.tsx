/**
 * 内容中心 → 用量和设置（forecast S15；自动化需求第 6 节、第 7 节"设置"、AC-115、AC-117）：
 * - 用量：Gemini 是否配置（只显示"已配置 / 未配置"，不显示 key）、今天用了多少（文章起草 / 比分解析分开）、本月估算费用。
 * - 设置：每天上限、文章起草占比、费用提醒线、AI 起草开关、每日草稿包、每天新页面上限、每周文章上限、经济日历提前天数。
 *   改设置是高危（content:publish），写审计。没有"自动发布"开关（EQ-1）。
 * - 紧急停止：一键停掉草稿箱的发布和复制（草稿照常生成）。Telegram 开奖卡的自动发布有自己的紧急停止（"Telegram 机器人"页）。
 * - 词表：禁用词（保存时检查）、倾向词（EQ-8）、敏感词（事件过滤）；内置词不能删。
 * - 发布渠道（整合 4，S16 开发记录第 7 节做法 ①）：Telegram 频道的开关、模板审定、紧急停止在内容中心的"Telegram 机器人"页（S16，经
 *   growth.api.TelegramAdminApi），这里只读显示状态并给入口；X 没有自动发布，只能复制后手动发。
 */
import { useCallback, useEffect, useState } from 'react';
import { App, Alert, Card, Col, Descriptions, Form, Input, InputNumber, Progress, Row, Segmented, Space, Switch, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { addContentWord, deleteContentWord, getContentStatus, getTelegram, listContentWords, setContentStopped, updateContentSettings } from '../../api';
import type { ContentWordKind } from '../../api/fcTypes';
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { utc } from '../../utils/market';
import { GeminiBanner } from './common';

/** 能改的设置项和取值范围（和后端 content.core.Settings.RANGES 一致；紧急停止单独一个按钮） */
const FIELDS: Array<{ key: string; min: number; max: number; flag?: boolean }> = [
  { key: 'ai.dailyLimit', min: 0, max: 10000 },
  { key: 'ai.contentSharePct', min: 0, max: 100 },
  { key: 'ai.costAlertUsd', min: 0, max: 10000 },
  { key: 'ai.drafts', min: 0, max: 1, flag: true },
  { key: 'pack.enabled', min: 0, max: 1, flag: true },
  { key: 'pages.dailyNew', min: 0, max: 50 },
  { key: 'articles.weekly', min: 0, max: 20 },
  { key: 'econ.leadDays', min: 1, max: 30 },
];
const WORD_KINDS: ContentWordKind[] = ['BANNED', 'TENDENCY', 'SENSITIVE'];
const fieldName = (k: string) => k.replace('.', '_');

export function ContentSettingsPage() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi(useCallback(() => getContentStatus(), []));
  const [form] = Form.useForm<Record<string, number | boolean>>();
  const hr = useHighRisk();

  useEffect(() => {
    if (!data) return;
    const v: Record<string, number | boolean> = {};
    for (const f of FIELDS) {
      const raw = Number(data.settings[f.key] ?? 0);
      v[fieldName(f.key)] = f.flag ? raw === 1 : raw;
    }
    form.setFieldsValue(v);
  }, [data, form]);

  if (error) return <ErrorState error={error} onRetry={reload} />;

  const save = async () => {
    const v = await form.validateFields();
    const values: Record<string, string> = {};
    for (const f of FIELDS) {
      const x = v[fieldName(f.key)];
      const s = f.flag ? (x ? '1' : '0') : String(x);
      if (data && data.settings[f.key] !== s) values[f.key] = s;
    }
    if (!Object.keys(values).length) return;
    hr.ask({
      title: t('ct.settings.saveTitle'),
      description: (
        <ul className="ct-diff">
          {Object.entries(values).map(([k, s]) => (
            <li key={k}>
              {t(`ct.setting.${fieldName(k)}`)}：{data?.settings[k]} → <b>{s}</b>
            </li>
          ))}
        </ul>
      ),
      onConfirm: (r) => updateContentSettings(values, r.reason),
      onDone: reload,
    });
  };

  const pct = (a: number, b: number) => (b > 0 ? Math.min(100, Math.round((a * 100) / b)) : 0);

  return (
    <>
      <PageHeader title={t('ct.settings.title')} sub={t('ct.settings.sub')} />
      <GeminiBanner />
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('ct.settings.usage')} className="ct-card" loading={loading && !data} data-testid="content-usage">
            {data ? (
              <>
                <Descriptions size="small" column={1} bordered>
                  <Descriptions.Item label={t('ct.settings.gemini')}>
                    {data.geminiConfigured ? <Tag color="green">{t('ct.settings.configured')}</Tag> : <Tag color="orange">{t('ct.settings.unconfigured')}</Tag>}
                    <span className="muted">{data.model}</span>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.state')}>
                    {t(`ct.geminiState.${data.geminiState}`)}
                    {data.pausedUntil ? <span className="muted">（{t('ct.settings.pausedUntil', { at: utc(data.pausedUntil) })}）</span> : null}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.today')}>
                    <Progress percent={pct(data.usedToday, data.limit)} size="small" format={() => `${data.usedToday} / ${data.limit}`} />
                    <div className="muted">
                      {t('ct.settings.split', { content: data.contentUsedToday, cap: data.contentCap, score: data.scoreUsedToday })}
                    </div>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.failures')}>{data.failuresToday}</Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.month')}>
                    {t('ct.settings.monthCost', { calls: data.monthCalls, cost: data.monthCostUsd, alert: data.costAlertUsd })}
                    {data.costAlert ? <Tag color="red" className="ct-tag-gap">{t('ct.settings.costOver')}</Tag> : null}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.pages')}>
                    {t('ct.settings.pagesUsed', { n: data.pagesToday, max: data.pagesDaily })} · {t('ct.settings.articlesUsed', { n: data.articlesThisWeek, max: data.articlesWeekly })}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('ct.settings.queue')}>{data.queue}</Descriptions.Item>
                </Descriptions>
                <p className="muted ct-note">{t('ct.settings.keyHint')}</p>
              </>
            ) : null}
          </Card>
          <Card size="small" title={t('ct.settings.stop')} className="ct-card">
            <p className="muted">{t('ct.settings.stopHint')}</p>
            {data?.stopped ? <Alert type="error" showIcon message={t('ct.banner.stopped')} className="ct-note" /> : null}
            <PermButton
              perm="content:publish"
              danger={!data?.stopped}
              type={data?.stopped ? 'default' : 'primary'}
              data-testid="content-stop"
              disabled={!data}
              onClick={() =>
                hr.ask({
                  title: data?.stopped ? t('ct.settings.resume') : t('ct.settings.stopNow'),
                  description: data?.stopped ? t('ct.settings.resumeDesc') : t('ct.settings.stopDesc'),
                  onConfirm: (r) => setContentStopped(!data?.stopped, r.reason),
                  onDone: reload,
                })
              }
            >
              {data?.stopped ? t('ct.settings.resume') : t('ct.settings.stopNow')}
            </PermButton>
          </Card>
          <ChannelsCard />
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('ct.settings.settings')} className="ct-card">
            <Form form={form} layout="vertical">
              <Row gutter={12}>
                {FIELDS.map((f) => (
                  <Col xs={24} sm={12} key={f.key}>
                    <Form.Item
                      name={fieldName(f.key)}
                      label={t(`ct.setting.${fieldName(f.key)}`)}
                      extra={t(`ct.settingHint.${fieldName(f.key)}`)}
                      valuePropName={f.flag ? 'checked' : 'value'}
                      rules={f.flag ? [] : [{ required: true, type: 'number', min: f.min, max: f.max, message: `${f.min}–${f.max}` }]}
                    >
                      {f.flag ? <Switch /> : <InputNumber min={f.min} max={f.max} precision={0} style={{ width: '100%' }} />}
                    </Form.Item>
                  </Col>
                ))}
              </Row>
              <Alert type="info" showIcon message={t('ct.settings.noAutoPublish')} className="ct-note" />
              <PermButton perm="content:publish" type="primary" onClick={() => void save()} data-testid="content-settings-save">
                {t('common.save')}
              </PermButton>
            </Form>
          </Card>
          <WordsCard />
        </Col>
      </Row>
      {hr.element}
    </>
  );
}

/** 发布渠道：X 只能手动；Telegram 频道的状态来自 S16 的机器人（只读，改动去"Telegram 机器人"页） */
function ChannelsCard() {
  const { t } = useTranslation();
  const { data, error, reload } = useApi(useCallback(() => getTelegram(), []));
  return (
    <Card size="small" title={t('ct.settings.channels')} className="ct-card" data-testid="content-channels">
      <Alert type="info" showIcon message={t('ct.settings.channelsTodo')} />
      <Space direction="vertical" className="ct-channels">
        <Space wrap>
          <Switch disabled checked={false} />
          <span>X</span>
          <span className="muted">{t('ct.settings.channelManual')}</span>
        </Space>
        {error ? <ErrorState error={error} onRetry={reload} compact /> : null}
        {(data?.targets ?? []).map((g) => (
          <Space wrap key={g.key}>
            <Switch disabled checked={g.enabled && !data?.status.emergencyStop} />
            <span>{t(`ct.settings.tg.${g.key}`)}</span>
            {g.enabled ? <Tag color="green">{t('ct.settings.tg.on')}</Tag> : <Tag>{t('ct.settings.tg.off')}</Tag>}
            {g.chatId ? <span className="muted">{g.chatId}</span> : null}
          </Space>
        ))}
        {data && !data.status.configured ? <Tag color="orange">{t('ct.settings.tg.unconfigured')}</Tag> : null}
        {data?.status.emergencyStop ? <Tag color="red">{t('ct.settings.tg.stopped')}</Tag> : null}
        <Link to="/content/telegram" data-testid="content-channels-telegram">
          {t('ct.settings.tg.manage')}
        </Link>
      </Space>
    </Card>
  );
}

function WordsCard() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [kind, setKind] = useState<ContentWordKind>('BANNED');
  const [word, setWord] = useState('');
  const { data, loading, error, reload } = useApi(useCallback(() => listContentWords(kind), [kind]));
  const hr = useHighRisk();
  return (
    <Card size="small" title={t('ct.words.title')} className="ct-card">
      <Segmented<ContentWordKind> value={kind} onChange={setKind} options={WORD_KINDS.map((k) => ({ value: k, label: t(`ct.words.kind.${k}`) }))} />
      <p className="muted ct-note">{t(`ct.words.hint.${kind}`)}</p>
      {error ? <ErrorState error={error} onRetry={reload} compact /> : null}
      <div className="ct-words" aria-busy={loading}>
        {(data ?? []).map((w) => (
          <Tag
            key={w.id}
            color={w.builtIn ? 'default' : 'blue'}
            closable={!w.builtIn}
            onClose={(e) => {
              e.preventDefault();
              hr.ask({
                title: t('ct.words.delete', { word: w.word }),
                onConfirm: (r) => deleteContentWord(w.id, r.reason),
                onDone: reload,
              });
            }}
          >
            {w.word}
          </Tag>
        ))}
      </div>
      <Space.Compact className="ct-word-add">
        <Input value={word} maxLength={40} onChange={(e) => setWord(e.target.value)} placeholder={t('ct.words.placeholder')} />
        <PermButton
          perm="content:publish"
          onClick={() => {
            const w = word.trim();
            if (!w) {
              void message.warning(t('validation.required'));
              return;
            }
            hr.ask({
              title: t('ct.words.add', { word: w }),
              onConfirm: (r) => addContentWord(kind, w, r.reason),
              onDone: () => {
                setWord('');
                reload();
              },
            });
          }}
        >
          {t('ct.words.addBtn')}
        </PermButton>
      </Space.Compact>
      {hr.element}
    </Card>
  );
}
