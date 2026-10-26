/**
 * 内容中心 → 草稿箱（forecast S15；自动化需求第 7 节"草稿箱"、第 4.3 节写作红线、EQ-1、AC-106～AC-111）。
 * 列表：种类、语言、状态、检查结果（数字核对、禁用词）、生成和过期时间；待审核的在最前。
 * 详情（抽屉）：中英文对照，正文可改（禁用词不能保存），对不上的数字标红，"引用的数据"清单；发布（content:publish，高危：原因已填"审核通过"）、
 * 驳回（原因用来改写作规则）、重新起草、复制。<b>没有自动发布</b>；X、Telegram 帖子审核后只能复制、手动发；回复草稿只能复制。
 */
import { useCallback, useEffect, useState } from 'react';
import { App, Button, Descriptions, Drawer, Empty, Form, Grid, Input, Modal, Segmented, Select, Space, Table, Tag, Typography } from 'antd';
import { CheckCircleFilled, CopyOutlined, ExclamationCircleFilled, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { copiedDraft, editDraft, generateContentDrafts, getDraftGroup, listDrafts, publishDraft, rejectDraft, retryDraft } from '../../api';
import { CONTENT_KINDS, GENERATE_KINDS, type ContentDraft, type ContentKind, type DraftStatus } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { useApi } from '../../hooks/useApi';
import { useAction, useHighRisk, useNoteDialog } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';
import { copyContent, copyToClipboard, DraftStatusPill, GeminiBanner, Highlighted, KindTag } from './common';

type StatusFilter = DraftStatus | 'all';
const FILTERS: StatusFilter[] = ['PENDING', 'all', 'PUBLISHED', 'FAILED', 'REJECTED', 'EXPIRED'];

export function ContentDraftsPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<StatusFilter>('PENDING');
  const [kind, setKind] = useState<ContentKind | undefined>();
  const [lang, setLang] = useState<'zh' | 'en' | undefined>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [open, setOpen] = useState<string | null>(null);
  const [gen, setGen] = useState(false);
  const { data, loading, error, reload } = useApi(
    useCallback(() => listDrafts({ status: status === 'all' ? undefined : status, kind, lang, page, pageSize: size }), [status, kind, lang, page, size]),
  );

  return (
    <>
      <PageHeader
        title={t('ct.drafts.title')}
        sub={t('ct.drafts.sub')}
        extra={
          <PermButton perm="content:edit" type="primary" icon={<PlusOutlined />} onClick={() => setGen(true)} data-testid="ct-generate">
            {t('ct.drafts.generate')}
          </PermButton>
        }
      />
      <GeminiBanner />
      <div className="filter-bar ct-filters">
        <Segmented<StatusFilter>
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          options={FILTERS.map((f) => ({ value: f, label: f === 'all' ? t('common.all') : t(`ct.status.${f}`) }))}
        />
        <Select<ContentKind>
          allowClear
          placeholder={t('ct.drafts.kind')}
          style={{ minWidth: 150 }}
          value={kind}
          onChange={(v) => {
            setKind(v);
            setPage(1);
          }}
          options={CONTENT_KINDS.map((k) => ({ value: k, label: t(`ct.kind.${k}`) }))}
        />
        <Select<'zh' | 'en'>
          allowClear
          placeholder={t('ct.drafts.lang')}
          style={{ minWidth: 110 }}
          value={lang}
          onChange={(v) => {
            setLang(v);
            setPage(1);
          }}
          options={[
            { value: 'zh', label: t('ct.lang.zh') },
            { value: 'en', label: t('ct.lang.en') },
          ]}
        />
      </div>
      <DataTable<ContentDraft>
        rowKey="draftNo"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        emptyText={t('ct.drafts.empty')}
        onPageChange={(p, s) => {
          setPage(p);
          setSize(s);
        }}
        onRow={(d) => ({ onClick: () => setOpen(d.draftNo), style: { cursor: 'pointer' } })}
        columns={[
          { title: t('ct.drafts.kind'), key: 'kind', render: (_, d) => <KindTag kind={d.kind} /> },
          { title: t('ct.drafts.lang'), dataIndex: 'lang', render: (v: string) => t(`ct.lang.${v}`) },
          {
            title: t('ct.drafts.titleCol'),
            key: 'title',
            render: (_, d) => (
              <a onClick={() => setOpen(d.draftNo)} data-testid={`draft-${d.draftNo}`}>
                {d.title || (d.status === 'GENERATING' ? t('ct.drafts.generating') : '—')}
              </a>
            ),
          },
          { title: t('ct.drafts.status'), key: 'status', render: (_, d) => <DraftStatusPill status={d.status} /> },
          { title: t('ct.drafts.checks'), key: 'checks', render: (_, d) => <CheckBadge d={d} /> },
          { title: t('ct.drafts.target'), key: 'target', render: (_, d) => d.topic ?? d.pageSlug ?? d.marketNo ?? '—' },
          { title: t('ct.drafts.created'), dataIndex: 'createdAt', render: (v: string) => utc(v) },
          { title: t('ct.drafts.expires'), dataIndex: 'expiresAt', render: (v: string | null, d) => (d.expired ? <Tag>{t('ct.status.EXPIRED')}</Tag> : utc(v)) },
        ]}
      />
      {open ? <DraftDrawer draftNo={open} onClose={() => setOpen(null)} onChanged={reload} /> : null}
      <GenerateModal
        open={gen}
        onClose={() => setGen(false)}
        onDone={(first) => {
          setGen(false);
          reload();
          setOpen(first);
        }}
      />
    </>
  );
}

function CheckBadge({ d }: { d: ContentDraft }) {
  const { t } = useTranslation();
  if (d.status === 'GENERATING' || !d.checks) return <span className="muted">—</span>;
  if (d.checkOk) {
    return (
      <span className="ct-check-ok">
        <CheckCircleFilled /> {t('ct.checks.ok')}
      </span>
    );
  }
  const n = d.checks.numbers.length + d.checks.banned.length + d.checks.tendency.length + (d.checks.polymarketNoteMissing ? 1 : 0)
    + (d.checks.actualLength > d.checks.maxLength ? 1 : 0) + d.checks.missing.length;
  return (
    <span className="ct-check-bad">
      <ExclamationCircleFilled /> {t('ct.checks.bad', { n })}
    </span>
  );
}

function DraftDrawer({ draftNo, onClose, onChanged }: { draftNo: string; onClose: () => void; onChanged: () => void }) {
  const { t } = useTranslation();
  const screens = Grid.useBreakpoint();
  const { data, loading, error, reload } = useApi(useCallback(() => getDraftGroup(draftNo), [draftNo]));
  const changed = () => {
    reload();
    onChanged();
  };
  // 还在生成（后台排队调 Gemini）：每 2 秒刷新一次，生成完列表也跟着刷新
  const generating = (data ?? []).some((d) => d.status === 'GENERATING');
  useEffect(() => {
    if (!generating) return undefined;
    const id = window.setTimeout(() => {
      reload();
      onChanged();
    }, 2000);
    return () => window.clearTimeout(id);
  }, [generating, data, reload, onChanged]);
  return (
    <Drawer open onClose={onClose} width={screens.lg ? 1100 : '100%'} title={t('ct.drawer.title')} destroyOnClose>
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : loading && !data ? null : (
        <div className="ct-group">
          {(data ?? []).map((d) => (
            <DraftEditor key={`${d.draftNo}-${d.version}`} d={d} onChanged={changed} />
          ))}
        </div>
      )}
    </Drawer>
  );
}

function DraftEditor({ d, onChanged }: { d: ContentDraft; onChanged: () => void }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [title, setTitle] = useState(d.title);
  const [body, setBody] = useState(d.body);
  const hr = useHighRisk();
  const note = useNoteDialog();
  const act = useAction();
  useEffect(() => {
    setTitle(d.title);
    setBody(d.body);
  }, [d.title, d.body]);
  const editable = d.status === 'PENDING' || d.status === 'FAILED';
  const dirty = title !== d.title || body !== d.body;
  const hits = d.checks?.numbers ?? [];

  const copy = async () => {
    try {
      await copiedDraft(d.draftNo);
      await copyToClipboard(copyContent(d));
      void message.success(t('ct.drawer.copied'));
      onChanged();
    } catch (err) {
      void message.error(errorText(t, err));
    }
  };

  return (
    <section className="ct-editor card" data-testid={`editor-${d.lang}`}>
      <header className="ct-editor-head">
        <Space wrap>
          <Tag color="geekblue">{t(`ct.lang.${d.lang}`)}</Tag>
          <KindTag kind={d.kind} />
          <DraftStatusPill status={d.status} />
          {d.expiresAt ? <span className="muted">{t('ct.drawer.expires', { at: utc(d.expiresAt) })}</span> : null}
        </Space>
      </header>
      {d.failReason ? <Typography.Paragraph type="danger">{t('ct.drawer.failed', { reason: d.failReason })}</Typography.Paragraph> : null}
      {d.rejectReason ? <Typography.Paragraph type="secondary">{t('ct.drawer.rejected', { reason: d.rejectReason })}</Typography.Paragraph> : null}
      {d.status === 'GENERATING' ? (
        <Empty description={t('ct.drafts.generating')} />
      ) : (
        <>
          <Form layout="vertical">
            <Form.Item label={t('ct.drawer.title2')}>
              <Input value={title} onChange={(e) => setTitle(e.target.value)} disabled={!editable} aria-label={`${t('ct.drawer.title2')} ${d.lang}`} />
            </Form.Item>
            <Form.Item label={t('ct.drawer.body')} extra={d.checks ? t('ct.drawer.length', { n: d.checks.actualLength, max: d.checks.maxLength }) : undefined}>
              <Input.TextArea rows={7} value={body} onChange={(e) => setBody(e.target.value)} disabled={!editable} aria-label={`${t('ct.drawer.body')} ${d.lang}`} />
            </Form.Item>
          </Form>
          <div className="ct-preview" aria-label={t('ct.drawer.preview')}>
            <b>{t('ct.drawer.preview')}</b>
            <p>
              <Highlighted text={d.body} hits={hits.filter((h) => h.field === 'body')} />
            </p>
            {d.link ? <p className="muted">{d.link}</p> : null}
          </div>
          <ChecksList d={d} />
        </>
      )}
      <Space wrap className="ct-actions">
        {editable ? (
          <PermButton
            perm="content:edit"
            disabled={!dirty}
            onClick={() => void act(() => editDraft(d.draftNo, title, body, d.version), t('ct.drawer.saved'), onChanged)}
            data-testid={`save-${d.lang}`}
          >
            {t('common.save')}
          </PermButton>
        ) : null}
        {d.kind !== 'REPLY' ? (
          <PermButton
            perm="content:publish"
            type="primary"
            disabled={!d.canPublish || dirty}
            disabledReason={dirty ? t('ct.drawer.saveFirst') : t('ct.drawer.cannotPublish')}
            data-testid={`publish-${d.lang}`}
            onClick={() =>
              hr.ask({
                title: t(d.copyOnly ? 'ct.drawer.approveTitle' : 'ct.drawer.publishTitle'),
                description: t(d.copyOnly ? 'ct.drawer.approveDesc' : 'ct.drawer.publishDesc'),
                initialValues: { reason: t('ct.drawer.defaultReason') },
                onConfirm: (v) => publishDraft(d.draftNo, v.reason),
                onDone: onChanged,
              })
            }
          >
            {t(d.copyOnly ? 'ct.drawer.approve' : 'ct.drawer.publish')}
          </PermButton>
        ) : null}
        {d.copyOnly || d.kind === 'PREVIEW' ? (
          <Button icon={<CopyOutlined />} disabled={!d.canCopy} onClick={() => void copy()} data-testid={`copy-${d.lang}`}>
            {t('ct.drawer.copy')}
          </Button>
        ) : null}
        {editable ? (
          <PermButton
            perm="content:edit"
            danger
            onClick={() =>
              note.ask({
                title: t('ct.drawer.reject'),
                label: t('ct.drawer.rejectLabel'),
                placeholder: t('ct.drawer.rejectPh'),
                danger: true,
                onConfirm: (n) => rejectDraft(d.draftNo, n),
                onDone: onChanged,
              })
            }
          >
            {t('ct.drawer.reject')}
          </PermButton>
        ) : null}
        {d.status === 'FAILED' || d.status === 'REJECTED' ? (
          <PermButton perm="content:edit" onClick={() => void act(() => retryDraft(d.draftNo), t('ct.drawer.retried'), onChanged)}>
            {t('ct.drawer.retry')}
          </PermButton>
        ) : null}
        {d.siteUrl ? (
          <a href={d.siteUrl} target="_blank" rel="noreferrer">
            {t('ct.drawer.site')}
          </a>
        ) : null}
      </Space>
      <RefsTable d={d} />
      {hr.element}
      {note.element}
    </section>
  );
}

function ChecksList({ d }: { d: ContentDraft }) {
  const { t } = useTranslation();
  const c = d.checks;
  if (!c) return null;
  if (d.checkOk) return <p className="ct-check-ok">{t('ct.checks.allOk')}</p>;
  return (
    <ul className="ct-check-list" data-testid={`checks-${d.lang}`}>
      {c.numbers.length ? <li>{t('ct.checks.numbers', { list: c.numbers.map((n) => n.text).join('、') })}</li> : null}
      {c.banned.length ? <li>{t('ct.checks.banned', { list: c.banned.join('、') })}</li> : null}
      {c.tendency.length ? <li>{t('ct.checks.tendency', { list: c.tendency.join('、') })}</li> : null}
      {c.polymarketNoteMissing ? <li>{t('ct.checks.polymarket')}</li> : null}
      {c.actualLength > c.maxLength ? <li>{t('ct.checks.length', { n: c.actualLength, max: c.maxLength })}</li> : null}
      {c.missing.length ? <li>{t('ct.checks.missing', { list: c.missing.join('、') })}</li> : null}
    </ul>
  );
}

function RefsTable({ d }: { d: ContentDraft }) {
  const { t } = useTranslation();
  return (
    <details className="ct-refs">
      <summary>{t('ct.refs.title', { n: d.refs.length })}</summary>
      <Table
        size="small"
        rowKey="key"
        pagination={false}
        scroll={{ x: 'max-content' }}
        dataSource={d.refs}
        columns={[
          { title: t('ct.refs.label'), dataIndex: d.lang === 'en' ? 'labelEn' : 'label' },
          { title: t('ct.refs.value'), dataIndex: 'value' },
          { title: t('ct.refs.source'), dataIndex: 'source' },
          { title: t('ct.refs.at'), dataIndex: 'at', render: (v: string) => utc(v) },
        ]}
      />
    </details>
  );
}

function GenerateModal({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (firstDraftNo: string) => void }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ kind: ContentKind; marketNo?: string; pageSlug?: string; topic?: string }>();
  const kind = Form.useWatch('kind', form);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const submit = async () => {
    const v = await form.validateFields();
    setBusy(true);
    setErr(null);
    try {
      const made = await generateContentDrafts({ kind: v.kind, marketNo: v.marketNo?.trim() || undefined, pageSlug: v.pageSlug?.trim() || undefined, topic: v.topic?.trim() || undefined });
      form.resetFields();
      onDone(made[0].draftNo);
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} title={t('ct.gen.title')} onCancel={onClose} onOk={() => void submit()} okText={t('ct.gen.ok')} confirmLoading={busy} destroyOnClose>
      <p className="muted">{t('ct.gen.note')}</p>
      <Form form={form} layout="vertical" initialValues={{ kind: 'PREVIEW' }}>
        <Form.Item name="kind" label={t('ct.drafts.kind')} rules={[{ required: true }]}>
          <Select options={GENERATE_KINDS.map((k) => ({ value: k, label: t(`ct.kind.${k}`) }))} />
        </Form.Item>
        {kind === 'ARTICLE' ? (
          <Form.Item name="topic" label={t('ct.gen.topic')} rules={[{ required: true, message: t('validation.required') }]} extra={t('ct.gen.topicHint')}>
            <Input maxLength={200} />
          </Form.Item>
        ) : kind === 'SEO_INTRO' ? (
          <Form.Item name="pageSlug" label={t('ct.gen.page')} rules={[{ required: true, message: t('validation.required') }]} extra={t('ct.gen.pageHint')}>
            <Input placeholder="btc-hourly" />
          </Form.Item>
        ) : (
          <Form.Item name="marketNo" label={t('ct.gen.market')} rules={[{ required: true, message: t('validation.required') }]} extra={t(`ct.gen.marketHint.${kind ?? 'PREVIEW'}`)}>
            <Input />
          </Form.Item>
        )}
      </Form>
      {err ? <Typography.Paragraph type="danger">{errorText(t, err)}</Typography.Paragraph> : null}
      <Descriptions size="small" column={1} items={[{ key: 'n', label: t('ct.gen.langs'), children: t('ct.gen.langsValue') }]} />
    </Modal>
  );
}
