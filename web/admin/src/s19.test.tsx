/**
 * forecast S19（P15）后台：等候页订阅页的"从 Cloudflare 导入"——高危确认（原因、勾选）、选文件后在浏览器里检查格式（不是 JSON、不是 Cloudflare 的格式、
 * 空的，保存前就标出来，C108）、后面还有下一份时提示、导入结果和逐行的问题；真实接口的路径和请求体。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderAs, setScenario } from './test/render';
import { resetFcMocks } from './mocks/fc';
import { WaitlistPage } from './pages/growth/WaitlistPage';
import { parseCfFile } from './pages/growth/CfImportField';
import * as real from './api/realFc';
import { fakeFetch } from './test/fakeFetch';

beforeEach(() => resetFcMocks());
afterEach(() => {
  setScenario('normal');
  vi.unstubAllGlobals();
});

const item = (email: string, status: string) => ({
  email,
  lang: 'zh-TW',
  status,
  src: 'threads',
  cmp: 'tw1',
  ref: null,
  start: null,
  createdAt: '2026-10-28T08:00:00.000Z',
  confirmedAt: status === 'confirmed' ? '2026-10-28T08:05:00.000Z' : null,
  unsubscribedAt: null,
  unsubToken: 'a'.repeat(32),
  ipHash: 'b'.repeat(64),
});

const fileOf = (content: unknown, name = 'waitlist-export.json') =>
  new File([typeof content === 'string' ? content : JSON.stringify(content)], name, { type: 'application/json' });

async function openImport() {
  await screen.findByTestId('wl-import', undefined, { timeout: 5000 });
  await waitFor(() => expect(screen.getByTestId('wl-import')).toBeEnabled(), { timeout: 5000 });
  fireEvent.click(screen.getByTestId('wl-import'));
  return screen.findByRole('dialog');
}

async function pick(dialog: HTMLElement, f: File) {
  fireEvent.change(within(dialog).getByTestId('wl-import-file'), { target: { files: [f] } });
}

describe('读文件（浏览器里先检查）', () => {
  it('不是 JSON、格式不对、空的、正常', () => {
    expect(parseCfFile('a', 'not json')).toMatchObject({ ok: false, error: 'wl.import.notJson' });
    expect(parseCfFile('a', JSON.stringify({ format: 'other', items: [] }))).toMatchObject({ ok: false, error: 'wl.import.wrongFormat' });
    expect(parseCfFile('a', JSON.stringify({ format: 'fc-cf-waitlist-v1', items: [] }))).toMatchObject({ ok: false, error: 'wl.import.empty' });
    expect(parseCfFile('a', JSON.stringify({ format: 'fc-cf-waitlist-v1', items: [item('a@b.co', 'confirmed')] }))).toMatchObject({ ok: true });
  });
});

describe('从 Cloudflare 导入（P15）', () => {
  it('高危确认：没选文件、原因没填一次全部标出；选错文件标在文件下面', async () => {
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    const dialog = await openImport();
    expect(within(dialog).getByText(/导入本身不发任何邮件/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '确认导入' }));
    expect(await within(dialog).findByText('请选择 Cloudflare 下载的 export.json', undefined, { timeout: 5000 })).toBeInTheDocument();
    expect(within(dialog).getByText('请填写操作原因')).toBeInTheDocument();
    await pick(dialog, fileOf({ format: 'something', items: [] }));
    expect(await within(dialog).findByText(/这不是 Cloudflare 名单的导出文件/, undefined, { timeout: 5000 })).toBeInTheDocument();
  });

  it('选对文件：显示数量、后面还有时提示；导入以后页面显示结果和逐行的问题', async () => {
    renderAs(<WaitlistPage />, { path: '/waitlist' });
    const dialog = await openImport();
    await pick(dialog, fileOf({ format: 'fc-cf-waitlist-v1', next: '/api/admin/export.json?after=2000', items: [item('new1@example.org', 'confirmed'), item('bad', 'confirmed'), item('x9@example.org', 'unsubscribed')] }));
    expect(await within(dialog).findByTestId('wl-import-summary', undefined, { timeout: 5000 })).toHaveTextContent('共 3 个：已确认 2，已退订 1');
    expect(within(dialog).getByTestId('wl-import-next')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: '开放交易' } });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /我已核对操作对象/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认导入' }));
    const result = await screen.findByTestId('wl-import-result', undefined, { timeout: 5000 });
    expect(result).toHaveTextContent('已导入 3 个：新增 1');
    expect(result).toHaveTextContent('退订者不新增 1');
    expect(result).toHaveTextContent('第 2 个：邮箱格式不对');
  });

  it('真实接口：POST /api/admin/waitlist/import，带勾选、原因和文件（只提交格式和订阅）', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/waitlist/import': {
        body: { total: 1, added: 1, confirmed: 0, unsubscribed: 0, unchanged: 0, notAdded: 0, invalid: 0, afterLaunch: 0, problems: [], launch: { status: 'none' } },
      },
    });
    const r = await real.importWaitlist('开放交易', { format: 'fc-cf-waitlist-v1', exportedAt: 'x', next: null, items: [item('a@b.co', 'confirmed') as never] });
    expect(r.added).toBe(1);
    expect(calls[0]).toMatchObject({ method: 'POST', url: '/api/admin/waitlist/import' });
    expect(calls[0].body).toEqual({ checked: true, reason: '开放交易', file: { format: 'fc-cf-waitlist-v1', items: [item('a@b.co', 'confirmed')] } });
  });
});
