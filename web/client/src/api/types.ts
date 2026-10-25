/**
 * 客户端接口的数据类型。按将来真实后端接口设计：
 * - 金额、价格、数量一律用十进制字符串（后端 BigDecimal 序列化），前端不做浮点累加；
 * - 时间一律用 ISO 8601 字符串（UTC）。
 */

/** 界面语言（RQ-18）：简体中文、繁體中文、英文 */
export type Lang = 'zh-CN' | 'zh-TW' | 'en';

// ---------- 账户与认证 ----------
export interface Session {
  /** 只有原型（mock 模式）有；真实环境的令牌在 HttpOnly Cookie 里，前端拿不到 */
  token?: string;
  userId: string;
  email: string;
}

export type EmailCodePurpose = 'register' | 'resetPassword';

export interface RegisterRequest {
  email: string;
  code: string;
  password: string;
  firstName: string;
  middleName: string;
  lastName: string;
  birthDate: string; // YYYY-MM-DD
  nationality: string; // ISO 3166-1 alpha-2
}

export interface Profile {
  userId: string;
  email: string;
  firstName: string;
  middleName: string;
  lastName: string;
  birthDate: string;
  nationality: string;
  registeredAt: string;
}

export type ProfileUpdate = Pick<Profile, 'firstName' | 'middleName' | 'lastName' | 'birthDate' | 'nationality'>;

export type KycStatus = 'none' | 'pending' | 'approved' | 'rejected';
export type KycDocType = 'idCard' | 'passport';

export interface KycInfo {
  status: KycStatus;
  docType?: KycDocType;
  docNumber?: string;
  submittedAt?: string;
  reviewedAt?: string;
  /** 被驳回时的原因（后台审核员填写，中英文由后端按语言返回） */
  rejectReason?: string;
}

export interface KycSubmit {
  docType: KycDocType;
  docNumber: string;
  front: File;
  back: File;
  selfie: File;
}

export interface NotifyPrefs {
  push: boolean;
  email: boolean;
}

// ---------- 账户 ----------
/**
 * 账户（GET /api/ledger/account）。forecast S2：三项余额（技术方案/v1-架构方案.md 第 3.2 节）——可用、下注中（下注锁定）、提现冻结；
 * balance = available + betLocked（不含提现冻结）。
 */
export interface Account {
  currency: 'USDT';
  /** 余额 = 可用 + 下注中（不含提现冻结） */
  balance: string;
  /** 可用（可以提现、S4 起可以下注的金额） */
  available: string;
  /** 下注中：已下注、还没结算或退回的本金 */
  betLocked: string;
  /** 提现冻结 */
  withdrawFrozen: string;
}

// ---------- 资金 ----------
export type Network = 'ERC20' | 'TRC20' | 'BSC';

export interface DepositAddress {
  network: Network;
  address: string;
  confirmations: number;
  etaMinutes: number;
  /** 最小充值金额（低于它不入账，AC-41） */
  minDeposit: string;
}

/** 充值记录状态：待确认 / 已到账 / 已作废（链上回滚）/ 低于最小充值金额 / 已冲正 */
export type DepositStatus = 'pending' | 'credited' | 'dropped' | 'belowMin' | 'reversed';

export interface DepositRecord {
  id: string;
  network: Network;
  txHash: string;
  amount: string;
  status: DepositStatus;
  /** 当前确认数（待确认时显示进度） */
  confirmations: number;
  requiredConfirmations: number;
  time: string;
}

/** 流水类型（GET /api/ledger/entries）。S4 加下注；派彩、抽成、退款、补偿在 S5、S7 加 */
export type LedgerType = 'deposit' | 'withdraw' | 'withdrawRefund' | 'bet';

/** 流水相关的市场（下注等，AC-39：每条能点到对应市场）：对外编号和三种语言的题目 */
export interface LedgerMarket {
  publicNo: string;
  title: { en: string; zhCN: string; zhTW: string };
}

export interface LedgerItem {
  id: string;
  type: LedgerType;
  amount: string;
  /** 附加说明（网络）；有市场时为空 */
  ref: string;
  time: string;
  /** 相关的市场（下注）；充值、提现没有 */
  market?: LedgerMarket | null;
}

export interface LedgerQuery {
  type: LedgerType | 'all';
  range: '7d' | '30d' | '90d' | 'all';
  page: number;
  pageSize: number;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface WhitelistAddress {
  id: string;
  network: Network;
  address: string;
  label: string;
  createdAt: string;
  /** 新地址 24 小时后生效 */
  activeAt: string;
}

export type WithdrawStatus =
  | 'cooling'
  | 'reviewing'
  | 'approved'
  | 'delayed'
  | 'broadcasting'
  | 'completed'
  | 'failedRefunded'
  | 'rejected'
  | 'cancelled';

export interface Withdrawal {
  id: string;
  amount: string;
  fee: string;
  network: Network;
  address: string;
  status: WithdrawStatus;
  createdAt: string;
  /** 冷静期结束时间（冷静期内可撤销） */
  coolingEndsAt: string;
  /** 大额延迟上链的结束时间 */
  delayEndsAt?: string;
  txHash?: string;
  rejectReason?: string;
}

export interface WithdrawInfo {
  kycStatus: KycStatus;
  available: string;
  usedToday: string;
  minAmount: string;
  maxPerTx: string;
  dailyLimit: string;
  /** ≥ 该金额审核后延迟上链 */
  largeAmount: string;
  largeDelayHours: number;
  coolingHours: number;
  fees: Record<Network, string>;
  maxWhitelist: number;
}

export interface WithdrawRequest {
  whitelistId: string;
  amount: string;
}

// ---------- 通知 ----------
export type NotificationKind = 'deposit' | 'withdraw' | 'kyc' | 'system';

export interface NotificationItem {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}

/** 服务端推送事件（WebSocket /api/ws/user，只推给本人） */
export type ServerEvent =
  | { type: 'account' }
  | { type: 'notification'; item: NotificationItem }
  | { type: 'withdraw'; status: WithdrawStatus; amount: string }
  /** 充值记录变了（发现待确认、确认数变化、到账、作废、冲正），客户端重新拉取充值记录（E2E-2） */
  | { type: 'deposit'; status: DepositStatus; network: Network; amount: string; confirmations?: number };

// ---------- 市场和彩池（forecast S3、S4 的接口契约；M1 原型按它接模拟层） ----------
/**
 * 字段和后端 `market/core/ClientViews.java`、`pool/bet/PoolViews.java` 一致（S3、S4 已定，`PoolClientContractTest` 核对字段名）：
 * 编号是字符串（雪花 ID）；金额是 2 位小数的字符串；费率是百分比字符串（"5" = 5%）；时间是 ISO-8601 UTC。
 * 标了"M1 提议"的字段后端还没有（结算、裁决、争议在 S5、S7 做），原型先按这个形状模拟，正式开发时由后端照着加或者再商量。
 */

/** 三种语言的文字（后端 I18nText）；客户端按界面语言选，取不到用英文 */
export interface I18nText {
  en: string;
  zhCN: string;
  zhTW: string;
}

/** 品类（后端 MarketCategory，大写） */
export type MarketCategory = 'FOOTBALL' | 'NBA' | 'CRYPTO_15M' | 'CRYPTO_1H' | 'MACRO';

/** 客户能看到的状态（草稿不会出现） */
export type MarketStatus = 'listed' | 'paused' | 'closed' | 'pending_resolution' | 'publicizing' | 'disputed' | 'settled' | 'cancelled' | 'voided';

/** 一个选项的奖池（PoolViews.OptionPool）：pct 详情用（1 位小数），pctList 列表用（整数），mult 不带 x；都是按需求 3.4 舍入好的显示字符串 */
export interface OptionPool {
  optionNo: string;
  seq: number;
  amount: string;
  bettors: number;
  pct: string;
  pctList: string;
  mult: string;
}

/** 本人在一个选项上的持仓（PoolViews.Mine）：ifWin = 这个选项赢了按现在的奖池能拿回多少 */
export interface MinePosition {
  optionNo: string;
  seq: number;
  amount: string;
  betCount: number;
  ifWin: string;
}

/** 下注限额（PoolViews.Limits） */
export interface BetLimits {
  min: string;
  max: string;
  userCap: string;
  used: string;
  remaining: string;
}

/** 市场的奖池（PoolViews.Pool）：列表里 mine、limits 为空 */
export interface MarketPool {
  total: string;
  bettors: number;
  /** 截止时已定格：显示"最终赔率" */
  frozen: boolean;
  feeRate: string;
  options: OptionPool[];
  mine?: MinePosition[];
  limits?: BetLimits;
}

export interface MarketOptionCard {
  optionNo: string;
  seq: number;
  label: I18nText;
}

/** 列表里的一个市场（ClientViews.Card） */
export interface MarketCard {
  publicNo: string;
  category: MarketCategory;
  title: I18nText;
  status: MarketStatus;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  eventEndAt: string;
  feeRate: string;
  options: MarketOptionCard[];
  pool: MarketPool;
  /** M1 提议：联赛 / 币种等副标题（后端在 S8 赛程生成时可以从 sourceRef 带出） */
  league?: I18nText;
  /** M1 提议：已结算时赢的选项（列表里显示结果） */
  winningOptionNo?: string;
}

export interface MarketOptionDetail extends MarketOptionCard {
  winCondition: I18nText;
}

export interface MarketClarification {
  text: I18nText;
  time: string;
}

export interface MarketFeeChange {
  oldRate: string;
  newRate: string;
  time: string;
}

/**
 * M1 提议：裁决与公示（S7）。自动裁决的 resolver 是 "auto"；平台裁决的是确认结果的管理员编号（不是姓名，需求 4.4）。
 * 加密市场带开始价、结束价和来源；体育带比分。
 */
export interface MarketResolution {
  winningOptionNo: string;
  mode: 'auto' | 'platform';
  resolver: string;
  resolvedAt: string;
  basis: I18nText;
  sources: Array<{ name: string; url: string; value?: string }>;
  /** 公示中：争议截止时间 */
  disputeUntil?: string;
  /** 本人能不能提交争议（有下注、在窗口内、没提交过） */
  canDispute?: boolean;
  /** 已结算：结算时用的费率、派彩合计、抽成合计、尾差 */
  settle?: { feeRate: string; payoutTotal: string; feeTotal: string; dust: string };
  /** 本人在这个市场的结算（M1 提议，S5）：派彩、抽成 */
  minePayout?: { payout: string; fee: string };
}

/** 市场详情（ClientViews.Detail） */
export interface MarketDetail extends Omit<MarketCard, 'options'> {
  rules: I18nText;
  resolutionMode: 'AUTO' | 'PLATFORM';
  sourcePrimary: string;
  sourceBackup: string;
  userCap: string;
  disputeWindowMinutes?: number;
  cancelReason?: string;
  voidReason?: string;
  options: MarketOptionDetail[];
  clarifications: MarketClarification[];
  feeChanges: MarketFeeChange[];
  resolution?: MarketResolution;
}

export interface MarketQuery {
  category?: MarketCategory;
  /** active（默认，可下注和暂停）/ closed（等结果）/ ended（已结束）；不传为全部 */
  status?: 'active' | 'closed' | 'ended' | 'all';
  sort?: 'closeAt' | 'newest' | 'pool';
  keyword?: string;
}

/** 下注请求（POST /api/markets/{publicNo}/bets）：clientReqId 同一客户唯一，重复提交返回第一次的结果 */
export interface BetRequest {
  optionNo: string;
  amount: string;
  clientReqId: string;
}

/** 下注结果（PoolViews.BetResult） */
export interface BetResult {
  betNo: string;
  marketNo: string;
  optionNo: string;
  amount: string;
  receivedAt: string;
  status: string;
  duplicate: boolean;
  available: string;
  betLocked: string;
  pool: MarketPool;
}

export type MyBetTab = 'active' | 'pending' | 'settled' | 'refunded';

/** 我的下注的一条（PoolViews.MyBet）；final*、payout、fee、refund 是 M1 提议（S5 结算后才有） */
export interface MyBet {
  betNo: string;
  marketNo: string;
  market: I18nText;
  marketStatus: MarketStatus;
  optionNo: string;
  seq: number;
  option: I18nText;
  amount: string;
  receivedAt: string;
  tab: MyBetTab;
  status: 'active' | 'refunded';
  refundReason?: 'CANCELLED' | 'VOIDED' | 'LATE_BET' | 'STAFF';
  multAtBet: string;
  feeRateAtBet: string;
  category?: MarketCategory;
  finalMult?: string;
  won?: boolean;
  payout?: string;
  fee?: string;
}

export interface MyBets {
  items: MyBet[];
  total: number;
  page: number;
  pageSize: number;
  summary: Record<MyBetTab, { count: number; amount: string }>;
  /** M1 提议：顶部汇总（总下注、总派彩、净盈亏） */
  totals?: { staked: string; payout: string; net: string };
}

/** 提交争议（M1 提议，S7） */
export interface DisputeRequest {
  optionNo: string;
  reason: string;
  link?: string;
}
