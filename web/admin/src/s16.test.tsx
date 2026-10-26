/**
 * forecast S16 后台：等候页订阅（MF-8：列表、各状态数量、发送上线通知只能一次、高危确认）和 Telegram 机器人（MF-7：状态、频道、模板预览和审定、群、
 * 发布日志、紧急停止）；五种状态（正常、空、超过一页、加载中、出错）；真实接口的路径和参数；SQ-24 参数设置里的尾盘大额最低金额。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import { resetFcMocks } from './mocks/fc';
import { params } from './mocks/data/db';
import { WaitlistPage } from './pages/growth/WaitlistPage';
import { TelegramPage } from './pages/telegram/TelegramPage';
import { ParamsPage } from './pages/params/ParamsPage';
import { MENU } from './config/menu';
import { PERMISSIONS } from './config/permissions';
import * as real from './api/realFc';
import { fakeFetch } from './test/fakeFetch';

beforeEach(() => resetFcMocks());
afterEach(() => {
  setScenario('normal');
  vi.unstubAllGlobals();
});

/** 权限加载完以前按钮是禁用的（加载完会换成新的元素）：每次重新取 */
async function clickWhenEnabled(testId: string) {
  await screen.findByTestId(testId, undefined, { timeout: 5000 });
  await waitFor(() => expect(screen.getByTestId(testId)).toBeEnabled(), { timeout: 5000 });
  fireEvent.click(screen.getByTestId(testId));
}

async function confirmHighRisk(reason: string, button = '确认执行') {
  const dialog = await screen.findByRole('dialog');
  fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('checkbox', { name: /我已核对操作对象/ }));
  fireEvent.click(within(dialog).getByRole('button', { name: button }));
}

describe('菜单和权限点（S16）', () => {
  it('来源统计后面是等候页订阅、Telegram 机器人；content:view / content:publish（高危）', () => {
    const keys = MENU.map((m) => m.key);
    expect(keys.slice(keys.indexOf('growth'), keys.indexOf('growth') + 3)).toEqual(['growth', 'waitlist', 'telegram']);
    expect(PERMISSIONS.find((p) => p.code === 'content:publish')).toMatchObject({ module: 'content', highRisk: true });
    expect(PERMISSIONS.find((p) => p.code === 'content:view')).toMatchObject({ module: 'content', highRisk: false });
  });
});

describe('等候页订阅（MF-8）', () => {
  it('正常：各状态数量、邮箱打码、来源；超过一页时分页', async () => {
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    expect(await screen.findByText('等候页订阅', { selector: 'h1, h2, h3, .page-title, *' }, { timeout: 5000 })).toBeInTheDocument();
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByText(/\*\*\*@/).length).toBe(20));
    expect(screen.getByText(/共 26 条/)).toBeInTheDocument();
    expect(within(table).getAllByText('已确认').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('data-reply', { exact: false }).length).toBeGreaterThan(0);
  });

  it('发送上线通知：高危确认（原因必填）→ 发出以后按钮变成结果，不能再发；已确认的变成已通知', async () => {
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    await clickWhenEnabled('wl-launch');
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/全站只能发一次/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认发送' }));
    expect(await within(dialog).findByText('请填写操作原因', undefined, { timeout: 5000 })).toBeInTheDocument();
    await confirmHighRisk('正式上线', '确认发送');
    expect(await screen.findByTestId('wl-launched', undefined, { timeout: 5000 })).toHaveTextContent('原因：正式上线');
    expect(screen.queryByTestId('wl-launch')).toBeNull();
    expect(screen.getByText(/上线通知已发/)).toBeInTheDocument();
  });

  it('空、出错', async () => {
    setScenario('empty');
    const { unmount } = renderAs(<WaitlistPage />, { path: '/waitlist' });
    expect(await screen.findByText('还没有人留邮箱', undefined, { timeout: 5000 })).toBeInTheDocument();
    unmount();
    setScenario('error');
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    expect(await screen.findByRole('button', { name: /重试/ }, { timeout: 5000 })).toBeInTheDocument();
  });

  it('加载中：表格先显示加载，不先显示"暂无数据"', async () => {
    setScenario('slow');
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    expect(screen.queryByText('还没有人留邮箱')).toBeNull();
    expect(document.querySelector('.ant-spin')).not.toBeNull();
  });
});

describe('Telegram 机器人（MF-7）', () => {
  it('正常：机器人状态、频道、模板、群、发布日志', async () => {
    renderAs(<TelegramPage />, { path: '/telegram' });
    expect(await screen.findByText('@quickcome_bot', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText('@quickcome_cn')).toBeInTheDocument();
    expect(screen.getByText('币圈数据交流群')).toBeInTheDocument();
    expect(screen.getAllByText('开奖卡').length).toBe(2);
    expect(await screen.findByText('quickcome_cn/40', { exact: false })).toBeInTheDocument();
    expect(screen.getAllByText('模板是草稿（还没审定），没有发').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('tg-unconfigured')).toBeNull();
  });

  it('改频道：编号不合法时说明哪个字段；打开英文频道', async () => {
    renderAs(<TelegramPage />, { path: '/telegram' });
    await clickWhenEnabled('tg-edit-CHANNEL_EN');
    let dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('频道编号'), { target: { value: 'bad id' } });
    fireEvent.click(within(dialog).getByRole('switch'));
    await confirmHighRisk('建好英文频道');
    expect(await within(dialog).findByText(/频道编号/, { selector: '.ant-alert *' })).toBeInTheDocument();
    dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('频道编号'), { target: { value: '@quickcome_en' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认执行' }));
    expect(await screen.findByText('@quickcome_en', undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('模板：预览（图片和文字）→ 我确认按这个模板自动发布 → 已审定', async () => {
    renderAs(<TelegramPage />, { path: '/telegram' });
    await clickWhenEnabled('tg-preview-en');
    expect(await screen.findByText(/Result: Up/, undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByAltText('开奖卡预览图')).toBeInTheDocument();
    await clickWhenEnabled('tg-approve');
    await waitFor(() => expect(screen.getAllByRole('dialog').length).toBeGreaterThan(1));
    const dialogs = screen.getAllByRole('dialog');
    const hr = dialogs[dialogs.length - 1];
    fireEvent.change(within(hr).getByLabelText('操作原因'), { target: { value: '审定英文开奖卡' } });
    fireEvent.click(within(hr).getByRole('checkbox', { name: /我已核对操作对象/ }));
    fireEvent.click(within(hr).getByRole('button', { name: '我确认按这个模板自动发布' }));
    await waitFor(() => expect(screen.getAllByText('已审定').length).toBe(2), { timeout: 5000 });
  });

  it('紧急停止：高危确认后显示"已紧急停止"，按钮变成恢复', async () => {
    renderAs(<TelegramPage />, { path: '/telegram' });
    await clickWhenEnabled('tg-stop');
    await confirmHighRisk('卡片数字有问题', '紧急停止');
    expect(await screen.findByTestId('tg-stopped', undefined, { timeout: 5000 })).toHaveTextContent('卡片数字有问题');
    expect(screen.getByTestId('tg-resume')).toBeInTheDocument();
  });

  it('空、出错', async () => {
    setScenario('empty');
    const { unmount } = renderAs(<TelegramPage />, { path: '/telegram' });
    expect(await screen.findByText('还没有发布记录', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText('机器人还没有被拉进任何群')).toBeInTheDocument();
    unmount();
    setScenario('error');
    renderAs(<TelegramPage />, { path: '/telegram' });
    expect((await screen.findAllByRole('button', { name: /重试/ }, { timeout: 5000 })).length).toBeGreaterThan(0);
  });
});

describe('参数设置：尾盘大额的单笔最低金额（SQ-24）', () => {
  it('风控一组里有"单笔最低金额"，默认 100', async () => {
    expect(params.find((p) => p.key === 'risk.lateBet.minAmount')?.value).toBe('100');
    renderAs(<ParamsPage />, { path: '/params' });
    expect(await screen.findByText('尾盘大额：单笔最低金额（0 = 不限）', undefined, { timeout: 5000 })).toBeInTheDocument();
  });
});

describe('S16 真实接口', () => {
  it('路径、参数、请求体', async () => {
    const { calls } = fakeFetch({
      'GET /api/admin/waitlist': { body: { items: [], total: 0, page: 1, pageSize: 20, counts: {}, launch: { status: 'none' } } },
      'POST /api/admin/waitlist/launch': { body: { status: 'sending' } },
      'GET /api/admin/telegram': { body: { status: {}, targets: [], groups: [], templates: [] } },
      'GET /api/admin/telegram/posts': { body: { items: [], total: 0, page: 1, pageSize: 10 } },
      'PUT /api/admin/telegram/targets/CHANNEL_ZH': { body: {} },
      'POST /api/admin/telegram/groups/-100123/broadcast-off': { body: {} },
      'GET /api/admin/telegram/templates/RESULT/zh-CN/preview': { body: {} },
      'POST /api/admin/telegram/templates/RESULT/zh-CN/approve': { body: {} },
      'POST /api/admin/telegram/templates/RESULT/en/disable': { body: {} },
      'POST /api/admin/telegram/stop': { body: {} },
    });
    await real.listWaitlist({ status: 'confirmed', page: 2, pageSize: 20 });
    expect(calls[0].url).toBe('/api/admin/waitlist?status=confirmed&page=2&pageSize=20');
    await real.launchWaitlist('上线');
    expect(calls[1]).toMatchObject({ method: 'POST', body: { checked: true, reason: '上线' } });
    await real.getTelegram();
    await real.listTelegramPosts('failed', 1, 10);
    expect(calls[3].url).toBe('/api/admin/telegram/posts?status=failed&page=1&pageSize=10');
    await real.updateTelegramTarget('CHANNEL_ZH', '@qc_cn', true, '建频道');
    expect(calls[4]).toMatchObject({ method: 'PUT', body: { chatId: '@qc_cn', enabled: true, reason: '建频道' } });
    await real.stopTelegramGroup('-100123', '刷屏');
    expect(calls[5]).toMatchObject({ method: 'POST', url: '/api/admin/telegram/groups/-100123/broadcast-off', body: { reason: '刷屏' } });
    await real.getTelegramPreview('RESULT', 'zh-CN');
    await real.approveTelegramTemplate('RESULT', 'zh-CN', 'abc', '审定');
    expect(calls[7]).toMatchObject({ body: { fingerprint: 'abc', checked: true, reason: '审定' } });
    await real.disableTelegramTemplate('RESULT', 'en', '停用');
    await real.setTelegramStop(true, '停');
    expect(calls[9]).toMatchObject({ body: { stop: true, reason: '停' } });
    expect(real.telegramPreviewUrl('RESULT', 'en')).toBe('/api/admin/telegram/templates/RESULT/en/preview.png');
  });
});
