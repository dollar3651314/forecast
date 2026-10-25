/**
 * 模拟数据库：内存中的全部可变状态和初始数据（规则 C50：数据要像真的）。
 * 包含超过一页的列表、大额和多位小数、超长名称、中文名、带 <b> 符号的名称。
 */
import type {
  KycInfo,
  LedgerItem,
  LedgerType,
  Network,
  NotificationKind,
  NotifyPrefs,
  Profile,
  WhitelistAddress,
  Withdrawal,
  WithdrawStatus,
} from '../api/types';
import { hoursFromNow, minutesAgo, seeded, snowflakeAt } from './ids';

/** 种子数据的对外编号：按「多少分钟前」生成雪花 ID 格式的字符串（和后端一样超过 JavaScript 安全整数，只当字符串） */
const seedNo = (minutes: number, seq: number): string => snowflakeAt(Date.now() - minutes * 60_000, seq);

export interface StoredNotification {
  id: string;
  kind: NotificationKind;
  /** 三种语言（S11：和后端模板一样，繁体空时用简体） */
  title: { zh: string; en: string; tw?: string };
  body: { zh: string; en: string; tw?: string };
  read: boolean;
  createdAt: string;
  /** 点进去的页面（S11，和后端 NotifyService.link 一样） */
  link?: string;
}

export interface Db {
  profile: Profile;
  password: string;
  kyc: KycInfo & { rejectReasonI18n?: { zh: string; en: string } };
  prefs: NotifyPrefs;
  /** 账户三项（S2）：可用、下注中、提现冻结；余额 = 可用 + 下注中（mocks/account.ts 按它返回） */
  account: { available: string; betLocked: string; withdrawFrozen: string };
  ledger: LedgerItem[];
  whitelist: WhitelistAddress[];
  withdrawals: Withdrawal[];
  notifications: StoredNotification[];
  deposit: Record<Network, string>;
}

/** M1：最近的下注流水（和 mocks/markets.ts 里本人的下注对应；题目三种语言） */
function seedBets(): LedgerItem[] {
  const bet = (i: number, amount: string, minutes: number, zhCN: string, en: string, zhTW: string): LedgerItem => ({
    id: `LB${i}`,
    type: 'bet',
    amount: `-${amount}`,
    ref: '',
    time: minutesAgo(minutes),
    market: { publicNo: seedNo(minutes, 300 + i), title: { zhCN, en, zhTW } },
  });
  return [
    bet(1, '40.00', 60 * 5, '曼城 vs 利物浦（90 分钟胜平负）', 'Man City vs Liverpool (90-min result)', '曼城 vs 利物浦（90 分鐘勝平負）'),
    bet(2, '50.00', 60 * 9, '湖人 vs 凯尔特人：总分大于 224.5？', 'Lakers vs Celtics: total points over 224.5?', '湖人 vs 凱爾特人：總分大於 224.5？'),
    bet(3, '200.00', 60 * 26, '勇士 vs 掘金（胜负，含加时）', 'Warriors vs Nuggets (winner, incl. OT)', '勇士 vs 掘金（勝負，含加時）'),
    bet(4, '60.00', 60 * 30, '曼城 vs 利物浦（90 分钟胜平负）', 'Man City vs Liverpool (90-min result)', '曼城 vs 利物浦（90 分鐘勝平負）'),
    bet(5, '80.00', 60 * 30 + 5, '布伦特福德 vs 富勒姆（90 分钟胜平负）', 'Brentford vs Fulham (90-min result)', '布倫特福德 vs 富勒姆（90 分鐘勝平負）'),
    bet(6, '120.00', 60 * 50, '美联储 12 月议息会议宣布降息吗？', 'Will the Fed cut rates at its December meeting?', '美聯儲 12 月議息會議宣佈降息嗎？'),
  ];
}

function seedLedger(): LedgerItem[] {
  const r = seeded(7);
  const plan: Array<[LedgerType, string]> = [
    ['deposit', 'TRC20'],
    ['withdraw', 'TRC20'],
    ['deposit', 'ERC20'],
    ['deposit', 'BSC'],
    ['withdraw', 'ERC20'],
    ['withdrawRefund', 'BSC'],
  ];
  const out: LedgerItem[] = [];
  for (let i = 0; i < 46; i++) {
    const [type, ref] = plan[i % plan.length];
    let amount: string;
    switch (type) {
      case 'deposit':
        amount = i === 0 ? '1000.00' : (500 + Math.round(r() * 49500 * 100) / 100).toFixed(2);
        break;
      case 'withdraw':
        amount = `-${(100 + Math.round(r() * 4900)).toFixed(2)}`;
        break;
      default:
        amount = (200 + Math.round(r() * 800)).toFixed(2);
    }
    out.push({ id: `L${90000 + i}`, type, amount, ref, time: minutesAgo(40 + i * 2711) });
  }
  return out;
}

type T3 = [zh: string, en: string, tw: string];
const N = (kind: NotificationKind, title: T3, body: T3, minutes: number, read: boolean, i: number, link?: string): StoredNotification => ({
  id: `N${5000 + i}`,
  kind,
  title: { zh: title[0], en: title[1], tw: title[2] },
  body: { zh: body[0], en: body[1], tw: body[2] },
  read,
  createdAt: minutesAgo(minutes),
  link,
});

/** 站内信：文案和后端模板（迁移 V17，S9 PM 定稿）一致，三种语言；市场相关的点进"我的下注"（原型里市场编号每次生成，不写死） */
function seedNotifications(): StoredNotification[] {
  const base: StoredNotification[] = [
    N(
      'system',
      ['「美国 9 月 CPI 同比 ≥ 3.0%？」已结算：你押中了', '"US Sep CPI YoY ≥ 3.0%?" settled: your option won', '「美國 9 月 CPI 年增 ≥ 3.0%？」已結算：你押中了'],
      [
        '「美国 9 月 CPI 同比 ≥ 3.0%？」结果是「否」。你的派彩 323.40 USDT 已到可用余额（本金 200.00 + 盈利 129.89 − 抽成 6.49，抽成为盈利的 5%）。',
        '"US Sep CPI YoY ≥ 3.0%?" resolved to "No". Your payout of 323.40 USDT is in your available balance (stake 200.00 + profit 129.89 − fee 6.49; the fee is 5% of the profit).',
        '「美國 9 月 CPI 年增 ≥ 3.0%？」結果是「否」。你的派彩 323.40 USDT 已到可用餘額（本金 200.00 + 盈利 129.89 − 抽成 6.49，抽成為盈利的 5%）。',
      ],
      12,
      false,
      1,
      '/bets?tab=settled',
    ),
    N(
      'deposit',
      ['充值已到账', 'Deposit credited', '充值已到帳'],
      ['你通过 TRC20 充值的 1,000.00 USDT 已到账，已计入可用余额。', 'Your deposit of 1,000.00 USDT via TRC20 has been credited to your available balance.', '你透過 TRC20 充值的 1,000.00 USDT 已到帳，已計入可用餘額。'],
      18,
      false,
      0,
      '/assets',
    ),
    N(
      'withdraw',
      ['提现状态更新', 'Withdrawal update', '提現狀態更新'],
      ['你的提现（1,000.00 USDT）已提交，冷静期内可以撤销。', 'Your withdrawal (1,000.00 USDT) was submitted and can be cancelled during the cooling-off period.', '你的提現（1,000.00 USDT）已提交，冷靜期內可以撤銷。'],
      25,
      false,
      3,
      '/assets/withdraw',
    ),
    N(
      'system',
      ['下注成功', 'Bet placed', '下注成功'],
      [
        '你已在「曼城 vs 利物浦（90 分钟胜平负）」押「主胜 曼城」40.00 USDT。下注后不能撤销；赔率随奖池变化，最终赔率在截止时确定。',
        'You bet 40.00 USDT on "Man City" in "Man City vs Liverpool (90-min result)". Bets cannot be cancelled. Odds change with the pool; final odds are set at the close.',
        '你已在「曼城 vs 利物浦（90 分鐘勝平負）」押「主勝 曼城」40.00 USDT。下注後不能撤銷；賠率隨獎池變化，最終賠率在截止時確定。',
      ],
      300,
      true,
      4,
      '/bets',
    ),
    N(
      'kyc',
      ['身份认证已通过', 'Identity verified', '身分認證已通過'],
      [
        '你的身份认证已通过，现在可以提现，下注也不再受累计充值的认证门槛限制。',
        'Your identity has been verified. You can now withdraw, and the verification threshold on total deposits no longer limits your bets.',
        '你的身分認證已通過，現在可以提現，下注也不再受累計充值的認證門檻限制。',
      ],
      2880,
      true,
      5,
      '/me/kyc',
    ),
  ];
  const out = [...base];
  for (let i = 0; i < 18; i++) {
    const n = i % 2 ? (100 * (i + 1)).toLocaleString('en-US') : (250 * (i + 1)).toLocaleString('en-US');
    out.push(
      i % 2
        ? N('withdraw', ['提现状态更新', 'Withdrawal update', '提現狀態更新'], [`你的提现（${n}.00 USDT）已完成。`, `Your withdrawal (${n}.00 USDT) has completed.`, `你的提現（${n}.00 USDT）已完成。`], 6000 + i * 1440, true, 10 + i, '/assets/withdraw')
        : N('deposit', ['充值已到账', 'Deposit credited', '充值已到帳'], [`你通过 ERC20 充值的 ${n}.00 USDT 已到账，已计入可用余额。`, `Your deposit of ${n}.00 USDT via ERC20 has been credited to your available balance.`, `你透過 ERC20 充值的 ${n}.00 USDT 已到帳，已計入可用餘額。`], 6000 + i * 1440, true, 10 + i, '/assets'),
    );
  }
  return out;
}

function seedWithdrawals(): Withdrawal[] {
  const W = (i: number, amount: string, status: WithdrawStatus, network: Network, address: string, minutes: number, extra: Partial<Withdrawal> = {}): Withdrawal => {
    const createdAt = minutesAgo(minutes);
    return {
      id: seedNo(minutes, 200 + i),
      amount,
      fee: network === 'ERC20' ? '5.00' : network === 'TRC20' ? '1.00' : '0.50',
      network,
      address,
      status,
      createdAt,
      coolingEndsAt: new Date(new Date(createdAt).getTime() + 2 * 3_600_000).toISOString(),
      ...extra,
    };
  };
  const T = 'TQ8nB4rW7xYp2LkM9vD3sF6hJ1cZ5gw3kP';
  const E = '0x4A7c9E2b1D8f3C6a5B0e9F7d2C1b8A3e6D5f4C21';
  return [
    W(0, '1000.00', 'cooling', 'TRC20', T, 15),
    W(1, '2500.00', 'reviewing', 'TRC20', T, 180),
    W(2, '800.00', 'approved', 'BSC', E, 300),
    W(3, '5000.00', 'delayed', 'TRC20', T, 420, { delayEndsAt: hoursFromNow(3.5) }),
    W(4, '1200.50', 'broadcasting', 'ERC20', E, 720, { txHash: '0x9b1e4c7a2f3d8e6b5a0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a' }),
    W(5, '200.00', 'completed', 'BSC', E, 4300, { txHash: '0x7f3a9c2e5b8d1a4f6c3e9b2d5a8f1c4e7b0d3a6f9c2e5b8d1a4f7c0e3b6de21b' }),
    W(6, '3000.00', 'failedRefunded', 'ERC20', E, 7200),
    W(7, '9999.99', 'rejected', 'TRC20', T, 10000, { rejectReason: 'addressRisk' }),
    W(8, '150.00', 'cancelled', 'TRC20', T, 14000),
    W(9, '10000.00', 'completed', 'TRC20', T, 20000, { txHash: 'a3f9c2e15b8d7a4f6c3e9b2d5a8f1c4e7b0d3a6f9c2e5b8d1a4f7c0e3b6d9a2f' }),
    W(10, '12.34', 'completed', 'BSC', E, 30000, { txHash: '0x1c4e7b0d3a6f9c2e5b8d1a4f7c0e3b6d9a2f5c8e1b4d7a0f3c6e9b2d5a8f1c4e' }),
  ];
}

export function seed(): Db {
  return {
    profile: {
      userId: 'U10086',
      email: 'ouyang.siyuan.prediction.account@example-mail.com',
      firstName: '思远',
      middleName: '',
      lastName: '欧阳',
      birthDate: '1990-06-18',
      nationality: 'CN',
      registeredAt: minutesAgo(60 * 24 * 41),
    },
    password: 'Forecast2026',
    kyc: { status: 'approved', docType: 'idCard', docNumber: '110101199006181234', submittedAt: minutesAgo(3000), reviewedAt: minutesAgo(2880) },
    prefs: { push: true, email: true },
    // M1：下注中 = 本人在进行中和等结果的市场里的下注合计（mocks/markets.ts：100 + 50 + 120 + 200 + 80）
    account: { available: '1234.56', betLocked: '550.00', withdrawFrozen: '1000.00' },
    ledger: [...seedBets(), ...seedLedger()],
    whitelist: [
      { id: 'A1', network: 'TRC20', address: 'TQ8nB4rW7xYp2LkM9vD3sF6hJ1cZ5gw3kP', label: '我的 imToken 钱包', createdAt: minutesAgo(60 * 24 * 30), activeAt: minutesAgo(60 * 24 * 29) },
      { id: 'A2', network: 'ERC20', address: '0x4A7c9E2b1D8f3C6a5B0e9F7d2C1b8A3e6D5f4C21', label: '冷钱包 <b>主</b> & "Ledger"', createdAt: minutesAgo(60 * 24 * 12), activeAt: minutesAgo(60 * 24 * 11) },
      { id: 'A3', network: 'BSC', address: '0x9E2b1D8f3C6a5B0e9F7d2C1b8A3e6D5f4C214A7c', label: '币安交易所充值地址（新添加，等待生效）', createdAt: minutesAgo(120), activeAt: hoursFromNow(22) },
    ],
    withdrawals: seedWithdrawals(),
    notifications: seedNotifications(),
    deposit: {
      ERC20: '0x8f3C2a9B4e7D1c6F5a0B9e8D7c2F1a4B3e6D9c0A',
      BSC: '0x8f3C2a9B4e7D1c6F5a0B9e8D7c2F1a4B3e6D9c0A',
      TRC20: 'TXr7mKp3Vd9qLw2HcN8eYb4FjGs6AuZt5R',
    },
  };
}

export let db: Db = seed();

/** 原型：重置全部模拟数据 */
export function resetDb(): void {
  db = seed();
}
