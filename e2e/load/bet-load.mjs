#!/usr/bin/env node
/**
 * 下注预压测（forecast S4；正式压测 AC-70 在 S12 的测试环境做，技术方案第 17 节"压测"）。
 *
 * 对一个以 e2e profile 启动的后端（e2e/start-backend.sh，专用的空库）：
 *   1. 准备：用 SQL 直接建 USERS 个客户和登录会话（注册接口按 IP 每小时只能发 30 次验证码，压测客户太多），
 *      经链替身给每个客户充值 DEPOSIT USDT（真实的充值入账代码，账本一致），后台建 3 个市场并上架；
 *   2. 开环施压：每秒 RATE 笔下注（不等上一笔返回），持续 DURATION 秒；一半打在同一个市场（单市场 RATE/2 笔/秒，需求 NF-01），
 *      另一半分给另外两个市场；每笔随机客户、随机选项、金额 1～10 USDT（两位小数）；
 *   3. 统计：成功、业务拒单、其他错误的笔数，耗时 p50 / p95 / p99 / 最大（毫秒，从发出请求到收到响应）；
 *   4. 对账：每个市场 Σ有效下注 = Σ选项奖池 = Σ持仓 = 账本里这个市场的下注锁定；没有客户的可用余额为负。
 *
 * 后端重启以后链替身的区块从头开始、扫块游标还在库里，充值扫不到：每次压测前用新的空库重启后端。
 * 用法（在仓库根目录）：
 *   MYSQL_CMD='docker exec -i <容器> mysql -uroot -p<本机测试库密码> forecast_e2e' \
 *   BASE_URL=http://127.0.0.1:8080 RATE=200 DURATION=60 USERS=1000 node e2e/load/bet-load.mjs
 * MYSQL_CMD 是能从标准输入执行 SQL 的命令（只在本机 / 压测机用；不要指向测试环境和生产的数据库）。
 * 管理员用 e2e 的一次性测试账号（FC_ADMIN_INIT_USERNAME / FC_ADMIN_INIT_PASSWORD 或 e2e/tests/support.ts 的默认值）。
 */
import { execSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080';
const RATE = Number(process.env.RATE || 200);
const SECONDS = Number(process.env.DURATION || 60);
const USERS = Number(process.env.USERS || 1000);
/** 预热：先按一半的速率跑 WARMUP 秒（不计入结果），让 JIT、连接池、缓冲池热起来，和长期运行的服务器一样 */
const WARMUP = Number(process.env.WARMUP || 20);
const DEPOSIT = process.env.DEPOSIT || '1000';
const MYSQL = process.env.MYSQL_CMD;
const ADMIN_USER = process.env.FC_ADMIN_INIT_USERNAME || 'e2e_admin';
const ADMIN_PASSWORDS = [process.env.E2E_ADMIN_NEW_PASSWORD || 'ci_only_admin_new_2', process.env.FC_ADMIN_INIT_PASSWORD || 'ci_only_admin_init_1'];
if (!MYSQL) throw new Error('要设置 MYSQL_CMD（能从标准输入执行 SQL 的命令）');

const sql = (text) => execSync(MYSQL + ' --batch --skip-column-names', { input: text, encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function call(method, path, body, cookie) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : undefined, headers: r.headers };
}

async function must(method, path, body, cookie) {
  const r = await call(method, path, body, cookie);
  if (r.status !== 200) throw new Error(`${method} ${path} → ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
}

async function adminCookie() {
  const login = (password) =>
    fetch(BASE + '/api/admin/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: ADMIN_USER, password }) });
  const cookieOf = (r) => r.headers.getSetCookie().find((c) => c.startsWith('fc_admin_session=')).split(';')[0];
  let r = await login(ADMIN_PASSWORDS[0]);
  if (r.status === 200) return cookieOf(r);
  // 新库：第一次用初始密码登录，要先改密码（和 e2e/tests/api.ts 同一个规则）
  r = await login(ADMIN_PASSWORDS[1]);
  if (r.status !== 200) throw new Error(`超管登录失败：${r.status}`);
  const changed = await fetch(BASE + '/api/admin/auth/change-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: cookieOf(r) },
    body: JSON.stringify({ oldPassword: ADMIN_PASSWORDS[1], newPassword: ADMIN_PASSWORDS[0] }),
  });
  if (changed.status !== 200) throw new Error(`首次登录改密码失败：${changed.status}`);
  return cookieOf(changed);
}

// ---------------------------------------------------------------------------
// 1. 准备
// ---------------------------------------------------------------------------
async function prepare() {
  const tag = Date.now().toString(36);
  const users = [];
  let values = [];
  for (let i = 0; i < USERS; i++) users.push({ email: `load_${tag}_${i}@example.com`, token: randomUUID() });
  for (let i = 0; i < users.length; i += 500) {
    const part = users.slice(i, i + 500);
    values = part.map((u) => `('${u.email}', 'ACTIVE')`).join(',');
    sql(`INSERT INTO account_user (email, status) VALUES ${values};`);
  }
  const ids = sql(`SELECT id, email FROM account_user WHERE email LIKE 'load\\_${tag}\\_%';`)
    .trim()
    .split('\n')
    .map((l) => l.split('\t'));
  const byEmail = new Map(ids.map(([id, email]) => [email, Number(id)]));
  for (const u of users) u.id = byEmail.get(u.email);
  for (let i = 0; i < users.length; i += 500) {
    const part = users.slice(i, i + 500);
    sql(`INSERT IGNORE INTO ledger_account (kind, user_id) VALUES ${part.map((u) => `('USER', ${u.id})`).join(',')};`);
    sql(
      `INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES ${part
        .map((u) => `(${u.id}, '${createHash('sha256').update(u.token).digest('hex')}', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY)`)
        .join(',')};`,
    );
  }
  for (const u of users) u.cookie = `fc_session=${u.token}`;
  log(`建了 ${users.length} 个客户和会话`);
  // 充值：每个客户取 TRC20 地址、在同一批区块里转账，最后出块确认（真实的入账代码）
  let n = 0;
  // 地址分配串行做：复制源的 AddressService 首次并发分配地址会在间隙锁上死锁（见开发记录/S4-下注奖池.md 遗留事项），和下注无关
  await pool(users, 1, async (u) => {
    const list = await must('GET', '/api/wallet/addresses', undefined, u.cookie);
    const trc = list.find((a) => a.network === 'TRC20');
    await must('POST', '/api/e2e/wallet/deposit', { chain: 'TRC20', address: trc.address, amount: DEPOSIT });
    if (++n % 200 === 0) log(`充值 ${n}/${users.length}`);
  });
  await must('POST', '/api/e2e/wallet/mine', { chain: 'TRC20', blocks: 25 });
  const credited = Number(sql(`SELECT COUNT(*) FROM ledger_account WHERE user_id IN (${users.map((u) => u.id).join(',')}) AND available >= ${DEPOSIT};`).trim());
  log(`充值到账 ${credited}/${users.length}`);
  if (credited !== users.length) throw new Error('有客户的充值没有到账');
  // 市场
  const admin = await adminCookie();
  const now = new Date((await must('GET', '/api/e2e/clock')).now).getTime();
  const at = (ms) => new Date(now + ms).toISOString();
  const t = (s) => ({ en: s, zhCN: s, zhTW: s });
  const markets = [];
  for (let i = 0; i < 3; i++) {
    const d = await must(
      'POST',
      '/api/admin/markets',
      {
        category: 'FOOTBALL',
        title: t(`压测市场 ${tag} ${i}`),
        rules: t('压测'),
        resolutionMode: 'AUTO',
        sourcePrimary: 'a',
        sourceBackup: 'b',
        openAt: at(-60_000),
        closeAt: at(86_400_000),
        eventStartAt: at(86_400_000),
        eventEndAt: at(90_000_000),
        options: [1, 2, 3].map((k) => ({ label: t(`选项${k}`), winCondition: t(`条件${k}`) })),
      },
      admin,
    );
    await must('POST', `/api/admin/markets/${d.publicNo}/publish`, { checked: true }, admin);
    markets.push({ no: d.publicNo, options: d.options.map((o) => o.optionNo) });
  }
  log(`上架了 ${markets.length} 个市场：${markets.map((m) => m.no).join(', ')}`);
  return { users, markets };
}

/** 并发 limit 个任务跑完 items */
async function pool(items, limit, fn) {
  let i = 0;
  await Promise.all(
    Array.from({ length: limit }, async () => {
      while (i < items.length) await fn(items[i++]);
    }),
  );
}

// ---------------------------------------------------------------------------
// 2. 施压
// ---------------------------------------------------------------------------
async function load({ users, markets }, rate = RATE, seconds = SECONDS) {
  const lat = [];
  const codes = new Map();
  const inflight = new Set();
  const total = rate * seconds;
  const start = performance.now();
  log(`开始：每秒 ${rate} 笔，${seconds} 秒，共 ${total} 笔（市场 ${markets[0].no} 占一半）`);
  for (let k = 0; k < total; k++) {
    const due = start + (k * 1000) / rate;
    const wait = due - performance.now();
    if (wait > 1) await new Promise((r) => setTimeout(r, wait));
    const u = users[Math.floor(Math.random() * users.length)];
    const m = Math.random() < 0.5 ? markets[0] : markets[1 + Math.floor(Math.random() * 2)];
    const option = m.options[Math.floor(Math.random() * m.options.length)];
    const amount = (1 + Math.floor(Math.random() * 900) / 100).toFixed(2);
    const t0 = performance.now();
    const p = call('POST', `/api/markets/${m.no}/bets`, { optionNo: option, amount, clientReqId: randomUUID() }, u.cookie)
      .then((r) => {
        lat.push(performance.now() - t0);
        const key = r.status === 200 ? 'OK' : `${r.status} ${r.body?.code}`;
        codes.set(key, (codes.get(key) || 0) + 1);
      })
      .catch((e) => {
        lat.push(performance.now() - t0);
        codes.set(`ERR ${e.code || e.message}`, (codes.get(`ERR ${e.code || e.message}`) || 0) + 1);
      })
      .finally(() => inflight.delete(p));
    inflight.add(p);
    if (k > 0 && k % (rate * 10) === 0) log(`已发 ${k} 笔，进行中 ${inflight.size}`);
  }
  await Promise.all(inflight);
  const elapsed = (performance.now() - start) / 1000;
  lat.sort((a, b) => a - b);
  const q = (p) => lat[Math.min(lat.length - 1, Math.floor((lat.length * p) / 100))].toFixed(1);
  const ok = codes.get('OK') || 0;
  const errors = [...codes.entries()].filter(([k]) => k.startsWith('ERR') || k.startsWith('5')).reduce((s, [, v]) => s + v, 0);
  return { elapsed, sent: total, ok, codes: Object.fromEntries(codes), errors, p50: q(50), p95: q(95), p99: q(99), max: lat[lat.length - 1].toFixed(1) };
}

// ---------------------------------------------------------------------------
// 3. 对账
// ---------------------------------------------------------------------------
function reconcile(markets) {
  const out = [];
  for (const m of markets) {
    const [bets, pools, positions, locked] = sql(
      `SELECT (SELECT COALESCE(SUM(b.amount),0) FROM pool_bet b JOIN market_question q ON q.id = b.market_id WHERE q.public_no = ${m.no} AND b.status = 'ACTIVE'),
              (SELECT COALESCE(SUM(t.amount),0) FROM pool_option_total t JOIN market_question q ON q.id = t.market_id WHERE q.public_no = ${m.no}),
              (SELECT COALESCE(SUM(p.amount),0) FROM pool_position p JOIN market_question q ON q.id = p.market_id WHERE q.public_no = ${m.no}),
              (SELECT COALESCE(SUM(l.amount),0) FROM ledger_entry l JOIN market_question q ON q.id = l.ref_market_id WHERE q.public_no = ${m.no} AND l.entry_type = 'BET_LOCK' AND l.bucket = 'BET_LOCKED');`,
    )
      .trim()
      .split('\t');
    out.push({ market: m.no, bets, pools, positions, locked, ok: bets === pools && pools === positions && positions === locked });
  }
  const negative = Number(sql(`SELECT COUNT(*) FROM ledger_account WHERE available < 0 OR bet_locked < 0;`).trim());
  return { markets: out, negative };
}

/** MySQL 的语句计数（整个实例），压测前后相减 ÷ 笔数 = 每笔下注大约几条语句 */
function counters() {
  const names = ['Com_select', 'Com_insert', 'Com_update', 'Com_delete', 'Com_set_option', 'Com_commit', 'Questions'];
  return Object.fromEntries(
    sql(`SHOW GLOBAL STATUS WHERE Variable_name IN (${names.map((n) => `'${n}'`).join(',')});`)
      .trim()
      .split('\n')
      .map((l) => l.split('\t'))
      .map(([k, v]) => [k, Number(v)]),
  );
}

const data = await prepare();
if (WARMUP > 0) {
  const w = await load(data, Math.max(1, Math.round(RATE / 2)), WARMUP);
  log('预热（不计入结果）', JSON.stringify({ sent: w.sent, ok: w.ok, p99: w.p99 }));
}
const before = counters();
const r = await load(data);
const after = counters();
log('每笔下注的 MySQL 语句（整个实例的计数之差 ÷ 笔数，含会话、限流以外的全部）', JSON.stringify(Object.fromEntries(Object.keys(after).map((k) => [k, ((after[k] - before[k]) / r.sent).toFixed(1)]))));
log('结果', JSON.stringify(r));
const rec = reconcile(data.markets);
log('对账', JSON.stringify(rec));
console.log(
  `\n下注预压测：先按 ${Math.round(RATE / 2)} 笔/秒预热 ${WARMUP} 秒，再按 ${RATE} 笔/秒 × ${SECONDS} 秒（单市场 ${RATE / 2} 笔/秒），实际用时 ${r.elapsed.toFixed(1)} 秒；` +
    `成功 ${r.ok}，其他 ${JSON.stringify(r.codes)}；耗时（毫秒）p50 ${r.p50} / p95 ${r.p95} / p99 ${r.p99} / 最大 ${r.max}；` +
    `对账 ${rec.markets.every((m) => m.ok) ? '一致' : '不一致'}，余额为负的账户 ${rec.negative} 个`,
);
if (!rec.markets.every((m) => m.ok) || rec.negative > 0 || r.errors > 0) process.exit(1);
