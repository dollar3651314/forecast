/** S16 数据页兼等候页的真实接口（路径、请求体；不用登录：401 不当成"登录失效"）。 */
import { confirmWaitlist, getBoard, joinWaitlist, unsubscribeWaitlist } from './board';

vi.mock('../mode', () => ({ API_MODE: 'real', IS_MOCK: false }));

const calls: Array<{ url: string; method: string; body?: unknown }> = [];

beforeEach(() => {
  calls.length = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method ?? 'GET', body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined });
      const status = url.includes('confirm') && calls.length > 3 ? 410 : 200;
      const body = status === 410 ? { code: 'WAITLIST_LINK_EXPIRED', message: 'x', fields: [] } : { status: 'ok' };
      return { ok: status < 300, status, text: async () => JSON.stringify(body) } as Response;
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

it('路径和请求体', async () => {
  await getBoard();
  expect(calls[0]).toMatchObject({ url: '/api/public/board', method: 'GET' });
  await joinWaitlist(' a@b.com ', 'zh-TW', '', { first: { src: 'x', cmp: 'c', at: '2026-10-25T00:00:00Z' }, last: { src: 'tg', start: 's-tg', at: '2026-10-26T00:00:00Z' } });
  expect(calls[1]).toMatchObject({
    url: '/api/public/waitlist',
    method: 'POST',
    body: { email: 'a@b.com', lang: 'zh-TW', website: '', attribution: { src: 'tg', start: 's-tg' } },
  });
  await confirmWaitlist('t1');
  expect(calls[2]).toMatchObject({ url: '/api/public/waitlist/confirm', body: { token: 't1' } });
  await unsubscribeWaitlist('t2');
  expect(calls[3]).toMatchObject({ url: '/api/public/waitlist/unsubscribe', body: { token: 't2' } });
  await expect(confirmWaitlist('t3')).rejects.toMatchObject({ reason: 'waitlistLinkExpired', status: 410 });
});
