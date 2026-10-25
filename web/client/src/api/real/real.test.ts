/**
 * 真实接口层（real 模式）的单元测试：用 fetch 和 WebSocket 替身，检查请求的地址、方法、请求体、
 * Cookie 设置，以及响应和错误的转换、401 回到登录页、实时推送和断线重连。
 */
import { ApiError } from '../errors';
import { onUnauthorized } from '../http';
import * as auth from './auth';
import * as account from './account';
import { handleUserMessage, subscribeEvents } from './events';
import * as notifications from './notifications';
import { setLoggedIn } from './session';
import * as user from './user';
import * as wallet from './wallet';
import type { ServerEvent } from '../types';
import { base64UrlToBytes, syncPushSubscription, unsubscribeThisDevice } from '../webPush';

// 这个文件测真实接口：Web Push 的同步逻辑按 real 模式运行
vi.mock('../mode', () => ({ API_MODE: 'real', IS_MOCK: false }));

interface Call {
  url: string;
  method: string;
  body?: unknown;
  credentials?: string;
}

let calls: Call[] = [];

/** fetch 替身：按调用顺序返回 responses 里的结果 */
function stubFetch(...responses: Array<{ status?: number; body?: unknown } | Error>) {
  calls = [];
  const queue = [...responses];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({
        url,
        method: init.method ?? 'GET',
        credentials: init.credentials,
        body: typeof init.body === 'string' ? JSON.parse(init.body) : init.body,
      });
      const r = queue.shift() ?? { status: 200 };
      if (r instanceof Error) throw r;
      const status = r.status ?? 200;
      const text = r.body === undefined ? '' : typeof r.body === 'string' ? r.body : JSON.stringify(r.body);
      return { ok: status >= 200 && status < 300, status, text: async () => text } as Response;
    }),
  );
}

class FakeWS {
  static OPEN = 1;
  static instances: FakeWS[] = [];
  readyState = 0;
  sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {
    FakeWS.instances.push(this);
  }
  send(m: string) {
    this.sent.push(JSON.parse(m));
  }
  close() {
    this.readyState = 3;
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  drop() {
    this.readyState = 3;
    this.onclose?.();
  }
  push(msg: unknown) {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
}

beforeEach(() => {
  FakeWS.instances = [];
  vi.stubGlobal('WebSocket', FakeWS);
  stubFetch();
});

afterEach(() => {
  setLoggedIn(false);
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('HTTP：同源、Cookie、错误转换', () => {
  it('登录：POST JSON，同源带 Cookie，返回会话（不含令牌）', async () => {
    stubFetch({ body: { userId: '7', email: 'a@b.com' } });
    const s = await auth.login('a@b.com', 'password1');
    expect(s).toEqual({ userId: '7', email: 'a@b.com' });
    expect(calls[0]).toMatchObject({ url: '/api/auth/login', method: 'POST', credentials: 'same-origin', body: { email: 'a@b.com', password: 'password1' } });
  });

  it('验证码用途换成后端的枚举；返回 204 时按 60 秒倒计时', async () => {
    stubFetch({ status: 204 });
    expect(await auth.sendEmailCode('a@b.com', 'resetPassword')).toEqual({ cooldownSeconds: 60 });
    expect(calls[0].body).toEqual({ email: 'a@b.com', purpose: 'RESET_PASSWORD' });
  });

  it('验证码：按后端返回的冷却秒数倒计时；返回值不合法时按 60 秒', async () => {
    stubFetch({ status: 200, body: { cooldownSeconds: 90 } }, { status: 200, body: { cooldownSeconds: 'x' } }, { status: 200, body: {} });
    expect(await auth.sendEmailCode('a@b.com', 'register')).toEqual({ cooldownSeconds: 90 });
    expect(await auth.sendEmailCode('a@b.com', 'register')).toEqual({ cooldownSeconds: 60 });
    expect(await auth.sendEmailCode('a@b.com', 'register')).toEqual({ cooldownSeconds: 60 });
  });

  it('验证码发送太频繁（429）：带上还要等的秒数', async () => {
    stubFetch({ status: 429, body: { code: 'CODE_TOO_FREQUENT', message: '发送太频繁', fields: [], cooldownSeconds: 42 } }, { status: 429, body: { code: 'CODE_TOO_FREQUENT', fields: [] } });
    const e = await auth.sendEmailCode('a@b.com', 'register').catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).reason).toBe('codeTooFrequent');
    expect((e as ApiError).cooldownSeconds).toBe(42);
    const old = await auth.sendEmailCode('a@b.com', 'register').catch((x: unknown) => x);
    expect((old as ApiError).cooldownSeconds).toBeUndefined();
  });

  it('注册：验证码错误标在验证码输入框上', async () => {
    stubFetch({ status: 400, body: { code: 'CODE_INVALID', message: '验证码错误', fields: [] } });
    const e = await auth.register({ email: 'a@b.com', code: '000000', password: 'password1', firstName: 'A', middleName: '', lastName: 'B', birthDate: '1990-01-01', nationality: 'CN' }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).reason).toBe('codeInvalid');
    expect((e as ApiError).fieldErrors).toEqual({ code: ['errors.codeInvalid'] });
  });

  it('改密码：字段名对应到页面表单', async () => {
    stubFetch({ status: 400, body: { code: 'VALIDATION_FAILED', fields: [{ field: 'newPassword', code: 'PASSWORD_LENGTH' }] } });
    const e = (await auth.changePassword('old', 'short').catch((x: unknown) => x)) as ApiError;
    expect(calls[0].body).toEqual({ currentPassword: 'old', newPassword: 'short' });
    expect(e.fieldErrors).toEqual({ password: ['validation.passwordLength'] });
  });

  it('401：通知回到登录页；启动时检查登录不触发', async () => {
    const h = vi.fn();
    const off = onUnauthorized(h);
    stubFetch({ status: 401, body: { code: 'UNAUTHORIZED', fields: [] } }, { status: 401, body: { code: 'UNAUTHORIZED', fields: [] } });
    expect(await auth.restoreSession()).toBeNull();
    expect(h).not.toHaveBeenCalled();
    await expect(account.getAccount()).rejects.toMatchObject({ reason: 'unauthorized' });
    expect(h).toHaveBeenCalledTimes(1);
    off();
  });

  it('断网、网关错误页', async () => {
    stubFetch(new TypeError('Failed to fetch'), { status: 502, body: '<html>Bad Gateway</html>' });
    await expect(account.getAccount()).rejects.toMatchObject({ reason: 'network' });
    await expect(account.getAccount()).rejects.toMatchObject({ reason: 'server' });
  });

  it('响应里的 null 当成没有；资料和 KYC 的时间补上 UTC 时区', async () => {
    stubFetch(
      { body: [{ id: '1', label: 'a', network: 'TRC20', address: 'T1', createdAt: '2026-10-01T00:00:00Z', activeAt: null }] },
      { body: { status: 'rejected', docType: 'idCard', docNumber: '****1234', submittedAt: '2026-10-15T08:00:00', reviewedAt: null, rejectReason: '照片模糊' } },
      { body: { userId: '7', email: 'a@b.com', firstName: 'A', middleName: null, lastName: 'B', birthDate: '1990-01-01', nationality: 'CN', registeredAt: '2026-10-01T00:00:00', locked: false } },
    );
    const [w] = await wallet.listWhitelist();
    expect('activeAt' in w).toBe(false);
    expect(w.label).toBe('a');
    const k = await user.getKyc();
    expect(k.submittedAt).toBe('2026-10-15T08:00:00Z');
    expect(k.reviewedAt).toBeUndefined();
    const pr = await user.getProfile();
    expect(pr.registeredAt).toBe('2026-10-01T00:00:00Z');
    expect(pr.middleName).toBe('');
  });
});

describe('账户、钱包、通知接口', () => {
  it('账户、资金流水：GET /api/ledger/*；流水的类型"全部"不带 type 参数；余额不足显示"可用余额不足"', async () => {
    stubFetch(
      { body: { currency: 'USDT', balance: '100', available: '100', betLocked: '0', withdrawFrozen: '0' } },
      { body: { items: [], total: 0, page: 2, pageSize: 20 } },
      { body: { items: [], total: 0, page: 1, pageSize: 20 } },
      { status: 400, body: { code: 'INSUFFICIENT_FUNDS', fields: [] } },
    );
    expect((await account.getAccount()).available).toBe('100');
    await wallet.listLedger({ type: 'all', range: '30d', page: 2, pageSize: 20 });
    await wallet.listLedger({ type: 'deposit', range: 'all', page: 1, pageSize: 20 });
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/ledger/account',
      'GET /api/ledger/entries?range=30d&page=2&pageSize=20',
      'GET /api/ledger/entries?type=deposit&range=all&page=1&pageSize=20',
    ]);
    await expect(account.getAccount()).rejects.toMatchObject({ reason: 'insufficientFunds' });
  });

  it('充值地址带最小充值金额；充值记录；提现超过可提金额', async () => {
    stubFetch(
      { body: [{ network: 'TRC20', address: 'T1', confirmations: 19, etaMinutes: 1, minDeposit: '1' }] },
      { body: [{ id: '1', network: 'BSC', txHash: '0x1', amount: '0.5', status: 'belowMin', confirmations: 15, requiredConfirmations: 15, time: '2026-10-15T00:00:00Z' }] },
      { status: 400, body: { code: 'INSUFFICIENT_BALANCE', fields: [] } },
    );
    expect((await wallet.getDepositAddresses())[0].minDeposit).toBe('1');
    expect((await wallet.listDeposits())[0].status).toBe('belowMin');
    await expect(wallet.submitWithdraw({ whitelistId: '3', amount: ' 100 ' })).rejects.toMatchObject({ reason: 'withdrawInsufficient' });
    expect(calls[2]).toMatchObject({ url: '/api/wallet/withdrawals', body: { whitelistId: '3', amount: '100' } });
  });

  it('提现：后端的字段错误（VALIDATION_FAILED 的 fields）对应到表单字段', async () => {
    stubFetch({
      status: 400,
      body: { code: 'VALIDATION_FAILED', fields: [{ field: 'amount', code: 'amountDigits' }, { field: 'whitelistId', code: 'selectAddress' }] },
    });
    const e = (await wallet.submitWithdraw({ whitelistId: 'x', amount: '1.234' }).catch((x: unknown) => x)) as ApiError;
    expect(e.reason).toBe('validationFailed');
    expect(e.fieldErrors).toEqual({ amount: ['validation.amountDigits'], whitelistId: ['validation.selectAddress'] });
  });

  it('站内信按界面语言取；未读数；标记已读', async () => {
    stubFetch({ body: { items: [], total: 0, page: 1, pageSize: 20 } }, { body: { count: 3 } }, { status: 200 });
    await notifications.listNotifications('en', 1, 20);
    expect(await notifications.getUnreadCount()).toBe(3);
    await notifications.markRead('42');
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual(['GET /api/notifications?lang=en&page=1&pageSize=20', 'GET /api/notifications/unread-count', 'POST /api/notifications/42/read']);
  });

  it('切换界面语言：登录后才 PUT /api/me/notify-lang，登录时补发', async () => {
    stubFetch({ status: 200 }, { status: 200 });
    notifications.setPushLanguage('en');
    expect(calls).toHaveLength(0);
    setLoggedIn(true);
    await Promise.resolve();
    expect(calls[0]).toMatchObject({ url: '/api/me/notify-lang', method: 'PUT', body: { lang: 'en' } });
    notifications.setPushLanguage('zh-CN');
    expect(calls[1]).toMatchObject({ url: '/api/me/notify-lang', body: { lang: 'zh-CN' } });
  });

  it('推送公钥为 null 时返回 null；订阅原样提交 toJSON()', async () => {
    stubFetch({ body: { publicKey: null } }, { status: 200 }, { status: 200 });
    expect(await notifications.getPushKey()).toBeNull();
    const sub = { endpoint: 'https://fcm.googleapis.com/x', expirationTime: null, keys: { p256dh: 'k', auth: 'a' } };
    await notifications.savePushSubscription(sub);
    await notifications.removePushSubscription(sub.endpoint);
    expect(calls[1]).toMatchObject({ url: '/api/notifications/push/subscriptions', method: 'POST', body: sub });
    expect(calls[2]).toMatchObject({ url: '/api/notifications/push/unsubscribe', body: { endpoint: sub.endpoint } });
  });
});

describe('实时推送', () => {
  it('推给本人的事件：登录后才连接 /api/ws/user，只转发认识的事件', () => {
    const got: ServerEvent[] = [];
    const off = subscribeEvents((e) => got.push(e));
    expect(FakeWS.instances.filter((w) => w.url.endsWith('/api/ws/user'))).toHaveLength(0);
    setLoggedIn(true);
    const ws = FakeWS.instances.find((w) => w.url.endsWith('/api/ws/user'))!;
    expect(ws.url).toMatch(/^ws:\/\/.+\/api\/ws\/user$/);
    ws.open();
    got.length = 0; // 连上时补发的 account 在下一个用例里检查
    ws.push({ type: 'account' });
    ws.push({ type: 'positions' }); // 复制源的持仓事件，forecast 不认识，忽略
    ws.push({ type: 'deposit', status: 'pending', network: 'ERC20', amount: '100', confirmations: 1 });
    ws.push({ type: 'unknown' });
    handleUserMessage({ type: 'notification' }); // 缺少 item 的忽略
    expect(got).toEqual([{ type: 'account' }, { type: 'deposit', status: 'pending', network: 'ERC20', amount: '100', confirmations: 1 }]);
    off();
  });

  it('第一次连上和断线重连后都让页面重新拉取账户（D-2）；退出登录后不再重连', () => {
    vi.useFakeTimers();
    const got: string[] = [];
    const off = subscribeEvents((e) => got.push(e.type));
    setLoggedIn(true);
    const first = FakeWS.instances.at(-1)!;
    first.open();
    // 第一次连上之前发生的变化收不到推送，所以第一次连上也要重新拉取
    expect(got).toEqual(['account']);
    got.length = 0;
    first.drop();
    vi.advanceTimersByTime(1000);
    const second = FakeWS.instances.at(-1)!;
    expect(second).not.toBe(first);
    second.open();
    expect(got).toEqual(['account']);
    setLoggedIn(false);
    const count = FakeWS.instances.length;
    second.drop();
    vi.advanceTimersByTime(60_000);
    expect(FakeWS.instances.length).toBe(count);
    off();
  });
});

describe('Web Push 订阅', () => {
  const KEY = 'BAECAwQ'; // base64url 的 [4, 1, 2, 3, 4]

  function stubBrowser(existing: { endpoint: string; key?: number[] } | null, permission: NotificationPermission = 'granted') {
    const state = { subscribed: 0, unsubscribed: 0 };
    const makeSub = (endpoint: string, key: number[]) => ({
      endpoint,
      options: { applicationServerKey: new Uint8Array(key).buffer },
      toJSON: () => ({ endpoint, keys: { p256dh: 'p', auth: 'a' } }),
      unsubscribe: async () => {
        state.unsubscribed += 1;
        return true;
      },
    });
    const pushManager = {
      getSubscription: async () => (existing ? makeSub(existing.endpoint, existing.key ?? [4, 1, 2, 3, 4]) : null),
      subscribe: async (o: { applicationServerKey: Uint8Array }) => {
        state.subscribed += 1;
        return makeSub('https://fcm.googleapis.com/new', [...o.applicationServerKey]);
      },
    };
    vi.stubGlobal('PushManager', function PushManager() {});
    vi.stubGlobal('Notification', { permission });
    Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: { getRegistration: async () => ({ pushManager }) } });
    return state;
  }

  afterEach(() => {
    Reflect.deleteProperty(navigator, 'serviceWorker');
  });

  it('base64url 公钥转字节', () => {
    expect([...base64UrlToBytes(KEY)]).toEqual([4, 1, 2, 3, 4]);
  });

  it('授权且推送开着：用公钥订阅并提交 toJSON()', async () => {
    const st = stubBrowser(null);
    stubFetch({ body: { publicKey: KEY } }, { status: 200 });
    await syncPushSubscription(true);
    expect(st.subscribed).toBe(1);
    expect(calls.map((c) => c.url)).toEqual(['/api/notifications/push/key', '/api/notifications/push/subscriptions']);
    expect(calls[1].body).toEqual({ endpoint: 'https://fcm.googleapis.com/new', keys: { p256dh: 'p', auth: 'a' } });
  });

  it('公钥为 null 不订阅；没有授权什么都不做', async () => {
    let st = stubBrowser(null);
    stubFetch({ body: { publicKey: null } });
    await syncPushSubscription(true);
    expect(st.subscribed).toBe(0);
    expect(calls).toHaveLength(1);
    st = stubBrowser(null, 'default');
    stubFetch();
    await syncPushSubscription(true);
    expect(calls).toHaveLength(0);
    expect(st.subscribed).toBe(0);
  });

  it('推送偏好关了、退出登录：取消这台设备的订阅', async () => {
    let st = stubBrowser({ endpoint: 'https://fcm.googleapis.com/old' });
    stubFetch({ status: 200 });
    await syncPushSubscription(false);
    expect(calls[0]).toMatchObject({ url: '/api/notifications/push/unsubscribe', body: { endpoint: 'https://fcm.googleapis.com/old' } });
    expect(st.unsubscribed).toBe(1);
    st = stubBrowser({ endpoint: 'https://fcm.googleapis.com/old' });
    stubFetch({ status: 200 });
    await unsubscribeThisDevice();
    expect(calls[0].url).toBe('/api/notifications/push/unsubscribe');
    expect(st.unsubscribed).toBe(1);
  });

  it('服务器换了公钥：重新订阅', async () => {
    const st = stubBrowser({ endpoint: 'https://fcm.googleapis.com/old', key: [9, 9] });
    stubFetch({ body: { publicKey: KEY } }, { status: 200 });
    await syncPushSubscription(true);
    expect(st.unsubscribed).toBe(1);
    expect(st.subscribed).toBe(1);
  });
});
