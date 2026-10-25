/**
 * 内存模拟数据库：页面上的操作会真实修改这里的数据（刷新浏览器后恢复初始值）。
 * 数据刻意包含：超过一页的数量、大额和多位小数、超长名称、中文名、含 <b> 的名称（规则 C50）。
 */
import type {
  AdminUser,
  AuditLog,
  Chain,
  CustomerSummary,
  Deposit,
  KycApplication,
  NotifyRecord,
  NotifyTemplate,
  ReconItem,
  Role,
  ParamItem,
  SystemConfig,
  HotWalletBalance,
  TaskItem,
  Withdrawal,
} from '../../api/types';
import { ALL_PERMISSION_CODES, type PermissionCode } from '../../config/permissions';
import { subtractAmounts } from '../../utils/money';
import {
  CHINESE_NAMES,
  EMAIL_DOMAINS,
  NATIONALITIES,
  createRng,
  evmAddress,
  minutesAgo,
  tronAddress,
} from './seed';

/**
 * 对外编号（出金、入金）：和后端 common/id/Snowflake 同一格式的雪花 ID 字符串
 * （41 位毫秒时间从 2026-01-01 起 + 节点号 1 + 12 位序号）。超过 JavaScript 安全整数，只用 BigInt 生成、一律当字符串（v2 第 11.4 节）。
 * 原型数据固定在 2026-10-14，编号也固定，刷新后不变。
 */
export function mockNo(minutesBefore: number, seq: number): string {
  const ms = Date.UTC(2026, 9, 14, 8, 0, 0) - minutesBefore * 60_000 - Date.UTC(2026, 0, 1);
  return ((BigInt(ms) << 22n) | (1n << 12n) | BigInt(seq & 0xfff)).toString();
}

const rng = createRng(20261014);

const pinyin = [
  'zhangsan', 'liwei88', 'wangwu', 'chenxiao', 'zhaomin', 'liuyang', 'sunlei', 'zhouting', 'wuhaotian', 'zhengshuang',
  'ouyang.nana', 'test.b', 'alexandria.kw', 'huangxm', 'mali', 'yangyang', 'linzq', 'hejing', 'gaofeng', 'luolin',
  'liangsc', 'xiett', 'hanxue', 'tangyr', 'fengcc', 'caoyu', 'pengyy', 'zengxx', 'lvym', 'sufei',
  'lucas.martin', 'jiangxin', 'caiwj', 'jiabaoyu', 'dingyi', 'weilai', 'xuebing', 'yezi', 'simacf', 'yuhuanshui',
  'panduola', 'duziteng', 'diana.p', 'xiayu', 'zhongnan', 'wangyang', 'tiantian', 'renyy', 'jiangzy', 'fanbb',
  'fangyuan', 'shilei', 'yaochen', 'tanyl', 'liaofan', 'xiongda', 'jinlili',
];

// ---------------- 客户 ----------------
export const customers: CustomerSummary[] = CHINESE_NAMES.map((name, i) => {
  const kycCycle: CustomerSummary['kycStatus'][] = ['approved', 'approved', 'pending', 'none', 'approved', 'rejected', 'approved'];
  return {
    id: 100482 + i * 7,
    name,
    email: `${pinyin[i] ?? 'user' + i}@${i < 8 ? ['qq.com', '163.com', 'foxmail.com', '126.com', 'outlook.com', 'sina.com', 'qq.com', '163.com'][i] : rng.pick(EMAIL_DOMAINS)}`,
    nationality: rng.pick(NATIONALITIES),
    status: i === 6 || i === 21 || i === 40 ? 'frozen' : 'active',
    kycStatus: i < 3 ? 'approved' : kycCycle[i % kycCycle.length],
    balance: i === 0 ? '6820.40' : i === 12 ? '1250000.75' : i === 35 ? '0.00' : rng.amount(0, 48000),
    registeredAt: minutesAgo(60 * 24 * (i === 0 ? 30 : rng.int(1, 300)) + rng.int(0, 600)),
    lastLoginAt: i % 9 === 8 ? null : minutesAgo(rng.int(5, 60 * 24 * 7)),
    // forecast S10：员工账户（第 4 个客户是登记过的员工）
    staff: i === 3,
  };
});

// 今天注册的 6 个（仪表盘"今日注册"）
customers.slice(-6).forEach((c, i) => {
  c.registeredAt = minutesAgo(30 + i * 85);
  c.kycStatus = 'none';
});

export const customerById = (id: number) => customers.find((c) => c.id === id);

/**
 * 后台编辑过的客户资料（v2 E1）：没编辑过的客户按姓名拆出名和姓（getCustomer 里），编辑后以这里为准。
 */
export const customerProfiles = new Map<number, { firstName: string; middleName: string; lastName: string; birthDate: string }>();

/** 最近登录 IP（v2 E6，示意：文档用的保留地址段）；从没登录过的客户为空 */
export const lastLoginIpOf = (c: CustomerSummary): string | null => (c.lastLoginAt ? `203.0.113.${(c.id * 7) % 250}` : null);

// ---------------- KYC ----------------
export const kycApplications: KycApplication[] = customers
  .filter((c) => c.kycStatus !== 'none')
  .map((c, i): KycApplication => {
    const status = c.kycStatus as KycApplication['status'];
    const passport = c.nationality !== 'CN';
    return {
      id: `KYC-${String(20261000 + i * 13)}`,
      customerId: c.id,
      name: c.name,
      email: c.email,
      nationality: c.nationality,
      birthDate: `19${rng.int(70, 99)}-${String(rng.int(1, 12)).padStart(2, '0')}-${String(rng.int(1, 28)).padStart(2, '0')}`,
      docType: passport ? 'passport' : 'id_card',
      docNumber: passport ? `E${rng.int(1000, 9999)}****${rng.int(10, 99)}` : `4403${rng.int(10, 99)}**********${rng.int(1000, 9999)}`.slice(0, 18),
      status,
      submittedAt: minutesAgo(status === 'pending' ? rng.int(20, 60 * 20) : rng.int(60 * 24, 60 * 24 * 60)),
      reviewer: status === 'pending' ? null : rng.pick(['李娜', '赵强']),
      reviewedAt: status === 'pending' ? null : minutesAgo(rng.int(60 * 2, 60 * 24 * 20)),
      rejectReason: status === 'rejected' ? rng.pick(['证件照片模糊，无法辨认证件号', '手持自拍与证件照不是同一人', '证件已过期']) : null,
    };
  })
  .sort((a, b) => (a.submittedAt < b.submittedAt ? 1 : -1));

// ---------------- 入金 ----------------
const chains: Chain[] = ['TRC20', 'TRC20', 'TRC20', 'ERC20', 'BSC'];
const requiredConf: Record<Chain, number> = { ERC20: 12, TRC20: 19, BSC: 15 };

export const deposits: Deposit[] = Array.from({ length: 46 }, (_, i) => {
  const c = customers[(i * 5) % (customers.length - 6)];
  const chain = rng.pick(chains);
  const status: Deposit['status'] = i < 3 ? 'confirming' : i === 7 ? 'ignored' : i === 15 ? 'reversed' : 'credited';
  const req = requiredConf[chain];
  const detected = rng.int(10 + i * 50, 60 + i * 60);
  return {
    id: mockNo(i * 180, 900 - i),
    customerId: c.id,
    customerName: c.name,
    email: c.email,
    chain,
    txHash: chain === 'TRC20' ? rng.hex(64) : '0x' + rng.hex(64),
    amount: i === 4 ? '120000.000000' : i === 0 ? '12000.00' : rng.amount(50, 30000, rng.bool(0.3) ? 6 : 2),
    confirmations: status === 'confirming' ? rng.int(1, req - 1) : req + rng.int(0, 400),
    requiredConfirmations: req,
    status,
    detectedAt: minutesAgo(detected),
    creditedAt: status === 'credited' || status === 'reversed' ? minutesAgo(detected - 3) : null,
  };
});

// ---------------- 出金 ----------------
interface WdSeed {
  ci: number;
  amount: string;
  chain: Chain;
  status: Withdrawal['status'];
  ago: number;
  first?: boolean;
  delay?: number;
}

const wdSeeds: WdSeed[] = [
  { ci: 0, amount: '5000.00', chain: 'TRC20', status: 'pending', ago: 428, first: true },
  { ci: 1, amount: '800.00', chain: 'BSC', status: 'pending', ago: 435 },
  { ci: 2, amount: '12000.00', chain: 'ERC20', status: 'delayed', ago: 939, delay: 135 },
  { ci: 3, amount: '2350.00', chain: 'TRC20', status: 'pending', ago: 453 },
  { ci: 4, amount: '1200.00', chain: 'TRC20', status: 'pending', ago: 470 },
  { ci: 5, amount: '4500.00', chain: 'BSC', status: 'delayed', ago: 742, delay: 292 },
  { ci: 6, amount: '680.00', chain: 'TRC20', status: 'pending', ago: 544 },
  { ci: 7, amount: '3000.00', chain: 'ERC20', status: 'pending', ago: 568 },
  { ci: 8, amount: '1500.00', chain: 'TRC20', status: 'pending', ago: 590, first: true },
  { ci: 9, amount: '920.00', chain: 'BSC', status: 'pending', ago: 612 },
  { ci: 10, amount: '1100.00', chain: 'TRC20', status: 'pending', ago: 640 },
  { ci: 11, amount: '600.00', chain: 'TRC20', status: 'pending', ago: 700 },
  { ci: 12, amount: '880.00', chain: 'ERC20', status: 'pending', ago: 760 },
  { ci: 13, amount: '400.00', chain: 'TRC20', status: 'pending', ago: 790 },
  { ci: 14, amount: '4500.00', chain: 'TRC20', status: 'delayed', ago: 520, delay: 318 },
  { ci: 15, amount: '1260.00', chain: 'TRC20', status: 'cooling', ago: 35 },
  { ci: 16, amount: '500.00', chain: 'BSC', status: 'cooling', ago: 48 },
  { ci: 17, amount: '1000.00', chain: 'TRC20', status: 'cooling', ago: 66 },
  { ci: 18, amount: '700.00', chain: 'ERC20', status: 'cooling', ago: 80 },
  { ci: 19, amount: '800.00', chain: 'TRC20', status: 'cooling', ago: 102 },
  { ci: 20, amount: '9800.00', chain: 'TRC20', status: 'completed', ago: 300 },
  { ci: 22, amount: '2000.00', chain: 'BSC', status: 'completed', ago: 330 },
  { ci: 23, amount: '3500.00', chain: 'ERC20', status: 'completed', ago: 360 },
  { ci: 24, amount: '1550.00', chain: 'TRC20', status: 'completed', ago: 380 },
  { ci: 25, amount: '3000.00', chain: 'TRC20', status: 'completed', ago: 420 },
  { ci: 26, amount: '2000.00', chain: 'TRC20', status: 'completed', ago: 455 },
  { ci: 27, amount: '1000.00', chain: 'BSC', status: 'completed', ago: 480 },
  { ci: 28, amount: '2500.00', chain: 'ERC20', status: 'completed', ago: 500 },
  { ci: 29, amount: '1500.00', chain: 'TRC20', status: 'completed', ago: 560 },
  { ci: 30, amount: '660.00', chain: 'TRC20', status: 'broadcasting', ago: 210 },
  { ci: 31, amount: '15.00', chain: 'TRC20', status: 'rejected', ago: 1600 },
  { ci: 32, amount: '7000.00', chain: 'ERC20', status: 'cancelled', ago: 2100 },
  { ci: 33, amount: '250.00', chain: 'BSC', status: 'failed', ago: 1900 },
];

export const withdrawals: Withdrawal[] = wdSeeds.map((s, i) => {
  const c = customers[s.ci];
  const addr = s.chain === 'TRC20' ? tronAddress(rng) : evmAddress(rng);
  return {
    id: mockNo(i * 47, 318 - i),
    customerId: c.id,
    customerName: c.name,
    email: c.email,
    amount: s.amount,
    fee: s.chain === 'ERC20' ? '5.00' : '1.00',
    chain: s.chain,
    address: addr,
    firstUse: !!s.first,
    whitelistAddedAt: s.first ? '2026-10-12 08:30:00' : minutesAgo(60 * 24 * rng.int(5, 90)),
    status: s.status,
    delayRemainingMinutes: s.delay ?? null,
    submittedAt: minutesAgo(s.ago),
    reviewer: ['delayed', 'completed', 'broadcasting', 'rejected', 'cancelled', 'failed'].includes(s.status) ? '李娜' : null,
    reason:
      s.status === 'rejected'
        ? '提现地址与入金来源不一致，请客户补充说明'
        : s.status === 'cancelled'
          ? '客户来电称账户被盗，紧急取消'
          : s.status === 'failed'
            ? '广播失败：节点返回 nonce too low，已自动退款'
            : null,
    txHash: s.status === 'completed' ? (s.chain === 'TRC20' ? rng.hex(64) : '0x' + rng.hex(64)) : null,
  };
});

// v2 自动审批原型（技术方案/v2-提现自动审批.md）补的 6 笔小额出金：自动通过的、冷静期预判通过的（含金额正好等于上限的）、
// 总开关关闭时本可以自动通过的、第 1 版时自动通过后上链失败的。客户挑 KYC 已通过、注册满 14 天、有入金、还没有出金的，规则判定才前后一致。
// 用单独的随机数，不影响上面和后面数据的生成顺序。判定记录在 ./autoApprove.ts
const autoRng = createRng(20261017);
/** 注册天数：和模拟层出金详情的"注册时长"同一算法 */
export function registeredDaysOf(c: CustomerSummary): number {
  if (c.id === 100482) return 30;
  return Math.max(1, Math.round((Date.UTC(2026, 9, 14) - Date.parse(c.registeredAt.replace(' ', 'T') + 'Z')) / 86_400_000));
}
const autoCustomers = customers
  .filter((c) => c.kycStatus === 'approved' && registeredDaysOf(c) >= 14 && deposits.some((d) => d.customerId === c.id && d.status === 'credited'))
  .filter((c) => !withdrawals.some((w) => w.customerId === c.id));
const autoSeeds: Omit<WdSeed, 'ci'>[] = [
  { amount: '320.00', chain: 'TRC20', status: 'completed', ago: 260 },
  { amount: '180.00', chain: 'BSC', status: 'broadcasting', ago: 140 },
  { amount: '450.00', chain: 'TRC20', status: 'cooling', ago: 25 },
  { amount: '260.00', chain: 'TRC20', status: 'pending', ago: 560 },
  { amount: '500.00', chain: 'TRC20', status: 'cooling', ago: 52 },
  { amount: '260.00', chain: 'BSC', status: 'failed', ago: 1880 },
];
/** 这几笔出金的客户 ID（顺序同 autoSeeds；第 7 个是下面 R13 那笔的客户） */
export const autoSeedCustomerIds: number[] = [];
autoSeeds.forEach((s, i) => {
  const c = autoCustomers[i];
  if (!c) return;
  autoSeedCustomerIds.push(c.id);
  withdrawals.push({
    id: mockNo(s.ago + 3, 3000 + i),
    customerId: c.id,
    customerName: c.name,
    email: c.email,
    amount: s.amount,
    fee: s.chain === 'BSC' ? '0.50' : '1.00',
    chain: s.chain,
    address: s.chain === 'TRC20' ? tronAddress(autoRng) : evmAddress(autoRng),
    firstUse: false,
    whitelistAddedAt: minutesAgo(60 * 24 * autoRng.int(20, 60)),
    status: s.status,
    delayRemainingMinutes: null,
    submittedAt: minutesAgo(s.ago),
    reviewer: null,
    reason: s.status === 'failed' ? '广播失败：节点明确拒绝 3 次，已自动退款' : null,
    txHash: s.status === 'completed' ? autoRng.hex(64) : null,
  });
});

// v1.2 R13 盈利客户（Amos 2026-10-18）补的 1 笔：待审核 300 USDT，只触发 R13（判定记录在 ./autoApprove.ts 的 x: 6）。
// 上面挑客户的条件（注册满 14 天）已经没有剩下的客户，这里放宽到注册满 7 天（R7 的默认值），仍然要 KYC 已通过、有入金、还没有出金。
// 放在上面 6 笔之后、用同一个随机数接着取，不影响前面已有数据的生成。
const r13Customer = customers.find(
  (c) => c.kycStatus === 'approved' && registeredDaysOf(c) >= 7 && deposits.some((d) => d.customerId === c.id && d.status === 'credited') && !withdrawals.some((w) => w.customerId === c.id),
);
if (r13Customer) {
  autoSeedCustomerIds.push(r13Customer.id);
  withdrawals.push({
    id: mockNo(503, 3006),
    customerId: r13Customer.id,
    customerName: r13Customer.name,
    email: r13Customer.email,
    amount: '300.00',
    fee: '1.00',
    chain: 'TRC20',
    address: tronAddress(autoRng),
    firstUse: false,
    whitelistAddedAt: minutesAgo(60 * 24 * 6),
    status: 'pending',
    delayRemainingMinutes: null,
    submittedAt: minutesAgo(500),
    reviewer: null,
    reason: null,
    txHash: null,
  });
}

// 以前已完成的出金（2026-10-17 补，修正原型演示数据前后不一致）：firstUse = false 表示这个客户以前往这个地址成功提现过（和后端 first_use 同一口径），
// 以前没有这几笔，规则判定 R4"此前已完成提现 N 笔"和出金详情的"最近出金：无"对不上。这里补上：
// - 地址不是第一次用的：往同一个地址补 1～2 笔；
// - 新地址首次提现（firstUse = true）：往另一个地址补 1 笔（不是第一笔提现，但这个地址没成功提现过）；
// - FIRST_EVER 里的客户是第一笔提现，不补（并把 firstUse 改成 true）。
// 都在 6 天以前，金额是累计入金的一成左右；用单独的随机数，不影响其他数据。默认的"进行中"筛选看不到它们。
const histRng = createRng(20261018);
/** 第一笔提现的客户（customers 下标）：规则判定里 R4 未通过 */
const FIRST_EVER = new Set([8, 31]);
const creditedOf = (customerId: number) =>
  customerId === 100482 ? 20000 : deposits.filter((d) => d.customerId === customerId && d.status === 'credited').reduce((s, d) => s + Number(d.amount), 0);
const history: Withdrawal[] = [];
withdrawals.forEach((w, i) => {
  if (FIRST_EVER.has(customers.findIndex((c) => c.id === w.customerId))) {
    w.firstUse = true;
    return;
  }
  const n = w.firstUse ? 1 : histRng.int(1, 2);
  const amount = Math.max(50, Math.floor((creditedOf(w.customerId) * 0.1) / 10) * 10).toFixed(2);
  for (let k = 0; k < n; k++) {
    const ago = 60 * 24 * (6 + histRng.int(0, 20) + k * 9) + histRng.int(0, 600);
    history.push({
      ...w,
      id: mockNo(ago, 3500 + i * 3 + k),
      amount,
      address: w.firstUse ? (w.chain === 'TRC20' ? tronAddress(histRng) : evmAddress(histRng)) : w.address,
      firstUse: false,
      status: 'completed',
      delayRemainingMinutes: null,
      submittedAt: minutesAgo(ago),
      reviewer: '李娜',
      reason: null,
      txHash: w.chain === 'TRC20' ? histRng.hex(64) : '0x' + histRng.hex(64),
    });
  }
});
withdrawals.push(...history);

// ---------------- 对账（字段和问题类型按后端，后台方案第 8 节） ----------------
export const reconItems: ReconItem[] = [
  { type: 'deposit_ledger_mismatch', ref: 'DEPOSIT:41207', expected: '2000', actual: '1999.99', detail: '充值记录的金额和账本入账不一致', status: 'open' },
  { type: 'suspense_open', ref: '17', expected: '0', actual: '2019.45', detail: '充值冲正时余额不够扣的差额，待处理（充值 #41188）', status: 'open' },
  { type: 'deposit_unknown_credit', ref: 'DEPOSIT:40990', expected: '0', actual: '88', detail: '账本里有充值入账，钱包里没有对应的链上充值', status: 'open' },
  { type: 'withdraw_frozen_mismatch', ref: '100482', expected: '5000', actual: '5200', detail: '账户的提现冻结应该等于进行中的提现合计', status: 'open' },
  { type: 'deposit_not_on_chain', ref: 'DEPOSIT:40871', expected: '300', actual: '0', detail: '入账时的区块哈希和链上现在的不一致', status: 'resolved' },
  { type: 'withdraw_ledger_mismatch', ref: '30112', expected: '45000.123456', actual: '45000.12', detail: '提现的账本记录和状态不一致', status: 'resolved' },
  { type: 'deposit_reversal_mismatch', ref: 'DEPOSIT:40655', expected: '760', actual: '0', detail: '冲正金额 + 挂账 应该等于充值金额', status: 'open' },
].map((r, i) => {
  const c = r.type === 'deposit_unknown_credit' ? null : customers[(i * 3 + 2) % 40];
  const detectedAt = minutesAgo(rng.int(60, 60 * 24 * 5));
  return {
    id: String(1001 + i),
    type: r.type as ReconItem['type'],
    ref: r.ref,
    customerId: c?.id ?? null,
    customerName: c?.name ?? null,
    expected: r.expected,
    actual: r.actual,
    diff: subtractAmounts(r.actual, r.expected, 6),
    detail: r.detail,
    status: r.status as ReconItem['status'],
    note: r.status === 'resolved' ? '已与链上核对，人工补记入账' : null,
    handler: r.status === 'resolved' ? '李娜' : null,
    detectedAt,
    resolvedAt: r.status === 'resolved' ? detectedAt : null,
  };
});

// ---------------- 任务表（只有邮件、推送；提现的广播和确认不走任务表，后台方案第 8 节） ----------------
const taskTypes: TaskItem['type'][] = ['email', 'push', 'email', 'push', 'email', 'push'];
export const tasks: TaskItem[] = Array.from({ length: 34 }, (_, i) => {
  const type = taskTypes[i % taskTypes.length];
  const status: TaskItem['status'] =
    i === 1 || i === 9 || i === 16 || i === 22 ? 'failed' : i === 3 || i === 11 ? 'processing' : i === 5 || i === 18 ? 'pending' : 'success';
  const maxAttempts = 5;
  const attempts = status === 'failed' ? maxAttempts : status === 'pending' ? rng.int(1, 3) : status === 'processing' ? rng.int(1, 2) : rng.int(1, 2);
  const errors: Record<TaskItem['type'], string> = {
    email: 'SMTP 550：收件邮箱不存在（noreply 退信）',
    push: '推送服务返回 410：订阅已失效',
  };
  return {
    id: String(78000 + 34 - i),
    type,
    bizRef: `NT-${String(553000 + i)}`,
    status,
    attempts,
    maxAttempts,
    nextRetryAt: status === 'pending' ? minutesAgo(-rng.int(1, 30)) : null,
    lastError: status === 'failed' || status === 'pending' ? errors[type] : null,
    createdAt: minutesAgo(i * 23 + 5),
    updatedAt: minutesAgo(i * 23),
  };
});

// ---------------- 通知 ----------------
interface TplSeed {
  code: string;
  zh: string;
  en: string;
  tZh: string;
  tEn: string;
  bZh: string;
  bEn: string;
  vars: string[];
  builtIn?: boolean;
  channels?: NotifyTemplate['channels'];
  /** forecast S9：需求第 7 节编号、繁体、受众、员工模板的收件人权限点 */
  req?: string;
  tTw?: string;
  bTw?: string;
  audience?: NotifyTemplate['audience'];
  perms?: string[];
}
const tplSeeds: TplSeed[] = [
  { code: 'deposit_credited', req: '#3', zh: '入金到账', en: 'Deposit credited', tZh: '充值已到账', tEn: 'Deposit credited', tTw: '充值已到帳', bZh: '你通过 {chain} 充值的 {amount} USDT 已到账，已计入可用余额。', bEn: 'Your deposit of {amount} USDT via {chain} has been credited to your available balance.', bTw: '你透過 {chain} 充值的 {amount} USDT 已到帳，已計入可用餘額。', vars: ['amount', 'chain'] },
  { code: 'withdraw_status', req: '#5', zh: '出金状态变化', en: 'Withdrawal status', tZh: '提现状态更新', tEn: 'Withdrawal update', tTw: '提現狀態更新', bZh: '你的提现 {orderId} 状态已更新为：{status}。', bEn: 'Your withdrawal {orderId} is now: {status}.', bTw: '你的提現 {orderId} 狀態已更新為：{status}。', vars: ['orderId', 'status'] },
  { code: 'kyc_result', req: '#6', zh: 'KYC 结果', en: 'KYC result', tZh: '身份认证结果', tEn: 'Verification result', tTw: '身分認證結果', bZh: '你的身份认证{result}。{reason}', bEn: 'Your identity verification was {result}. {reason}', bTw: '你的身分認證{result}。{reason}', vars: ['result', 'reason'] },
  { code: 'email_code', req: '#2', audience: 'any', zh: '邮箱验证码', en: 'Email code', tZh: 'Quick Come 验证码', tEn: 'Quick Come verification code', tTw: 'Quick Come 驗證碼', bZh: '你的验证码是 {code}，10 分钟内有效。', bEn: 'Your code is {code}. It expires in 10 minutes.', bTw: '你的驗證碼是 {code}，10 分鐘內有效。', vars: ['code'], channels: ['email'] },
  { code: 'settle_won', req: '#11', zh: '结算：我赢了', en: 'Settled: you won', tZh: '「{market}」已结算：你押中了', tEn: '"{marketEn}" settled: your option won', tTw: '「{market}」已結算：你押中了', bZh: '「{market}」结果是「{option}」。你的派彩 {payout} USDT 已到可用余额。', bEn: '"{marketEn}" resolved to "{optionEn}". Your payout of {payout} USDT is in your available balance.', bTw: '「{market}」結果是「{option}」。你的派彩 {payout} USDT 已到可用餘額。', vars: ['market', 'marketEn', 'option', 'optionEn', 'payout'], channels: ['inbox', 'push'] },
  { code: 'market_clarified', req: '#18', zh: '市场发布补充说明', en: 'Market clarification', tZh: '「{market}」发布了补充说明', tEn: 'Clarification for "{marketEn}"', tTw: '「{market}」發布了補充說明', bZh: '你下注的市场「{market}」发布了补充说明：\n{text}', bEn: 'A clarification was published for "{marketEn}":\n{textEn}', bTw: '你下注的市場「{market}」發布了補充說明：\n{text}', vars: ['market', 'marketEn', 'text', 'textEn'], channels: ['inbox'] },
  { code: 'resolve_proposed', audience: 'staff', perms: ['resolve:confirm'], zh: '裁决提议待确认', en: 'Proposed result awaiting confirmation', tZh: '市场 {marketNo} 有提议待确认', tEn: 'Market {marketNo}: proposed result awaiting confirmation', bZh: '市场 {marketNo}（{market}）有新的裁决提议：{option}。', bEn: 'A result has been proposed for market {marketNo} ({marketEn}): {optionEn}.', vars: ['marketNo', 'market', 'marketEn', 'option', 'optionEn'], channels: ['email'] },
  // forecast S10（迁移 V19）：风控告警，发给有 risk_alert:view 的管理员
  { code: 'risk_alert', audience: 'staff', perms: ['risk_alert:view'], zh: '风控告警', en: 'Risk alert', tZh: '风控告警：{kind}（客户 {userId}）', tEn: 'Risk alert: {kindEn} (customer {userId})', bZh: '风控规则发现一条新的告警：\n类型：{kind}\n客户：{userId}\n市场：{market}\n金额：{amount} USDT\n说明：{summary}\n请到后台"风控告警"查看，核对后标记已处理或误报。', bEn: 'A risk rule raised a new alert:\nType: {kindEn}\nCustomer: {userId}\nMarket: {market}\nAmount: {amount} USDT\nDetails: {summaryEn}\nPlease review it in the admin console (Risk alerts) and mark it handled or a false positive.', vars: ['kind', 'kindEn', 'userId', 'market', 'amount', 'summary', 'summaryEn'], channels: ['email'] },
  { code: 'reversal_shortfall', audience: 'staff', zh: '冲正挂账（发给运营、财务）', en: 'Reversal shortfall (to staff)', tZh: '冲正余额不足，已挂账', tEn: 'Reversal shortfall recorded', bZh: '客户 {customerId} 的入金 {txHash} 被链上回滚，余额不足，已生成挂账。', bEn: 'Deposit {txHash} of customer {customerId} was reversed; shortfall recorded.', vars: ['customerId', 'txHash'], channels: ['email'] },
];

export const templates: NotifyTemplate[] = tplSeeds.map((t, i) => ({
  id: `TPL-${String(i + 1).padStart(2, '0')}`,
  code: t.code,
  audience: t.audience ?? 'user',
  reqNo: t.req ?? null,
  nameZh: t.zh,
  nameEn: t.en,
  builtIn: t.builtIn ?? true,
  enabled: true,
  channels: t.channels ?? ['inbox', 'push', 'email'],
  titleZh: t.tZh,
  bodyZh: t.bZh,
  titleEn: t.tEn,
  bodyEn: t.bEn,
  titleTw: t.tTw ?? '',
  bodyTw: t.bTw ?? '',
  variables: t.vars,
  staffPerms: t.perms ?? [],
  updatedAt: minutesAgo(60 * 24 * (i + 1)),
}));

export const notifyRecords: NotifyRecord[] = Array.from({ length: 52 }, (_, i) => {
  const c = customers[(i * 5 + 1) % customers.length];
  const tpl = templates[i % templates.length];
  const channel = (['inbox', 'push', 'email'] as const)[i % 3];
  const status: NotifyRecord['status'] = i % 10 === 3 ? 'failed' : i % 7 === 5 && channel !== 'inbox' ? 'skipped' : 'sent';
  const manual = i === 6 || i === 20;
  return {
    id: `NT-${String(553100 - i)}`,
    customerId: c.id,
    customerName: c.name,
    source: manual ? 'manual' : 'template',
    templateCode: manual ? null : tpl.code,
    channel,
    title: manual ? '关于您账户安全的提醒' : tpl.titleZh,
    status,
    error: status === 'failed' ? (channel === 'push' ? '推送服务返回 410：订阅已失效' : channel === 'email' ? 'SMTP 550：邮箱不存在' : '写入失败') : status === 'skipped' ? '客户已关闭该渠道' : null,
    operator: manual ? '李娜' : null,
    time: minutesAgo(i * 29 + 3),
  };
});

// ---------------- 角色、管理员 ----------------
const opsPerms: PermissionCode[] = [
  'dashboard:view', 'customer:view', 'customer:freeze', 'kyc:view', 'kyc:review',
  'deposit:view', 'withdrawal:view', 'withdrawal:review', 'recon:view', 'recon:resolve', 'sweep:view', 'sweep:operate', 'task:view', 'task:retry',
  'notify_template:view', 'notify:send', 'notify_record:view', 'admin:view', 'admin:edit', 'audit:view',
];
// forecast S1：复制源"风控"角色的交易、风控、品种权限删除，只剩出金紧急取消和查看；裁决员、财务等角色按需求第 2.2 节在 S3 以后加
const riskPerms: PermissionCode[] = ['dashboard:view', 'customer:view', 'withdrawal:view', 'withdrawal:emergency_cancel', 'audit:view'];

export const roles: Role[] = [
  { id: 'R-1', code: 'super_admin', nameZh: '超级管理员', nameEn: 'Super admin', description: '拥有全部权限；只有超级管理员能分配本角色', permissions: [...ALL_PERMISSION_CODES], builtIn: true, memberCount: 1 },
  { id: 'R-2', code: 'operations', nameZh: '运营', nameEn: 'Operations', description: 'KYC 审核、出金审核、客户服务、通知', permissions: opsPerms, builtIn: false, memberCount: 4 },
  { id: 'R-3', code: 'risk', nameZh: '风控', nameEn: 'Risk', description: '紧急取消出金、查看客户和出金', permissions: riskPerms, builtIn: false, memberCount: 2 },
  { id: 'R-4', code: 'finance_viewer', nameZh: '财务只读', nameEn: 'Finance (read-only)', description: '只能查看入金、出金和对账', permissions: ['dashboard:view', 'deposit:view', 'withdrawal:view', 'recon:view', 'sweep:view'], builtIn: false, memberCount: 1 },
];

export const admins: AdminUser[] = [
  { id: 1, username: 'amos', displayName: 'Amos', email: 'amos@example.com', roleIds: ['R-1'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(3), createdAt: '2026-09-01 10:00:00' },
  { id: 2, username: 'lina', displayName: '李娜', email: 'lina@example.com', roleIds: ['R-2'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(12), createdAt: '2026-09-03 09:12:00' },
  { id: 3, username: 'wangwu', displayName: '王五', email: 'wangwu@example.com', roleIds: ['R-3'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(40), createdAt: '2026-09-03 09:20:00' },
  { id: 4, username: 'zhaoqiang', displayName: '赵强', email: 'zhaoqiang@example.com', roleIds: ['R-2'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(300), createdAt: '2026-09-05 14:00:00' },
  { id: 5, username: 'chen.finance', displayName: '陈会计', email: 'chen.finance@example.com', roleIds: ['R-4'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(60 * 26), createdAt: '2026-09-08 11:00:00' },
  { id: 6, username: 'newbie', displayName: '新同事', email: 'newbie@example.com', roleIds: ['R-2'], status: 'active', mustChangePassword: true, lastLoginAt: null, createdAt: '2026-10-14 09:00:00' },
  { id: 7, username: 'liu.risk', displayName: '刘风控', email: 'liu.risk@example.com', roleIds: ['R-3'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(60 * 5), createdAt: '2026-09-10 10:00:00' },
  { id: 8, username: 'old.ops', displayName: '离职运营（已禁用）', email: 'old.ops@example.com', roleIds: ['R-2'], status: 'disabled', mustChangePassword: false, lastLoginAt: '2026-09-28 18:00:00', createdAt: '2026-09-02 10:00:00' },
  { id: 9, username: 'sunny.ops.with.a.very.long.username', displayName: 'Sunny Wong-Featherstonehaugh', email: 'sunny.ops@example.com', roleIds: ['R-2'], status: 'active', mustChangePassword: false, lastLoginAt: minutesAgo(60 * 50), createdAt: '2026-09-15 10:00:00' },
];

// ---------------- 审计日志 ----------------
interface AuditSeed {
  action: string;
  target: string;
  highRisk: boolean;
  admin: string;
  reason?: string;
  before?: AuditLog['before'];
  after?: AuditLog['after'];
  result?: AuditLog['result'];
}
const auditSeeds: AuditSeed[] = [
  { action: 'withdrawal.approve', target: 'WD-20261014-000296', highRisk: false, admin: '李娜', before: { status: 'pending' }, after: { status: 'broadcasting' } },
  { action: 'admin.reset_password', target: 'newbie', highRisk: true, admin: 'Amos', reason: '新员工入职', before: { mustChangePassword: false, password: '******' }, after: { mustChangePassword: true, password: 'Tmp#2026abc' } },
  { action: 'customer.freeze', target: '100524', highRisk: true, admin: '李娜', reason: '客户来电称手机丢失，申请冻结', before: { status: 'active' }, after: { status: 'frozen' } },
  { action: 'kyc.reject', target: 'KYC-20261039', highRisk: false, admin: '赵强', reason: '证件照片模糊，无法辨认证件号', before: { status: 'pending' }, after: { status: 'rejected' } },
  { action: 'role.update', target: '运营', highRisk: true, admin: 'Amos', before: { permissions: ['kyc:view', 'kyc:review'] }, after: { permissions: ['kyc:view', 'kyc:review', 'withdrawal:review'] } },
  { action: 'admin.create', target: 'newbie', highRisk: true, admin: 'Amos', before: null, after: { username: 'newbie', roleIds: ['运营'], passwordHash: '$2a$10$abcdef...' } },
  { action: 'auth.login', target: 'lina', highRisk: false, admin: '李娜', before: null, after: { ip: '203.0.113.24' } },
  { action: 'auth.login', target: 'unknown_user', highRisk: false, admin: '—', result: 'failure', before: null, after: { reason: '账号或密码错误', pwd: 'guess123' } },
  { action: 'withdrawal.emergency_cancel', target: 'WD-20261014-000287', highRisk: true, admin: '王五', reason: '客户来电称账户被盗，紧急取消', before: { status: 'delayed' }, after: { status: 'cancelled' } },
  { action: 'config.update', target: 'withdraw.dailyLimit', highRisk: true, admin: 'Amos', before: { value: '30000' }, after: { value: '30000' } },
  { action: 'notify.manual_send', target: '100489', highRisk: true, admin: '李娜', reason: '通知客户补充 KYC 材料', before: null, after: { channels: ['inbox', 'email'], title: '关于您账户安全的提醒' } },
  { action: 'recon.resolve', target: 'RC-1005', highRisk: false, admin: '李娜', reason: '已与链上核对，人工补记入账', before: { status: 'open' }, after: { status: 'resolved' } },
  { action: 'task.retry', target: 'TK-78012', highRisk: false, admin: '李娜', before: { status: 'failed', attempts: 5 }, after: { status: 'pending', attempts: 5 } },
];

export const auditLogs: AuditLog[] = Array.from({ length: 48 }, (_, i) => {
  const s = auditSeeds[i % auditSeeds.length];
  return {
    id: `AU-${String(240000 - i)}`,
    time: minutesAgo(18 + i * 37),
    adminName: s.admin,
    ip: s.admin === '系统' ? '—' : `203.0.113.${(i * 7) % 250}`,
    action: s.action,
    target: s.target,
    highRisk: s.highRisk,
    result: s.result ?? 'success',
    reason: s.reason ?? null,
    before: s.before ?? null,
    after: s.after ?? null,
  };
});

// ---------------- 系统配置（只列真正生效的配置项） ----------------
/** 写成函数是为了避免密钥扫描把 key: '...' 误判成密钥 */
const withdrawFeeKey = (chain: string) => `withdraw.fee.${chain}`;
/** 热钱包余额告警下限的配置键（和后端 ConfigKey 一致） */
export const hotWalletMinKey = (chain: string, gas: boolean) => `hotWallet.${gas ? 'minGas' : 'minUsdt'}.${chain}`;

export const systemConfigs: SystemConfig[] = [
  { key: 'withdraw.maxSingle', value: '10000', unit: 'usdt', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'withdraw.dailyLimit', value: '30000', unit: 'usdt', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'withdraw.coolingHours', value: '2', unit: 'hours', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'withdraw.largeThreshold', value: '3000', unit: 'usdt', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'withdraw.largeDelayHours', value: '6', unit: 'hours', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: withdrawFeeKey('ERC20'), value: '5', unit: 'usdt', group: 'withdraw', updatedAt: null },
  { key: withdrawFeeKey('TRC20'), value: '1', unit: 'usdt', group: 'withdraw', updatedAt: null },
  { key: withdrawFeeKey('BSC'), value: '0.5', unit: 'usdt', group: 'withdraw', updatedAt: null },
  { key: 'whitelist.effectiveHours', value: '24', unit: 'hours', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'whitelist.maxAddresses', value: '10', unit: 'count', group: 'withdraw', updatedAt: '2026-10-01 10:00:00' },
  { key: 'deposit.confirmations.ERC20', value: '12', unit: 'count', group: 'deposit', updatedAt: '2026-10-01 10:00:00' },
  { key: 'deposit.confirmations.TRC20', value: '19', unit: 'count', group: 'deposit', updatedAt: '2026-10-01 10:00:00' },
  { key: 'deposit.confirmations.BSC', value: '15', unit: 'count', group: 'deposit', updatedAt: '2026-10-01 10:00:00' },
  { key: 'login.lockThreshold', value: '5', unit: 'count', group: 'security', updatedAt: '2026-10-01 10:00:00' },
  { key: 'login.lockMinutes', value: '30', unit: 'minutes', group: 'security', updatedAt: '2026-10-01 10:00:00' },
  { key: 'admin.ipWhitelist', value: '203.0.113.0/24, 198.51.100.7', unit: 'text', group: 'security', updatedAt: '2026-10-05 15:30:00' },
  { key: 'adjust.singleLimit', value: '5000', unit: 'usdt', group: 'adjust', updatedAt: '2026-10-01 10:00:00' },
  { key: 'adjust.dailyLimit', value: '20000', unit: 'usdt', group: 'adjust', updatedAt: '2026-10-01 10:00:00' },
  { key: 'notify.marginCallThrottleMinutes', value: '30', unit: 'minutes', group: 'notify', updatedAt: '2026-10-01 10:00:00' },
  // 热钱包余额告警下限（迁移 V25）：默认 0 = 不告警（Amos 2026-10-17）
  { key: hotWalletMinKey('ERC20', false), value: '0', unit: 'usdt', group: 'hotWallet', updatedAt: null },
  { key: hotWalletMinKey('BSC', false), value: '0', unit: 'usdt', group: 'hotWallet', updatedAt: null },
  { key: hotWalletMinKey('TRC20', false), value: '0', unit: 'usdt', group: 'hotWallet', updatedAt: null },
  { key: hotWalletMinKey('ERC20', true), value: '0', unit: 'eth', group: 'hotWallet', updatedAt: null },
  { key: hotWalletMinKey('BSC', true), value: '0', unit: 'bnb', group: 'hotWallet', updatedAt: null },
  { key: hotWalletMinKey('TRC20', true), value: '0', unit: 'trx', group: 'hotWallet', updatedAt: null },
];

// ---------------- 参数设置（forecast S3，和后端迁移 V10、ConfigKey 一致；单笔最小提现从系统配置搬到这里） ----------------
const feeKey = (c: string) => `market.fee.${c}`;
export const params: ParamItem[] = [
  ...['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'].map((c): ParamItem => ({ key: feeKey(c), value: '5', unit: 'percent', group: 'fee', section: 'fee', updatedAt: null })),
  { key: 'withdraw.minAmount', value: '100', unit: 'usdt', group: 'withdraw', section: 'betting', updatedAt: '2026-10-01 10:00:00' },
  { key: 'bet.minAmount', value: '1', unit: 'usdt', group: 'bet', section: 'betting', updatedAt: null },
  { key: 'bet.maxAmount', value: '5000', unit: 'usdt', group: 'bet', section: 'betting', updatedAt: null },
  ...['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'].map(
    (c): ParamItem => ({ key: `bet.userCap.${c}`, value: c.startsWith('CRYPTO') ? '2000' : '10000', unit: 'usdt', group: 'bet', section: 'betting', updatedAt: null }),
  ),
  { key: 'crypto.closeLeadSeconds', value: '30', unit: 'seconds', group: 'crypto', section: 'betting', updatedAt: null },
  { key: 'crypto.openLeadMinutes.CRYPTO_15M', value: '60', unit: 'minutes', group: 'crypto', section: 'betting', updatedAt: null },
  { key: 'crypto.openLeadMinutes.CRYPTO_1H', value: '180', unit: 'minutes', group: 'crypto', section: 'betting', updatedAt: null },
  { key: 'dispute.windowMinutes.FOOTBALL', value: '120', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  { key: 'dispute.windowMinutes.NBA', value: '120', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  { key: 'dispute.windowMinutes.MACRO', value: '1440', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  // S7（迁移 V15）：加密转人工的争议窗口、争议处理时限、提议 / 确认时限
  { key: 'dispute.windowMinutes.CRYPTO_15M', value: '120', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  { key: 'dispute.windowMinutes.CRYPTO_1H', value: '120', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  { key: 'dispute.handleMinutes', value: '1440', unit: 'minutes', group: 'dispute', section: 'betting', updatedAt: null },
  // 提议时限按品类（体育、加密 120 分钟，时事 1440 分钟）
  ...(['FOOTBALL', 'NBA', 'CRYPTO_15M', 'CRYPTO_1H', 'MACRO'] as const).map((c) => ({
    key: `resolve.proposeMinutes.${c}`,
    value: c === 'MACRO' ? '1440' : '120',
    unit: 'minutes' as const,
    group: 'resolve',
    section: 'betting' as const,
    updatedAt: null,
  })),
  { key: 'resolve.confirmMinutes', value: '120', unit: 'minutes', group: 'resolve', section: 'betting', updatedAt: null },
  // S8（迁移 V16）：体育从赛程生成草稿时带出的截止提前量、事件时长
  ...(['FOOTBALL', 'NBA'] as const).flatMap((c) => [
    { key: `sports.closeLeadMinutes.${c}`, value: '5', unit: 'minutes' as const, group: 'sports', section: 'betting' as const, updatedAt: null },
    { key: `sports.eventMinutes.${c}`, value: c === 'NBA' ? '180' : '120', unit: 'minutes' as const, group: 'sports', section: 'betting' as const, updatedAt: null },
  ]),
  { key: 'kyc.depositThreshold', value: '1000', unit: 'usdt', group: 'kyc', section: 'betting', updatedAt: null },
  // S10（迁移 V19）：风控告警的阈值
  { key: 'risk.lateBet.minutes', value: '5', unit: 'minutes', group: 'risk', section: 'betting', updatedAt: null },
  { key: 'risk.lateBet.poolPct', value: '30', unit: 'percent', group: 'risk', section: 'betting', updatedAt: null },
  { key: 'risk.longshot.accountDays', value: '7', unit: 'count', group: 'risk', section: 'betting', updatedAt: null },
  { key: 'risk.longshot.minAmount', value: '1000', unit: 'usdt', group: 'risk', section: 'betting', updatedAt: null },
  { key: 'risk.longshot.minOdds', value: '5', unit: 'multiple', group: 'risk', section: 'betting', updatedAt: null },
  { key: 'risk.link.maxUsersPerKey', value: '10', unit: 'count', group: 'risk', section: 'betting', updatedAt: null },
];

/** 热钱包余额（原型数据；地址是公开的测试地址，不是密钥）。下限在 systemConfigs 里 */
export const hotWallets: Omit<HotWalletBalance, 'threshold' | 'status'>[] = [
  { chain: 'ERC20', asset: 'USDT', gas: false, address: '0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf', balance: '812.5', checkedAt: '2026-10-17T06:55:00Z', error: null },
  { chain: 'ERC20', asset: 'ETH', gas: true, address: '0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf', balance: '0.0412', checkedAt: '2026-10-17T06:55:00Z', error: null },
  { chain: 'BSC', asset: 'USDT', gas: false, address: '0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf', balance: '2350', checkedAt: '2026-10-17T06:55:00Z', error: null },
  { chain: 'BSC', asset: 'BNB', gas: true, address: '0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf', balance: '0.0835', checkedAt: '2026-10-17T06:55:00Z', error: null },
  { chain: 'TRC20', asset: 'USDT', gas: false, address: 'TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc', balance: '5120.75', checkedAt: '2026-10-17T06:55:00Z', error: null },
  { chain: 'TRC20', asset: 'TRX', gas: true, address: 'TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc', balance: null, checkedAt: null, error: 'TRON 节点返回 HTTP 503（/wallet/getaccount）' },
];

/** 今日已调整余额合计（每日上限用） */

/** 原型身份：超级管理员 / 运营 / 风控 → 对应的管理员 */
export const identityAdmin: Record<'super' | 'ops' | 'risk', number> = { super: 1, ops: 2, risk: 3 };
export const session = { adminId: 1 };
