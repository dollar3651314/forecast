/**
 * forecast S12b 后台：参数设置"告警邮件"一组（Amos 2026-10-27："告警邮件，我可以在什么地方控制 是否发送"）。
 * 总开关（关掉只写日志、不发邮件）、不发邮件的告警项（列出现在所有的告警项，带中文说明和状态，勾选）；权限点 alert_params:edit（高危）；
 * 正常、没有权限、加载中、出错几种状态；中英文；真实接口的路径和请求体。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import { params } from './mocks/data/db';
import { ParamsPage } from './pages/params/ParamsPage';
import { PERMISSIONS } from './config/permissions';
import * as real from './api/real';
import { fakeFetch } from './test/fakeFetch';

const original = params.filter((p) => p.section === 'alert').map((p) => ({ ...p }));

afterEach(() => {
  setScenario('normal');
  vi.unstubAllGlobals();
  for (const o of original) Object.assign(params.find((p) => p.key === o.key)!, o);
});

async function confirmHighRisk(reason: string) {
  const dialog = await screen.findByRole('dialog');
  fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: reason } });
  // 按文字找（弹窗里勾选框多时按角色和名字找很慢）
  fireEvent.click(within(dialog).getByText(/我已核对操作对象/).closest('label')!.querySelector('input')!);
  fireEvent.click(within(dialog).getByText('确认执行').closest('button')!);
}

/** "告警邮件"这张卡片 */
async function alertCard() {
  const title = await screen.findByText('告警邮件', { selector: '.ant-card-head-title' }, { timeout: 5000 });
  return title.closest('.ant-card') as HTMLElement;
}

/** 告警项的勾选框（按 data-testid 找，比按角色和名字找快） */
function itemBox(dialog: HTMLElement, name: string): HTMLInputElement {
  return within(dialog).getByTestId(`alert-item-${name}`).closest('label')!.querySelector('input')!;
}

async function clickEdit(name: string) {
  const btn = await screen.findByRole('button', { name: `编辑 ${name}` }, { timeout: 5000 });
  await waitFor(() => expect(btn).toBeEnabled());
  fireEvent.click(btn);
}

describe('权限点', () => {
  it('alert_params:edit 在参数组、高危', () => {
    expect(PERMISSIONS.find((p) => p.code === 'alert_params:edit')).toMatchObject({ module: 'params', highRisk: true });
  });
});

describe('参数设置：告警邮件', () => {
  it('正常：默认开、没有静音的项；说明写了收件人和"未开通不发"', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    const card = await alertCard();
    await waitFor(() => expect(within(card).getByText('告警邮件总开关')).toBeInTheDocument());
    expect(within(card).getByText('开（发邮件）')).toBeInTheDocument();
    expect(within(card).getByText('不发邮件的告警项')).toBeInTheDocument();
    expect(within(card).getByText('没有')).toBeInTheDocument();
    expect(within(card).getByText(/FC_ALERT_EMAILS/)).toBeInTheDocument();
    expect(within(card).getByText(/未开通.*不发邮件/)).toBeInTheDocument();
  });

  it('关掉总开关：高危确认（原因必填）后显示"关（只写日志）"', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    await alertCard();
    await clickEdit('告警邮件总开关');
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/健康检查 \/api\/health 不受影响/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByLabelText('关（只写日志）'));
    await confirmHighRisk('测试阶段不收告警邮件');
    const card = await alertCard();
    await waitFor(() => expect(within(card).getByText('关（只写日志）')).toBeInTheDocument());
    expect(params.find((p) => p.key === 'alert.mail.enabled')?.value).toBe('false');
  });

  it('不发邮件的告警项：列出现在所有的告警项（中文说明、状态），勾上的保存后列在下面', async () => {
    renderAs(<ParamsPage />, { path: '/params' });
    await alertCard();
    await clickEdit('不发邮件的告警项');
    const dialog = await screen.findByRole('dialog');
    const tg = await within(dialog).findByTestId('alert-item-telegram.bot');
    expect(tg).toHaveTextContent('Telegram 机器人');
    expect(tg).toHaveTextContent('未开通');
    expect(within(dialog).getByTestId('alert-item-node.TRC20')).toHaveTextContent('出错');
    expect(within(dialog).getByTestId('alert-item-mail')).toHaveTextContent('邮件连续发送失败');
    expect(dialog.querySelectorAll('[data-testid^="alert-item-"]').length).toBe(9);
    expect(within(dialog).getByTestId('alert-item-source.thesportsdb')).toHaveTextContent('数据源 TheSportsDB 未开通');
    fireEvent.click(itemBox(dialog, 'source.thesportsdb'));
    fireEvent.click(itemBox(dialog, 'node.TRC20'));
    await confirmHighRisk('测试环境');
    const card = await alertCard();
    await waitFor(() => expect(within(card).getByText('2 项')).toBeInTheDocument());
    expect(within(card).getByTestId('pm-muted-list')).toHaveTextContent('TRC20 链节点连不上（node.TRC20）、数据源 TheSportsDB 未开通（source.thesportsdb）');
    expect(params.find((p) => p.key === 'alert.mail.muted')?.value).toBe('node.TRC20,source.thesportsdb');
  });

  it('已静音、现在不检查的项也列出来（"现在不检查"），可以取消', async () => {
    params.find((p) => p.key === 'alert.mail.muted')!.value = 'node.GONE';
    renderAs(<ParamsPage />, { path: '/params' });
    const card = await alertCard();
    await waitFor(() => expect(within(card).getByText('1 项')).toBeInTheDocument());
    await clickEdit('不发邮件的告警项');
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByTestId('alert-item-node.GONE')).toHaveTextContent('现在不检查');
    const gone = itemBox(dialog, 'node.GONE');
    expect(gone).toBeChecked();
    fireEvent.click(gone);
    await confirmHighRisk('那条链不用了');
    await waitFor(() => expect(within(card).getByText('没有')).toBeInTheDocument());
  });

  it('没有 alert_params:edit：这张卡片显示"没有权限"，不影响别的组', async () => {
    renderAs(<ParamsPage />, { path: '/params', adminId: 2 });
    const card = await alertCard();
    expect(within(card).getByText('没有权限（alert_params:edit）')).toBeInTheDocument();
  });

  it('出错：显示重试', async () => {
    setScenario('error');
    renderAs(<ParamsPage />, { path: '/params' });
    const card = await alertCard();
    await waitFor(() => expect(within(card).getByRole('button', { name: /重试/ })).toBeInTheDocument(), { timeout: 5000 });
  });

  it('英文界面', async () => {
    renderAs(<ParamsPage />, { path: '/params', lang: 'en' });
    const title = await screen.findByText('Alert emails', { selector: '.ant-card-head-title' }, { timeout: 5000 });
    const card = title.closest('.ant-card') as HTMLElement;
    await waitFor(() => expect(within(card).getByText('Alert emails (master switch)')).toBeInTheDocument());
    expect(within(card).getByText('On (send emails)')).toBeInTheDocument();
    expect(within(card).getByText('Alerts without email')).toBeInTheDocument();
  });
});

describe('真实接口', () => {
  it('路径和请求体', async () => {
    const { calls } = fakeFetch({
      'GET /api/admin/params/alert': { body: [] },
      'GET /api/admin/params/alert/items': { body: [] },
      'PUT /api/admin/params/alert/alert.mail.enabled': { body: {} },
      'PUT /api/admin/params/alert/alert.mail.muted': { body: {} },
    });
    await real.listParams('alert');
    expect(calls[0]).toMatchObject({ method: 'GET', url: '/api/admin/params/alert' });
    await real.listAlertItems();
    expect(calls[1]).toMatchObject({ method: 'GET', url: '/api/admin/params/alert/items' });
    await real.updateParam('alert', 'alert.mail.enabled', 'false', '测试', 'true');
    expect(calls[2]).toMatchObject({ method: 'PUT', url: '/api/admin/params/alert/alert.mail.enabled', body: { value: 'false', reason: '测试', expected: 'true' } });
    await real.updateParam('alert', 'alert.mail.muted', 'telegram.bot', '测试', '');
    expect(calls[3]).toMatchObject({ body: { value: 'telegram.bot', expected: '' } });
  });
});
