/**
 * forecast S15 后台"内容中心"（自动化需求第 7 节）：草稿箱（检查通过 / 数字对不上标红、不能发布，改好保存后能审核通过）、
 * 没配 Gemini 的提示、紧急停止、事件候选（经济日历立即生成市场草稿、焦点战建议、已过滤）、页面、工具（只有下载和复制）、日志、
 * 来源统计里分享码换算成分享人；真实接口的路径和参数（高危操作带 reason 和 checked）。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import { listDrafts } from './api';
import { resetContentMocks, setMockGemini } from './mocks/content';
import { resetFcMocks } from './mocks/fc';
import { ContentDraftsPage } from './pages/content/ContentDraftsPage';
import { ContentEventsPage } from './pages/content/ContentEventsPage';
import { ContentPagesPage } from './pages/content/ContentPagesPage';
import { ContentSettingsPage } from './pages/content/ContentSettingsPage';
import { ContentToolsPage } from './pages/content/ContentToolsPage';
import { ContentLogsPage } from './pages/content/ContentLogsPage';
import { GrowthPage } from './pages/growth/GrowthPage';
import * as real from './api/realContent';
import { fakeFetch } from './test/fakeFetch';
import { ALL_PERMISSION_CODES, PERMISSIONS } from './config/permissions';

beforeEach(() => {
  resetContentMocks();
  resetFcMocks();
});
afterEach(() => {
  setScenario('normal');
  vi.unstubAllGlobals();
});

/** 高危确认弹窗：填原因、勾确认、点"确认执行"，等弹窗关掉（用 DOM 查询，role 查询在大页面上很慢） */
async function confirmHighRisk() {
  const open = () => [...document.querySelectorAll<HTMLElement>('.high-risk-modal')].find((m) => m.closest<HTMLElement>('.ant-modal-wrap')?.style.display !== 'none');
  const dlg = await waitFor(() => {
    const m = open();
    if (!m) throw new Error('高危弹窗还没出现');
    return m;
  });
  const reason = dlg.querySelector('textarea') as HTMLTextAreaElement;
  if (!reason.value) fireEvent.change(reason, { target: { value: '测试原因' } });
  fireEvent.click(dlg.querySelector('input[type="checkbox"]') as HTMLInputElement);
  fireEvent.click(dlg.querySelector('.ant-modal-footer .ant-btn-dangerous') as HTMLElement);
  await waitFor(() => expect(open()).toBeUndefined(), { timeout: 4000 });
}

describe('权限点', () => {
  it('content:view / content:edit / content:publish，发布是高危', () => {
    expect(ALL_PERMISSION_CODES).toEqual(expect.arrayContaining(['content:view', 'content:edit', 'content:publish']));
    expect(PERMISSIONS.filter((p) => p.code.startsWith('content:')).map((p) => [p.code, p.highRisk])).toEqual([
      ['content:view', false],
      ['content:edit', false],
      ['content:publish', true],
    ]);
  });
});

describe('草稿箱（AC-104、AC-106、AC-107、AC-110）', () => {
  it('列表默认看待审核；检查没通过的显示几处问题；打开后对不上的数字标红、不能审核通过；改好保存后能通过，通过后才能复制', async () => {
    const page = await listDrafts({ status: 'PENDING', kind: 'POST_X', lang: 'zh', page: 1, pageSize: 20 });
    const red = page.items[0];
    expect(red.checkOk).toBe(false);
    renderAs(<ContentDraftsPage />, { path: '/content/drafts' });
    expect(await screen.findByText('草稿箱')).toBeInTheDocument();
    expect((await screen.findAllByText('1 处问题')).length).toBeGreaterThan(0);
    // 已过期、已发布的不在"待审核"里
    expect(document.querySelector('.ant-table-tbody')?.textContent).not.toContain('已过期');
    fireEvent.click(screen.getByTestId(`draft-${red.draftNo}`));
    const zh = await screen.findByTestId('editor-zh');
    const mark = zh.querySelector('mark.ct-bad-number');
    expect(mark?.textContent).toBe('85');
    expect(within(zh).getByTestId('checks-zh').textContent).toContain('数字对不上引用数据');
    expect(within(zh).getByRole('button', { name: '审核通过' })).toBeDisabled();
    // 帖子没审核通过不能复制
    expect(within(zh).getByRole('button', { name: /复制/ })).toBeDisabled();
    // 改成引用数据里有的数字，保存后重新检查
    fireEvent.change(within(zh).getByLabelText('正文 zh'), { target: { value: '奖池 1,250.00 USDT，押"大 2.5"的占 58.3%。' } });
    expect(within(zh).getByRole('button', { name: '审核通过' })).toBeDisabled();
    fireEvent.click(within(zh).getByTestId('save-zh'));
    await waitFor(() => expect(within(screen.getByTestId('editor-zh')).getByRole('button', { name: '审核通过' })).toBeEnabled(), { timeout: 4000 });
    expect(screen.getByTestId('editor-zh').querySelector('mark.ct-bad-number')).toBeNull();
    fireEvent.click(within(screen.getByTestId('editor-zh')).getByRole('button', { name: '审核通过' }));
    await confirmHighRisk();
    await waitFor(() => expect(within(screen.getByTestId('editor-zh')).getByRole('button', { name: /复制/ })).toBeEnabled(), { timeout: 4000 });
  }, 60_000);

  it('保存时有禁用词：不保存，说明是哪个词', async () => {
    const page = await listDrafts({ status: 'PENDING', kind: 'PREVIEW', lang: 'zh', page: 1, pageSize: 20 });
    renderAs(<ContentDraftsPage />, { path: '/content/drafts' });
    fireEvent.click(await screen.findByTestId(`draft-${page.items[0].draftNo}`));
    const zh = await screen.findByTestId('editor-zh');
    fireEvent.change(within(zh).getByLabelText('正文 zh'), { target: { value: '这场稳赚。' } });
    fireEvent.click(within(zh).getByTestId('save-zh'));
    expect(await screen.findByText(/没有保存：稳赚/)).toBeInTheDocument();
  });

  it('没配 Gemini：顶部提示"未配置"，说明市场草稿和数据照常', async () => {
    setMockGemini(false);
    renderAs(<ContentDraftsPage />, { path: '/content/drafts' });
    const banner = await screen.findByTestId('gemini-banner');
    expect(banner.textContent).toContain('Gemini 未配置');
    expect(banner.textContent).toContain('市场草稿、数据卡、页面数据照常更新');
  });

  it('英文界面也能用', async () => {
    renderAs(<ContentDraftsPage />, { path: '/content/drafts', lang: 'en' });
    await waitFor(() => expect(document.body.textContent).toContain('Generate draft'));
    expect(screen.getAllByText('Pending review').length).toBeGreaterThan(0);
  });
});

describe('用量和设置（AC-115、AC-117）', () => {
  it('只显示 key 已配置 / 未配置；没有自动发布开关；紧急停止后顶部提示，再恢复', async () => {
    renderAs(<ContentSettingsPage />, { path: '/content/settings' });
    const usage = await screen.findByTestId('content-usage');
    expect(within(usage).getByText('已配置')).toBeInTheDocument();
    expect(within(usage).getByText(/37 \/ 300/)).toBeInTheDocument();
    expect(screen.getByText(/没有"自动发布"开关/)).toBeInTheDocument();
    expect(screen.queryByText(/ci_only|AIza/)).toBeNull();
    // 渠道开关是占位（MF-7），不能打开
    screen.getAllByRole('switch').filter((s) => s.closest('.ct-channels')).forEach((s) => expect(s).toBeDisabled());
    fireEvent.click(screen.getByTestId('content-stop'));
    await confirmHighRisk();
    expect(await screen.findByText(/已紧急停止发布/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('content-stop'));
    await confirmHighRisk();
    await waitFor(() => expect(screen.queryByText(/已紧急停止发布：/)).toBeNull(), { timeout: 4000 });
  }, 60_000);
});

describe('事件候选（AC-100、AC-101、AC-103）', () => {
  it('经济日历"立即生成"后显示市场草稿编号；焦点战建议附理由；被过滤的写明原因', async () => {
    renderAs(<ContentEventsPage />, { path: '/content/events' });
    expect(await screen.findByText('FOMC 利率决议')).toBeInTheDocument();
    expect(screen.getByText(/优先联赛第 2 档/)).toBeInTheDocument();
    expect(screen.getAllByText('敏感词：U19').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByTestId('econ-draft-1'));
    expect(await screen.findByText('100000000000000301')).toBeInTheDocument();
  });
});

describe('页面、工具、日志', () => {
  it('页面列表：三种页面、今天新增 X / 上限', async () => {
    renderAs(<ContentPagesPage />, { path: '/content/pages' });
    expect(await screen.findByText('BTC 每小时涨跌统计')).toBeInTheDocument();
    expect(screen.getByText(/今天新增 1 \/ 5/)).toBeInTheDocument();
    expect(screen.getByText('/insights/article-k1')).toBeInTheDocument();
  });

  it('工具：数据卡只有下载和复制，没有发布；Polymarket 默认关闭', async () => {
    renderAs(<ContentToolsPage />, { path: '/content/tools' });
    expect(await screen.findByText(/Polymarket 公开接口默认关闭/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('BTC 每小时涨跌统计'));
    fireEvent.click(screen.getByTestId('tool-card'));
    const result = await screen.findByTestId('tool-result');
    expect(within(result).getByText('下载图片')).toBeInTheDocument();
    expect(within(result).queryByText('发布')).toBeNull();
  });

  it('日志：每次操作一行，可以按动作筛选', async () => {
    renderAs(<ContentLogsPage />, { path: '/content/logs' });
    expect((await screen.findAllByText('拦截')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/NF-16/).length).toBeGreaterThan(0);
  });
});

describe('来源统计：分享码换算成分享人（S15）', () => {
  it('ref 是分享码时显示"分享人 客户编号"', async () => {
    renderAs(<GrowthPage />, { path: '/growth' });
    expect((await screen.findAllByText('分享人 100023')).length).toBeGreaterThan(0);
  });
});

describe('真实接口的路径和参数', () => {
  it('发布、设置、紧急停止、词表带 reason 和 checked；生成草稿、经济日历、工具、日志', async () => {
    const draft = { draftNo: '1', status: 'PUBLISHED' };
    const { calls } = fakeFetch({
      'POST /api/admin/content/drafts/1/publish': { body: draft },
      'PUT /api/admin/content/settings': { body: {} },
      'POST /api/admin/content/stop': { body: {} },
      'POST /api/admin/content/words': { body: {} },
      'POST /api/admin/content/drafts': { body: [draft] },
      'POST /api/admin/content/econ': { body: {} },
      'POST /api/admin/content/tools/compare': { body: {} },
      'GET /api/admin/content/logs': { body: { items: [], total: 0 } },
      'PUT /api/admin/content/drafts/1': { body: draft },
      'POST /api/admin/content/drafts/1/copied': { body: draft },
    });
    await real.publishDraft('1', '核对过');
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/admin/content/drafts/1/publish', body: { reason: '核对过', checked: true } });
    await real.updateContentSettings({ 'ai.dailyLimit': '200' }, '降一点');
    expect(calls[1]).toMatchObject({ method: 'PUT', body: { values: { 'ai.dailyLimit': '200' }, reason: '降一点', checked: true } });
    await real.setContentStopped(true, '有问题');
    expect(calls[2].body).toEqual({ stopped: true, reason: '有问题', checked: true });
    await real.addContentWord('BANNED', '包赢', '加词');
    expect(calls[3].body).toEqual({ kind: 'BANNED', word: '包赢', reason: '加词', checked: true });
    await real.generateContentDrafts({ kind: 'ARTICLE', topic: 'BTC' });
    expect(calls[4].body).toEqual({ kind: 'ARTICLE', topic: 'BTC' });
    await real.addEcon({ kind: 'CPI', period: '2026-11', releaseAt: '2026-12-10T13:30:00Z', prevValue: '3.0' });
    expect(calls[5].body).toMatchObject({ kind: 'CPI', period: '2026-11', prevValue: '3.0' });
    await real.makeCompare('101', '某大V说会大', undefined, 'zh-CN');
    expect(calls[6].body).toEqual({ marketNo: '101', viewpoint: '某大V说会大', lang: 'zh-CN' });
    await real.listContentLogs({ action: 'PUBLISH', page: 2, pageSize: 20 });
    expect(calls[7].url).toBe('/api/admin/content/logs?action=PUBLISH&page=2&pageSize=20');
    await real.editDraft('1', '标题', '正文', 3);
    expect(calls[8].body).toEqual({ title: '标题', body: '正文', version: 3 });
    await real.copiedDraft('1');
    expect(calls[9]).toMatchObject({ method: 'POST', url: '/api/admin/content/drafts/1/copied' });
  });

  it('业务错误按 errors.fc 显示，带后端说明', async () => {
    fakeFetch({ 'POST /api/admin/content/drafts/9/publish': { status: 409, body: { code: 'DRAFT_CHECK_FAILED', message: '有 1 个数字找不到：85' } } });
    await expect(real.publishDraft('9', 'x')).rejects.toMatchObject({ status: 409, code: 'DRAFT_CHECK_FAILED', params: { detail: '有 1 个数字找不到：85' } });
  });
});
