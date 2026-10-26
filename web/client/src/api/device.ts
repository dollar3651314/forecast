/**
 * 设备号（forecast S10，需求第 8.1 节"关联账户：同设备"）：这个浏览器第一次登录 / 注册时生成的随机号，存在 localStorage 的 `fc.device`，
 * 登录、注册时放在请求头 `X-Device-Id` 里；后端只存它的 SHA-256（account/device/DeviceLog）。不是硬件指纹，清掉浏览器数据会换一个。
 * 读写本地存储失败（隐私模式等）时每次生成一个新的，不影响登录。
 */
const KEY = 'fc.device';
const VALID = /^[A-Za-z0-9_-]{16,64}$/;

function fresh(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
}

export function deviceId(): string {
  try {
    const v = localStorage.getItem(KEY);
    if (v && VALID.test(v)) return v;
    const n = fresh();
    localStorage.setItem(KEY, n);
    return n;
  } catch {
    return fresh();
  }
}
