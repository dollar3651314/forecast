/**
 * 体育赛程（需求第 4.2、6.2 节"从赛程生成"；后端 SportsAdminApi，S8）：
 * 赛程列表（主源未来 7 天）：开赛时间（UTC，开赛时间变过的标出）、赛事、主客队、状态、两个来源的对应是否已确认、已生成的市场；
 * 立即同步（market:edit；来源未开通的显示"未开通"）；
 * 处理一场（抽屉）：系统找的候选（分数、推荐、主客对调）→ 运营确认对应（只能确认候选里的，开赛后不能改，X-12）、取消对应、按编号手工找；
 * 一键生成草稿（玩法：足球胜平负、大小球（默认 2.5）、NBA 胜负、大小分（分界线必填、.5 结尾）），之后照常提交上架、由有 market:publish 的人上架。
 * 下面是四个玩法的规则文本模板（market:autogen，高危）。
 */
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, App, Button, Card, Checkbox, Drawer, Empty, Input, Select, Space, Table, Tag } from 'antd';
import { SyncOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { clearMapping, confirmMapping, generateDrafts, getFixture, listFixtures, listSportsTemplates, searchCandidates, syncFixtures, updateSportsTemplate } from '../../api';
import { PLAYS, type Fixture, type Generated, type Play, type Sport, type SportsTemplate, type SyncResult } from '../../api/fcTypes';
import { DataTable } from '../../components/DataTable';
import { ErrorState } from '../../components/ErrorState';
import { I18nInputs, I18nView } from '../../components/I18nInputs';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { usePagedList } from '../../hooks/usePagedList';
import { errorText } from '../../utils/errors';
import { utc } from '../../utils/market';

function FixtureDrawer({ no, onClose, onChanged }: { no: string | null; onClose: () => void; onChanged: () => void }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const fetcher = useCallback(() => (no ? getFixture(no) : Promise.resolve(null)), [no]);
  const { data: f, error, reload } = useApi<Fixture | null>(fetcher);
  const [ref, setRef] = useState('');
  const [plays, setPlays] = useState<Record<string, { on: boolean; line: string }>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [gen, setGen] = useState<Generated | null>(null);

  const act = async (key: string, fn: () => Promise<unknown>, ok: string) => {
    setBusy(key);
    try {
      const r = await fn();
      void message.success(ok);
      if (key === 'gen') setGen(r as Generated);
      reload();
      onChanged();
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setBusy(null);
    }
  };
  const avail = f ? PLAYS.filter((p) => p.startsWith(f.sport === 'NBA' ? 'NBA' : 'FOOTBALL')) : [];
  const started = f ? Date.parse(f.kickoffAt) <= Date.now() : false;

  return (
    <Drawer open={!!no} onClose={onClose} width={Math.min(760, typeof window !== 'undefined' ? window.innerWidth : 760)} title={f ? `${f.home.name} vs ${f.away.name}` : t('sp.fixture')} destroyOnClose>
      {error ? (
        <ErrorState error={error} onRetry={reload} compact />
      ) : !f ? (
        <Card loading />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <div className="fc-dl-inline">
            <span>
              {t('sp.kickoff')} <Num strong>{utc(f.kickoffAt, true)}</Num>
            </span>
            {f.kickoffChanged ? <Pill tone="warn">{t('sp.kickoffChanged', { from: utc(f.originalKickoffAt) })}</Pill> : null}
            <span>
              {f.competitionName} · {t(`sp.status.${f.status}`, { defaultValue: f.status })}
            </span>
            <span className="muted">
              {f.primarySource} #{f.primaryRef}
            </span>
          </div>
          <Card size="small" title={t('sp.mapping')} extra={f.mapping ? <Pill tone="ok">{t('sp.mapped')}</Pill> : <Pill tone="warn">{t('sp.unmapped')}</Pill>}>
            {f.mapping ? (
              <div className="fc-dl-inline">
                <span>
                  {f.mapping.source} #{f.mapping.ref}：{f.mapping.home} vs {f.mapping.away}
                </span>
                {f.mapping.swapped ? <Tag color="orange">{t('sp.swapped')}</Tag> : null}
                <span className="muted">{f.mapping.confirmedAt ? t('sp.confirmedBy', { by: f.mapping.confirmedBy, at: utc(f.mapping.confirmedAt) }) : null}</span>
                <PermButton perm="market:edit" size="small" disabled={started} disabledReason={t('sp.started')} loading={busy === 'clear'} onClick={() => void act('clear', () => clearMapping(f.fixtureNo), t('sp.cleared'))}>
                  {t('sp.clear')}
                </PermButton>
              </div>
            ) : (
              <Alert type="info" showIcon message={t('sp.mapHint')} />
            )}
            {f.candidatesError ? <Alert style={{ marginTop: 8 }} type="warning" showIcon message={f.candidatesError} /> : null}
            <Table
              style={{ marginTop: 8 }}
              size="small"
              rowKey="ref"
              pagination={false}
              scroll={{ x: 'max-content' }}
              dataSource={f.candidates}
              locale={{ emptyText: t('sp.noCandidates') }}
              columns={[
                { title: t('sp.candidate'), key: 'c', render: (_, c) => `${c.home} vs ${c.away}` },
                { title: t('sp.kickoff'), dataIndex: 'kickoffAt', render: (v: string) => <Num>{utc(v)}</Num> },
                { title: t('sp.score'), dataIndex: 'score', render: (v: number, c) => (<Space size={4}><Num>{v.toFixed(2)}</Num>{c.suggested ? <Tag color="green">{t('sp.suggested')}</Tag> : null}{c.swapped ? <Tag color="orange">{t('sp.swapped')}</Tag> : null}</Space>) },
                {
                  title: '',
                  key: 'op',
                  render: (_, c) =>
                    f.mapping?.ref === c.ref ? (
                      <span className="muted">{t('sp.current')}</span>
                    ) : (
                      <PermButton perm="market:edit" size="small" type={c.suggested ? 'primary' : 'default'} disabled={started} disabledReason={t('sp.started')} loading={busy === `map${c.ref}`} onClick={() => void act(`map${c.ref}`, () => confirmMapping(f.fixtureNo, c.ref), t('sp.mappedOk'))}>
                        {t('sp.confirm')}
                      </PermButton>
                    ),
                },
              ]}
            />
            <Space.Compact style={{ marginTop: 8, maxWidth: 420, width: '100%' }}>
              <Input value={ref} onChange={(e) => setRef(e.target.value)} placeholder={t('sp.refPh')} aria-label={t('sp.refPh')} />
              <PermButton perm="market:edit" loading={busy === 'search'} onClick={() => void act('search', () => searchCandidates(f.fixtureNo, ref.trim() || undefined), t('sp.searched'))}>
                {ref.trim() ? t('sp.searchRef') : t('sp.searchAgain')}
              </PermButton>
            </Space.Compact>
          </Card>
          <Card size="small" title={t('sp.generate')}>
            {!f.mapping ? <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={t('sp.genNoMapping')} /> : null}
            <Space direction="vertical">
              {avail.map((p) => (
                <Space key={p} wrap>
                  <Checkbox checked={!!plays[p]?.on} onChange={(e) => setPlays({ ...plays, [p]: { on: e.target.checked, line: plays[p]?.line ?? (p === 'FOOTBALL_OU' ? '2.5' : '') } })}>
                    {t(`sp.play.${p}`)}
                  </Checkbox>
                  {p.endsWith('_OU') || p.endsWith('_TOTAL') ? (
                    <Input size="small" style={{ width: 120 }} addonBefore={t('sp.line')} value={plays[p]?.line ?? (p === 'FOOTBALL_OU' ? '2.5' : '')} placeholder={p === 'NBA_TOTAL' ? '224.5' : '2.5'} onChange={(e) => setPlays({ ...plays, [p]: { on: plays[p]?.on ?? true, line: e.target.value } })} />
                  ) : null}
                </Space>
              ))}
            </Space>
            <div style={{ marginTop: 8 }}>
              <PermButton
                perm="market:edit"
                type="primary"
                loading={busy === 'gen'}
                disabled={!Object.values(plays).some((x) => x.on)}
                disabledReason={t('sp.pickPlay')}
                onClick={() => void act('gen', () => generateDrafts(f.fixtureNo, Object.entries(plays).filter(([, x]) => x.on).map(([p, x]) => ({ play: p as Play, line: x.line.trim() || null }))), t('sp.generated'))}
              >
                {t('sp.genBtn')}
              </PermButton>
            </div>
            {gen ? (
              <Alert
                style={{ marginTop: 8 }}
                type={gen.warnings.length ? 'warning' : 'success'}
                showIcon
                message={t('sp.genDone', { n: gen.created.length })}
                description={
                  <>
                    {gen.created.map((c) => (
                      <div key={c.marketNo}>
                        <Link to={`/markets/${c.marketNo}/edit`}>{c.marketNo}</Link> · {t(`sp.play.${c.play}`)} {c.line ?? ''}
                      </div>
                    ))}
                    {gen.warnings.map((w) => (
                      <div key={w}>{t(`sp.warn.${w}`, { defaultValue: w })}</div>
                    ))}
                  </>
                }
              />
            ) : null}
            {f.markets.length ? (
              <div style={{ marginTop: 8 }}>
                <b>{t('sp.markets')}</b>
                {f.markets.map((x) => (
                  <div key={x.marketNo} className="mkd-log">
                    <Link to={`/markets/${x.marketNo}`}>{x.marketNo}</Link> · {t(`sp.play.${x.play}`)} {x.line ?? ''} · {t(`mk.status.${x.status}`)}
                  </div>
                ))}
              </div>
            ) : null}
          </Card>
        </Space>
      )}
    </Drawer>
  );
}

function SportsTemplates() {
  const { t } = useTranslation();
  const { data, error, reload } = useApi(useCallback(() => listSportsTemplates(), []));
  const hr = useHighRisk();
  const [v, setV] = useState<SportsTemplate | null>(null);
  if (error) return <ErrorState error={error} onRetry={reload} compact />;
  return (
    <Card size="small" loading={!data}>
      {(data ?? []).map((x) => (
        <div key={x.kind} className="ag-tpl">
          <div>
            <b>{t(`sp.play.${x.kind}`)}</b> <Tag>{t('ag.version', { v: x.version })}</Tag>
            <I18nView value={x.title} />
          </div>
          <PermButton perm="market:autogen" size="small" onClick={() => setV(x)}>
            {t('common.edit')}
          </PermButton>
        </div>
      ))}
      {data && !data.length ? <Empty /> : null}
      <Drawer
        open={!!v}
        onClose={() => setV(null)}
        width={Math.min(720, typeof window !== 'undefined' ? window.innerWidth : 720)}
        title={v ? t('ag.tplEdit', { name: t(`sp.play.${v.kind}`) }) : ''}
        footer={
          <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
            <Button onClick={() => setV(null)}>{t('common.cancel')}</Button>
            <PermButton
              perm="market:autogen"
              type="primary"
              onClick={() =>
                v &&
                hr.ask({
                  title: t('ag.tplSave', { name: t(`sp.play.${v.kind}`) }),
                  description: t('sp.tplNote'),
                  onConfirm: (x) => updateSportsTemplate(v.kind, { name: v.name, title: v.title, rules: v.rules, sourcePrimary: v.sourcePrimary, sourceBackup: v.sourceBackup, options: v.options }, x.reason),
                  onDone: () => {
                    setV(null);
                    reload();
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
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert type="info" message={t('ag.vars')} description={<Num>{v.variables.map((x) => `{${x}}`).join('  ')}</Num>} />
            <b>{t('mke.titleField')}</b>
            <I18nInputs value={v.title} onChange={(x) => setV({ ...v, title: x })} placeholder={t('mke.titleField')} />
            <b>{t('mke.sec.rules')}</b>
            <I18nInputs multiline rows={4} value={v.rules} onChange={(x) => setV({ ...v, rules: x })} placeholder={t('mke.rulesPh')} />
            {v.options.map((o, i) => (
              <div key={o.outcome}>
                <b>
                  {t('mkd.option')} {o.outcome}
                </b>
                <I18nInputs value={o.label} onChange={(x) => setV({ ...v, options: v.options.map((p, j) => (j === i ? { ...p, label: x } : p)) })} placeholder={t('mke.optLabel')} />
                <I18nInputs value={o.winCondition} onChange={(x) => setV({ ...v, options: v.options.map((p, j) => (j === i ? { ...p, winCondition: x } : p)) })} placeholder={t('mke.optWin')} />
              </div>
            ))}
          </Space>
        ) : null}
      </Drawer>
      {hr.element}
    </Card>
  );
}

export function SportsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const list = usePagedList(listFixtures, {} as { sport?: Sport; mapping?: 'confirmed' | 'unconfirmed' }, 20);
  const [open, setOpen] = useState<string | null>(null);
  const [sync, setSync] = useState<SyncResult[] | null>(null);
  const [syncing, setSyncing] = useState(false);
  const doSync = async () => {
    setSyncing(true);
    try {
      setSync(await syncFixtures());
      list.reload();
    } catch (e) {
      void message.error(errorText(t, e));
    } finally {
      setSyncing(false);
    }
  };
  return (
    <>
      <PageHeader
        title={t('sp.title')}
        sub={t('sp.sub')}
        extra={
          <PermButton perm="market:edit" type="primary" icon={<SyncOutlined />} loading={syncing} onClick={() => void doSync()}>
            {t('sp.sync')}
          </PermButton>
        }
      />
      {sync ? (
        <Alert
          type={sync.some((s) => s.outcome !== 'ok') ? 'warning' : 'success'}
          showIcon
          closable
          onClose={() => setSync(null)}
          message={t('sp.syncDone')}
          description={sync.map((s) => (
            <div key={s.sport}>
              {t(`mk.cats.${s.sport}`)}（{s.source}）：{t(`sp.outcome.${s.outcome}`)}
              {s.outcome === 'ok' ? ` · ${t('sp.syncNums', { fetched: s.fetched, created: s.created, updated: s.updated, changed: s.kickoffChanged })}` : s.error ? ` · ${s.error}` : ''}
            </div>
          ))}
        />
      ) : null}
      <div className="fc-bar">
        <Space wrap>
          <Select allowClear style={{ width: 130 }} placeholder={t('sp.sport')} value={list.filters.sport} onChange={(v) => list.setFilters({ ...list.filters, sport: v })} options={['FOOTBALL', 'NBA'].map((s) => ({ value: s, label: t(`mk.cats.${s}`) }))} />
          <Select allowClear style={{ width: 160 }} placeholder={t('sp.mapping')} value={list.filters.mapping} onChange={(v) => list.setFilters({ ...list.filters, mapping: v })} options={[{ value: 'unconfirmed', label: t('sp.unmapped') }, { value: 'confirmed', label: t('sp.mapped') }]} />
        </Space>
        <span className="muted">{t('sp.range')}</span>
      </div>
      <DataTable<Fixture>
        rowKey="fixtureNo"
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        dataSource={list.data?.items}
        total={list.data?.total}
        page={list.page}
        pageSize={list.pageSize}
        onPageChange={list.onPageChange}
        emptyText={list.filters.sport || list.filters.mapping ? t('sp.emptyFiltered') : t('sp.empty')}
        columns={[
          { title: t('sp.kickoff'), dataIndex: 'kickoffAt', render: (v: string, f) => (<Space size={4}><Num>{utc(v)}</Num>{f.kickoffChanged ? <Pill tone="warn">{t('sp.changed')}</Pill> : null}</Space>) },
          { title: t('sp.competition'), key: 'c', render: (_, f) => `${t(`mk.cats.${f.sport}`)} · ${f.competitionName}` },
          { title: t('sp.match'), key: 'm', render: (_, f) => <b>{`${f.home.name} vs ${f.away.name}`}</b> },
          { title: t('sp.statusCol'), dataIndex: 'status', render: (v: string) => t(`sp.status.${v}`, { defaultValue: v }) },
          { title: t('sp.mapping'), key: 'map', render: (_, f) => (f.mapping ? <Pill tone="ok">{t('sp.mapped')}</Pill> : f.candidates.some((c) => c.suggested) ? <Pill tone="pend">{t('sp.hasSuggestion')}</Pill> : <Pill tone="warn">{t('sp.unmapped')}</Pill>) },
          { title: t('sp.markets'), key: 'mk', align: 'right', render: (_, f) => <Num>{f.markets.length}</Num> },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, f) => (
              <PermButton perm="market:view" size="small" type="primary" ghost onClick={() => setOpen(f.fixtureNo)}>
                {t('sp.handle')}
              </PermButton>
            ),
          },
        ]}
      />
      <h2 className="dash-h">{t('sp.templates')}</h2>
      <SportsTemplates />
      <FixtureDrawer no={open} onClose={() => setOpen(null)} onChanged={list.reload} />
    </>
  );
}
