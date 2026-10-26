/**
 * 官方 Telegram 机器人（forecast S16，MF-7、MQ-2；需求第 6.3 节；后端 /api/admin/telegram/**，content:view 查看、content:publish ★ 改动）。
 * - 机器人状态：有没有配置 FC_TELEGRAM_BOT_TOKEN（token 只由 Amos 填在服务器上，这里不显示也不能填）、用户名、最近的错误；紧急停止 / 恢复（页头，C79）。
 * - 官方频道（中文、英文）：频道编号、开关（默认关；连续 3 次发送失败自动关）。
 * - 自动发布的模板（SQ-22：频道只推开奖卡）：草稿 / 已审定 / 已停用；预览（最近结束的一个真实市场生成的图片和文字）后审定，审定以后模板文字改过要重新审定。
 * - 机器人所在的群：群管理员在群里 /broadcast_on 开启播报；这里只能关闭。
 * - 发布日志：时间、市场、渠道、结果、帖子链接、没发 / 失败的原因。
 * 和 S15"内容中心"的关系：整合时这一页并进内容中心的"发布渠道"（开发记录 S16 第 4.6 节）。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Card, Descriptions, Form, Input, Modal, Segmented, Space, Switch, Table, Typography } from 'antd';
import { PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  approveTelegramTemplate,
  disableTelegramTemplate,
  getTelegram,
  getTelegramPreview,
  listTelegramPosts,
  setTelegramStop,
  stopTelegramGroup,
  telegramPreviewUrl,
  updateTelegramTarget,
} from '../../api';
import type { TelegramGroup, TelegramPost, TelegramPostStatus, TelegramPreview, TelegramTarget, TelegramTemplate } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill, type PillTone } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

const POST_TONE: Record<TelegramPostStatus, PillTone> = { pending: 'pend', sent: 'ok', failed: 'danger', skipped: 'muted', stopped: 'warn' };
const POST_STATUSES: TelegramPostStatus[] = ['sent', 'pending', 'failed', 'skipped', 'stopped'];
const TPL_TONE: Record<TelegramTemplate['status'], PillTone> = { draft: 'pend', approved: 'ok', disabled: 'muted' };

export function TelegramPage() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi(useCallback(() => getTelegram(), []));
  const [postStatus, setPostStatus] = useState<TelegramPostStatus | 'all'>('all');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const posts = useApi(useCallback(() => listTelegramPosts(postStatus === 'all' ? undefined : postStatus, page, size), [postStatus, page, size]));
  const hr = useHighRisk();
  const [preview, setPreview] = useState<{ tpl: TelegramTemplate; p?: TelegramPreview; err?: unknown } | null>(null);

  const refresh = () => {
    reload();
    posts.reload();
  };
  const st = data?.status;

  const askStop = (stop: boolean) =>
    hr.ask({
      title: stop ? t('tg.stopTitle') : t('tg.resumeTitle'),
      description: stop ? t('tg.stopDesc') : t('tg.resumeDesc'),
      confirmText: stop ? t('tg.stopBtn') : t('tg.resumeBtn'),
      onConfirm: (v) => setTelegramStop(stop, v.reason),
      onDone: refresh,
    });

  const askTarget = (tg: TelegramTarget) =>
    hr.ask({
      title: t('tg.editTarget', { name: t(`tg.target.${tg.key}`) }),
      description: t('tg.targetDesc'),
      initialValues: { chatId: tg.chatId ?? '', enabled: tg.enabled },
      extra: (
        <>
          <Form.Item name="chatId" label={t('tg.chatId')} extra={t('tg.chatIdHint')}>
            <Input placeholder="@quickcome_cn" autoComplete="off" />
          </Form.Item>
          <Form.Item name="enabled" label={t('tg.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </>
      ),
      onConfirm: (v) => updateTelegramTarget(tg.key, String(v.chatId ?? '').trim() || null, !!v.enabled, v.reason),
      onDone: refresh,
    });

  const openPreview = async (tpl: TelegramTemplate) => {
    setPreview({ tpl });
    try {
      setPreview({ tpl, p: await getTelegramPreview(tpl.kind, tpl.lang) });
    } catch (e) {
      setPreview({ tpl, err: e });
    }
  };

  const askApprove = (tpl: TelegramTemplate, p: TelegramPreview) =>
    hr.ask({
      title: t('tg.approveTitle', { lang: t(`tg.lang.${tpl.lang}`) }),
      description: t('tg.approveDesc'),
      confirmText: t('tg.approveBtn'),
      onConfirm: (v) => approveTelegramTemplate(tpl.kind, tpl.lang, p.fingerprint, v.reason),
      onDone: () => {
        setPreview(null);
        refresh();
      },
    });

  const askDisable = (tpl: TelegramTemplate) =>
    hr.ask({
      title: t('tg.disableTitle', { lang: t(`tg.lang.${tpl.lang}`) }),
      description: t('tg.disableDesc'),
      onConfirm: (v) => disableTelegramTemplate(tpl.kind, tpl.lang, v.reason),
      onDone: refresh,
    });

  const askGroupOff = (g: TelegramGroup) =>
    hr.ask({
      title: t('tg.groupOffTitle', { name: g.title || g.chatId }),
      description: t('tg.groupOffDesc'),
      onConfirm: (v) => stopTelegramGroup(g.chatId, v.reason),
      onDone: refresh,
    });

  return (
    <>
      <PageHeader
        title={t('tg.title')}
        sub={t('tg.sub')}
        extra={
          st ? (
            st.emergencyStop ? (
              <PermButton perm="content:publish" type="primary" icon={<PlayCircleOutlined />} onClick={() => askStop(false)} data-testid="tg-resume">
                {t('tg.resumeBtn')}
              </PermButton>
            ) : (
              <PermButton perm="content:publish" danger icon={<PauseCircleOutlined />} onClick={() => askStop(true)} data-testid="tg-stop">
                {t('tg.stopBtn')}
              </PermButton>
            )
          ) : null
        }
      />
      {error ? (
        <ErrorState error={error} onRetry={reload} />
      ) : (
        <>
          {st && !st.configured ? <Alert type="warning" showIcon className="gr-note" data-testid="tg-unconfigured" message={t('tg.unconfigured')} description={t('tg.unconfiguredHint')} /> : null}
          {st?.emergencyStop ? (
            <Alert type="error" showIcon className="gr-note" data-testid="tg-stopped" message={t('tg.stoppedInfo', { at: utc(st.stopAt ?? ''), reason: st.stopReason ?? '' })} />
          ) : null}
          <Card size="small" title={t('tg.bot')} className="gr-card" loading={loading && !data}>
            {st ? (
              <Descriptions size="small" column={{ xs: 1, md: 3 }}>
                <Descriptions.Item label={t('tg.token')}>
                  {st.configured ? <Pill tone="ok">{t('tg.configured')}</Pill> : <Pill tone="danger">{t('tg.notConfigured')}</Pill>}
                </Descriptions.Item>
                <Descriptions.Item label={t('tg.username')}>{st.botUsername ? <Num>@{st.botUsername}</Num> : <span className="muted">—</span>}</Descriptions.Item>
                <Descriptions.Item label={t('tg.health')}>
                  {st.healthy ? <Pill tone="ok">{t('tg.healthy')}</Pill> : <Pill tone="warn">{t('tg.unhealthy')}</Pill>}
                  {st.lastError && !st.healthy ? <span className="muted"> {st.lastError}</span> : null}
                </Descriptions.Item>
              </Descriptions>
            ) : null}
          </Card>

          <Card size="small" title={t('tg.channels')} className="gr-card">
            <Table<TelegramTarget>
              size="middle"
              rowKey="key"
              pagination={false}
              scroll={{ x: 'max-content' }}
              dataSource={data?.targets}
              loading={loading && !data}
              columns={[
                { title: t('tg.channel'), dataIndex: 'key', render: (v: string) => t(`tg.target.${v}`) },
                { title: t('tg.chatId'), dataIndex: 'chatId', render: (v: string | null) => (v ? <Num>{v}</Num> : <span className="muted">{t('tg.notSet')}</span>) },
                {
                  title: t('tg.enabled'),
                  dataIndex: 'enabled',
                  render: (v: boolean, r) => (
                    <span>
                      <Pill tone={v ? 'ok' : 'muted'}>{v ? t('tg.on') : t('tg.off')}</Pill>
                      {r.disabledReason ? <span className="muted"> {r.disabledReason}</span> : null}
                    </span>
                  ),
                },
                { title: t('tg.failStreak'), dataIndex: 'failStreak', align: 'right', render: (v: number) => <Num>{v}</Num> },
                {
                  title: t('common.actions'),
                  key: 'op',
                  render: (_, r) => (
                    <PermButton perm="content:publish" size="small" onClick={() => askTarget(r)} data-testid={`tg-edit-${r.key}`}>
                      {t('tg.edit')}
                    </PermButton>
                  ),
                },
              ]}
            />
          </Card>

          <Card size="small" title={t('tg.templates')} className="gr-card">
            <Typography.Paragraph type="secondary">{t('tg.templatesHint')}</Typography.Paragraph>
            <Table<TelegramTemplate>
              size="middle"
              rowKey={(r) => `${r.kind}-${r.lang}`}
              pagination={false}
              scroll={{ x: 'max-content' }}
              dataSource={data?.templates}
              loading={loading && !data}
              columns={[
                { title: t('tg.tplKind'), dataIndex: 'kind', render: () => t('tg.kind.RESULT') },
                { title: t('tg.tplLang'), dataIndex: 'lang', render: (v: string) => t(`tg.lang.${v}`) },
                {
                  title: t('tg.tplStatus'),
                  dataIndex: 'status',
                  render: (v: TelegramTemplate['status'], r) =>
                    r.changed ? <Pill tone="warn">{t('tg.tplChanged')}</Pill> : <Pill tone={TPL_TONE[v]}>{t(`tg.tpl.${v}`)}</Pill>,
                },
                { title: t('tg.approvedAt'), dataIndex: 'approvedAt', render: (v: string | null) => (v ? <Num>{utc(v)}</Num> : <span className="muted">—</span>) },
                {
                  title: t('common.actions'),
                  key: 'op',
                  render: (_, r) => (
                    <Space size={4}>
                      <PermButton perm="content:view" size="small" onClick={() => void openPreview(r)} data-testid={`tg-preview-${r.lang}`}>
                        {t('tg.previewBtn')}
                      </PermButton>
                      {r.status === 'approved' ? (
                        <PermButton perm="content:publish" size="small" danger onClick={() => askDisable(r)}>
                          {t('tg.disableBtn')}
                        </PermButton>
                      ) : null}
                    </Space>
                  ),
                },
              ]}
            />
          </Card>

          <Card size="small" title={t('tg.groups')} className="gr-card">
            <Typography.Paragraph type="secondary">{t('tg.groupsHint')}</Typography.Paragraph>
            <Table<TelegramGroup>
              size="middle"
              rowKey="chatId"
              pagination={{ pageSize: 10, size: 'small', hideOnSinglePage: true }}
              scroll={{ x: 'max-content' }}
              dataSource={data?.groups}
              loading={loading && !data}
              locale={{ emptyText: t('tg.noGroups') }}
              columns={[
                { title: t('tg.groupTitle'), dataIndex: 'title' },
                { title: t('tg.chatId'), dataIndex: 'chatId', render: (v: string) => <Num>{v}</Num> },
                { title: t('tg.groupStatus'), dataIndex: 'status', render: (v: string) => <Pill tone={v === 'active' ? 'ok' : 'muted'}>{t(`tg.group.${v}`)}</Pill> },
                {
                  title: t('tg.broadcast'),
                  dataIndex: 'broadcast',
                  render: (v: boolean, r) => (v ? <Pill tone="info">{t('tg.broadcastOn', { lang: t(`tg.lang.${r.lang}`) })}</Pill> : <span className="muted">{t('tg.off')}</span>),
                },
                { title: t('tg.startParam'), dataIndex: 'startParam', render: (v: string | null) => (v ? <Num>{v}</Num> : <span className="muted">—</span>) },
                {
                  title: t('common.actions'),
                  key: 'op',
                  render: (_, r) =>
                    r.broadcast ? (
                      <PermButton perm="content:publish" size="small" onClick={() => askGroupOff(r)}>
                        {t('tg.groupOffBtn')}
                      </PermButton>
                    ) : null,
                },
              ]}
            />
          </Card>

          <Card size="small" title={t('tg.posts')} className="gr-card">
            <div className="fc-bar">
              <Segmented
                value={postStatus}
                onChange={(v) => {
                  setPostStatus(v as TelegramPostStatus | 'all');
                  setPage(1);
                }}
                options={[{ value: 'all', label: t('tg.all') }, ...POST_STATUSES.map((s) => ({ value: s, label: t(`tg.post.${s}`) }))]}
              />
            </div>
            <DataTable<TelegramPost>
              bare
              rowKey="id"
              loading={posts.loading}
              error={posts.error}
              onRetry={posts.reload}
              dataSource={posts.data?.items}
              total={posts.data?.total}
              page={page}
              pageSize={size}
              onPageChange={(p, s) => (setPage(p), setSize(s))}
              emptyText={t('tg.noPosts')}
              columns={[
                { title: t('tg.postTime'), dataIndex: 'createdAt', render: (v: string) => <Num>{utc(v)}</Num> },
                { title: t('tg.market'), dataIndex: 'marketNo', render: (v: string) => <Link to={`/markets/${v}`}>{v}</Link> },
                { title: t('tg.channel'), dataIndex: 'target', render: (v: string) => (v.startsWith('G:') ? t('tg.groupTarget', { id: v.slice(2) }) : t(`tg.target.${v}`)) },
                { title: t('tg.postStatus'), dataIndex: 'status', render: (v: TelegramPostStatus) => <Pill tone={POST_TONE[v]}>{t(`tg.post.${v}`)}</Pill> },
                {
                  title: t('tg.postUrl'),
                  dataIndex: 'postUrl',
                  render: (v: string | null) =>
                    v ? (
                      <a href={v} target="_blank" rel="noreferrer">
                        {v.replace('https://', '')}
                      </a>
                    ) : (
                      <span className="muted">—</span>
                    ),
                },
                { title: t('tg.note'), dataIndex: 'note', render: (v: string | null) => (v ? <span className="ellipsis" style={{ maxWidth: 320 }} title={v}>{v}</span> : null) },
              ]}
            />
          </Card>
        </>
      )}

      <Modal
        open={!!preview}
        title={preview ? t('tg.previewTitle', { lang: t(`tg.lang.${preview.tpl.lang}`) }) : ''}
        onCancel={() => setPreview(null)}
        width={720}
        footer={
          preview?.p ? (
            <PermButton perm="content:publish" type="primary" onClick={() => askApprove(preview.tpl, preview.p!)} data-testid="tg-approve">
              {t('tg.approveBtn')}
            </PermButton>
          ) : null
        }
      >
        {preview?.err ? (
          <Alert type="info" showIcon message={errorText(t, preview.err)} />
        ) : preview?.p ? (
          <div className="tg-preview">
            <img src={telegramPreviewUrl(preview.tpl.kind, preview.tpl.lang)} alt={t('tg.previewAlt')} style={{ width: '100%', borderRadius: 8 }} />
            <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
              {t('tg.previewFrom', { no: preview.p.marketNo })}
            </Typography.Paragraph>
            <pre className="tg-preview-text">{preview.p.text}</pre>
          </div>
        ) : (
          <div style={{ height: 120 }} />
        )}
      </Modal>
      {hr.element}
    </>
  );
}
