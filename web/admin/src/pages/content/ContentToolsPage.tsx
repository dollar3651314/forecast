/**
 * 内容中心 → 工具（forecast S15；MF-9 内容数据工具，自动化需求第 4.1 节 #2、#3，AC-113）：
 * - 数据卡：一个市场的奖池（S14 的市场卡）/ BTC 每小时涨跌统计；图片由程序按模板画（不用 AI），印数据来源和查询时间。
 * - 大 V 对照图：输入观点摘要 + 选一个市场（+ 可选的 Polymarket 同类题，公开接口<b>默认关闭</b>，条款待核实）；有 Gemini 时同时起草中英文回复。
 * <b>只有"下载""复制"，没有发布</b>（回复由 Amos 手动发，AC-110）。
 */
import { useCallback, useState } from 'react';
import { App, Alert, Button, Card, Col, Form, Input, Row, Segmented, Select, Space, Table } from 'antd';
import { CopyOutlined, DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { copiedDraft, getContentStatus, makeCompare, makeDataCard } from '../../api';
import type { ToolResult } from '../../api/fcTypes';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { errorText } from '../../utils/errors';
import { copyContent, copyToClipboard, GeminiBanner } from './common';

const LANGS = [
  { value: 'zh-CN', label: '简体' },
  { value: 'zh-TW', label: '繁體' },
  { value: 'en', label: 'EN' },
];

export function ContentToolsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const status = useApi(useCallback(() => getContentStatus(), []));
  const [result, setResult] = useState<ToolResult | null>(null);
  const [busy, setBusy] = useState<'card' | 'compare' | null>(null);
  const [cardKind, setCardKind] = useState<'market' | 'btc'>('market');
  const [cardForm] = Form.useForm<{ marketNo?: string; lang: string }>();
  const [cmpForm] = Form.useForm<{ marketNo: string; viewpoint: string; polymarketSlug?: string; lang: string }>();

  const run = async (which: 'card' | 'compare', fn: () => Promise<ToolResult>) => {
    setBusy(which);
    try {
      setResult(await fn());
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(null);
    }
  };
  const copyText = async (text: string) => {
    await copyToClipboard(text);
    void message.success(t('common.copied'));
  };

  return (
    <>
      <PageHeader title={t('ct.tools.title')} sub={t('ct.tools.sub')} />
      <GeminiBanner />
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('ct.tools.dataCard')} className="ct-card">
            <Form form={cardForm} layout="vertical" initialValues={{ lang: 'zh-CN' }}>
              <Form.Item label={t('ct.tools.cardKind')}>
                <Segmented<'market' | 'btc'>
                  value={cardKind}
                  onChange={setCardKind}
                  options={[
                    { value: 'market', label: t('ct.tools.kindMarket') },
                    { value: 'btc', label: t('ct.tools.kindBtc') },
                  ]}
                />
              </Form.Item>
              {cardKind === 'market' ? (
                <Form.Item name="marketNo" label={t('ct.gen.market')} rules={[{ required: true, message: t('validation.required') }]}>
                  <Input />
                </Form.Item>
              ) : null}
              <Form.Item name="lang" label={t('ct.tools.lang')}>
                <Select options={LANGS} />
              </Form.Item>
              <Button
                type="primary"
                loading={busy === 'card'}
                data-testid="tool-card"
                onClick={async () => {
                  const v = await cardForm.validateFields();
                  void run('card', () => makeDataCard(cardKind, v.marketNo?.trim(), v.lang));
                }}
              >
                {t('ct.tools.make')}
              </Button>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('ct.tools.compare')} className="ct-card">
            <Form form={cmpForm} layout="vertical" initialValues={{ lang: 'zh-CN' }}>
              <Form.Item name="viewpoint" label={t('ct.tools.viewpoint')} rules={[{ required: true, min: 4, max: 200, message: t('ct.tools.viewpointRule') }]}>
                <Input.TextArea rows={2} placeholder={t('ct.tools.viewpointPh')} />
              </Form.Item>
              <Form.Item name="marketNo" label={t('ct.gen.market')} rules={[{ required: true, message: t('validation.required') }]}>
                <Input />
              </Form.Item>
              <Form.Item
                name="polymarketSlug"
                label={t('ct.tools.polymarket')}
                extra={status.data?.polymarketEnabled ? t('ct.tools.polymarketOn') : t('ct.tools.polymarketOff')}
              >
                <Input placeholder="will-btc-close-above-100k" disabled={!status.data?.polymarketEnabled} />
              </Form.Item>
              <Form.Item name="lang" label={t('ct.tools.lang')}>
                <Select options={LANGS} />
              </Form.Item>
              <Button
                type="primary"
                loading={busy === 'compare'}
                data-testid="tool-compare"
                onClick={async () => {
                  const v = await cmpForm.validateFields();
                  void run('compare', () => makeCompare(v.marketNo.trim(), v.viewpoint.trim(), v.polymarketSlug?.trim() || undefined, v.lang));
                }}
              >
                {t('ct.tools.make')}
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>
      {result ? (
        <Card size="small" title={t('ct.tools.result')} className="ct-card" data-testid="tool-result">
          {result.notes.map((n) => (
            <Alert key={n} type="info" showIcon message={n} className="ct-note" />
          ))}
          <Row gutter={[16, 16]}>
            <Col xs={24} lg={14}>
              <img src={result.image} alt={t('ct.tools.imageAlt')} className="ct-tool-image" />
              <Space className="ct-actions">
                <Button icon={<DownloadOutlined />} href={result.image} download="card.png">
                  {t('ct.tools.download')}
                </Button>
                <Button icon={<CopyOutlined />} onClick={() => void copyText(result.text)}>
                  {t('ct.tools.copyText')}
                </Button>
              </Space>
            </Col>
            <Col xs={24} lg={10}>
              <pre className="ct-tool-text">{result.text}</pre>
              {result.replies.map((d) => (
                <div key={d.draftNo} className="card ct-reply">
                  <b>{t('ct.tools.reply', { lang: t(`ct.lang.${d.lang}`) })}</b>
                  <p>{d.body || t('ct.drafts.generating')}</p>
                  {d.checks && !d.checkOk ? <p className="ct-check-bad">{d.checks.summary}</p> : null}
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    disabled={!d.canCopy}
                    onClick={async () => {
                      try {
                        await copiedDraft(d.draftNo);
                        await copyText(copyContent(d));
                      } catch (e) {
                        void message.error(errorText(t, e));
                      }
                    }}
                  >
                    {t('ct.drawer.copy')}
                  </Button>
                </div>
              ))}
            </Col>
          </Row>
          <details className="ct-refs">
            <summary>{t('ct.refs.title', { n: result.refs.length })}</summary>
            <Table size="small" rowKey="key" pagination={false} scroll={{ x: 'max-content' }} dataSource={result.refs}
              columns={[
                { title: t('ct.refs.label'), dataIndex: 'label' },
                { title: t('ct.refs.value'), dataIndex: 'value' },
                { title: t('ct.refs.source'), dataIndex: 'source' },
              ]}
            />
          </details>
        </Card>
      ) : null}
    </>
  );
}
