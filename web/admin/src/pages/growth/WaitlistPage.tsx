/**
 * 等候页订阅（forecast S16，MF-8；需求第 6.2 节"来源统计 … 等候页订阅列表（订阅方式、订阅时间、是否已通知）"；后端 GET /api/admin/waitlist，growth:view）。
 * 上面是各状态的数量和"发送上线通知"（content:publish ★，SQ-22：等候页只发这一封"正式上线了"，不发开赛提醒；<b>全站只能发一次</b>，
 * 发过以后按钮变成发送结果）；下面是订阅列表（邮箱打码，不显示完整邮箱）。主操作在页头（C79）。
 * S19（P15）：页头多一个"从 Cloudflare 导入"（content:publish ★）：开放交易时把 Cloudflare 上确认过的订阅导进来（不发任何邮件，可以重复导入），
 * 之后再按"发送上线通知"。导入结果显示在页面上（新增、改成已确认、改成已退订、不变、不新增、格式不对的逐行原因）。
 */
import { useCallback, useState } from 'react';
import { Alert, Segmented, Space } from 'antd';
import { CloudUploadOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { importWaitlist, launchWaitlist, listWaitlist } from '../../api';
import type { CfImportResult, WaitlistItem, WaitlistStatus } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill, type PillTone } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { utc } from '../../utils/market';
import { CfImportField, type PickedFile } from './CfImportField';

const TONE: Record<WaitlistStatus, PillTone> = { pending: 'warn', confirmed: 'info', notified: 'ok', unsubscribed: 'muted' };
const STATUSES: WaitlistStatus[] = ['confirmed', 'pending', 'notified', 'unsubscribed'];

function Kpi({ k, v, tone = '' }: { k: string; v: string | number; tone?: string }) {
  return (
    <div className={`card kpi ${tone}`}>
      <div className="k">{k}</div>
      <div className="v">
        <b>{v}</b>
      </div>
    </div>
  );
}

export function WaitlistPage() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<WaitlistStatus | 'all'>('all');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const { data, loading, error, reload } = useApi(
    useCallback(() => listWaitlist({ status: status === 'all' ? undefined : status, page, pageSize: size }), [status, page, size]),
  );
  const hr = useHighRisk();
  const c = data?.counts;
  const launch = data?.launch;
  const launched = !!launch && launch.status !== 'none';
  const [imported, setImported] = useState<CfImportResult | null>(null);

  const askImport = () =>
    hr.ask({
      title: t('wl.import.title'),
      description: (
        <>
          <p>{t('wl.import.desc')}</p>
          <p className="muted">{t('wl.import.noMail')}</p>
        </>
      ),
      extra: <CfImportField />,
      confirmText: t('wl.import.confirm'),
      successText: '',
      onConfirm: async (v) => {
        const picked = v.file as PickedFile;
        if (!picked.ok) throw new Error(t(picked.error));
        setImported(await importWaitlist(v.reason, picked.file));
      },
      onDone: reload,
    });

  const askLaunch = () =>
    hr.ask({
      title: t('wl.launchTitle'),
      description: (
        <>
          <p>{t('wl.launchDesc', { n: c?.confirmed ?? 0 })}</p>
          <p className="muted">{t('wl.launchOnce')}</p>
        </>
      ),
      confirmText: t('wl.launchConfirm'),
      successText: t('wl.launched'),
      onConfirm: (v) => launchWaitlist(v.reason),
      onDone: reload,
    });

  return (
    <>
      <PageHeader
        title={t('wl.title')}
        sub={t('wl.sub')}
        extra={
          <Space wrap>
            <PermButton perm="content:publish" icon={<CloudUploadOutlined />} disabled={!data} onClick={askImport} data-testid="wl-import">
              {t('wl.import.btn')}
            </PermButton>
            {launched ? (
              <Pill tone={launch!.status === 'done' ? 'ok' : 'warn'}>{t(`wl.launch.${launch!.status}`, { sent: launch!.sent, n: launch!.recipients })}</Pill>
            ) : (
              <PermButton perm="content:publish" type="primary" danger icon={<SendOutlined />} disabled={!data} onClick={askLaunch} data-testid="wl-launch">
                {t('wl.launchBtn')}
              </PermButton>
            )}
          </Space>
        }
      />
      <Alert type="info" showIcon className="gr-note" message={t('wl.note')} />
      {imported ? (
        <Alert
          type={imported.invalid > 0 || imported.afterLaunch > 0 ? 'warning' : 'success'}
          showIcon
          closable
          onClose={() => setImported(null)}
          className="gr-note"
          data-testid="wl-import-result"
          message={t('wl.import.result', { ...imported })}
          description={
            imported.invalid > 0 || imported.afterLaunch > 0 ? (
              <>
                {imported.afterLaunch > 0 ? <p>{t('wl.import.afterLaunch', { n: imported.afterLaunch })}</p> : null}
                {imported.problems.length > 0 ? (
                  <ul className="wl-import-problems">
                    {imported.problems.slice(0, 50).map((p) => (
                      <li key={`${p.row}-${p.field}`}>{t('wl.import.problem', { row: p.row, what: t(`wl.import.code.${p.code}`, { defaultValue: p.code }) })}</li>
                    ))}
                    {imported.problems.length > 50 ? <li>{t('wl.import.more', { n: imported.problems.length - 50 })}</li> : null}
                  </ul>
                ) : null}
              </>
            ) : undefined
          }
        />
      ) : null}
      {launched ? (
        <Alert
          type="success"
          showIcon
          className="gr-note"
          data-testid="wl-launched"
          message={t('wl.launchInfo', { at: utc(launch!.at ?? ''), n: launch!.recipients, sent: launch!.sent, reason: launch!.reason ?? '' })}
        />
      ) : null}
      <section className="kpis gr-kpis" aria-label={t('wl.counts')}>
        <Kpi k={t('wl.status.confirmed')} v={c ? c.confirmed : '—'} tone="green" />
        <Kpi k={t('wl.status.pending')} v={c ? c.pending : '—'} />
        <Kpi k={t('wl.status.notified')} v={c ? c.notified : '—'} />
        <Kpi k={t('wl.status.unsubscribed')} v={c ? c.unsubscribed : '—'} tone="orange" />
      </section>
      <div className="fc-bar">
        <Segmented
          value={status}
          onChange={(v) => {
            setStatus(v as WaitlistStatus | 'all');
            setPage(1);
          }}
          options={[{ value: 'all', label: t('wl.all') }, ...STATUSES.map((s) => ({ value: s, label: t(`wl.status.${s}`) }))]}
        />
      </div>
      <DataTable<WaitlistItem>
        rowKey="id"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data?.items}
        total={data?.total}
        page={page}
        pageSize={size}
        onPageChange={(p, s) => (setPage(p), setSize(s))}
        emptyText={status === 'all' ? t('wl.empty') : t('wl.emptyFiltered')}
        columns={[
          { title: t('wl.col.createdAt'), dataIndex: 'createdAt', render: (v: string) => <Num>{utc(v)}</Num> },
          { title: t('wl.col.email'), dataIndex: 'email', render: (v: string) => <Num>{v}</Num> },
          { title: t('wl.col.lang'), dataIndex: 'lang', render: (v: string) => t(`wl.lang.${v}`) },
          { title: t('wl.col.status'), dataIndex: 'status', render: (v: WaitlistStatus) => <Pill tone={TONE[v]}>{t(`wl.status.${v}`)}</Pill> },
          {
            title: t('wl.col.source'),
            key: 'src',
            render: (_, r) => (r.src || r.cmp || r.ref ? [r.src, r.cmp, r.ref].filter(Boolean).join(' · ') : <span className="muted">{t('gr.direct')}</span>),
          },
          { title: t('wl.col.confirmedAt'), dataIndex: 'confirmedAt', render: (v: string | null) => (v ? <Num>{utc(v)}</Num> : <span className="muted">—</span>) },
          { title: t('wl.col.notifiedAt'), dataIndex: 'notifiedAt', render: (v: string | null) => (v ? <Num>{utc(v)}</Num> : <span className="muted">—</span>) },
        ]}
      />
      {hr.element}
    </>
  );
}
