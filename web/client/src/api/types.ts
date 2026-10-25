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
  /** 勾选"已年满 18 周岁并同意服务条款"（S11，需求 P8；后端没勾拒绝 ADULT_REQUIRED） */
  adultConfirmed: boolean;
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
/** 流水类型（S5 加派彩 payout、抽成 fee、下注退回 refund；S7 加补偿 compensation，AC-39） */
export type LedgerType = 'deposit' | 'withdraw' | 'withdrawRefund' | 'bet' | 'payout' | 'fee' | 'refund' | 'compensation';

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
  /** 点进去的页面（S11，需求 P6）：市场详情 /markets/…、提现 /assets/withdraw、资产 /assets、认证 /me/kyc；没有时为空 */
  link?: string | null;
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
 * 结果、裁决、争议、结算的字段**以后端为准**（S5 `winningOptionNo` / `settling`、S6 / S7 的 `resolution`、S11 的 `settle`、`reminder`、
 * 我的下注的 `result` / `totals`；项目地图约定 48）。只剩 `league` 是原型专用（后端没有，真实接口时显示品类）。
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
  /** M1 提议：联赛 / 币种等副标题（后端没有，只在原型里有；真实接口时显示品类） */
  league?: I18nText;
  /** 已结算市场的结果（详情 S5、列表 S11，后端 `ClientViews`）；其他状态为空 */
  winningOptionNo?: string | null;
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

/** 自动裁决参与判定的一个来源（S6，`resolve/auto/ResolutionViews`） */
export interface AutoResolutionSource {
  /** BINANCE / OKX / COINBASE */
  source: string;
  /** 例如 "Binance BTC/USDT" */
  label: string;
  startPrice: string;
  endPrice: string;
  direction: 'up' | 'down';
  /** 原始返回的 sha256（存档可核对） */
  sha256: string;
}

/** 自动裁决（S6，加密）：已结算时有；价格是原样的字符串（不舍入） */
export interface AutoResolution {
  mode: 'auto';
  result: 'up' | 'down';
  /** 固定 "AUTO" */
  resolvedBy: string;
  resolvedAt: string;
  symbol: string;
  windowStart: string;
  windowEnd: string;
  /** 按首选来源（币安），首选没参与判定时按实际用的来源 */
  startPrice: string | null;
  endPrice: string | null;
  source: string | null;
  sources: AutoResolutionSource[];
}

/** 争议结论（S7，公开；不显示争议人） */
export interface DisputeDecision {
  decision: 'upheld' | 'overturned' | 'voided';
  conclusion: string | null;
  decidedAt: string;
}

/** 本人提交过的争议（S7） */
export interface MyDispute {
  disputeNo: string;
  /** 认为应该是哪个选项；claimVoid = true 时为空（认为应作废） */
  claimedOptionNo: string | null;
  claimVoid: boolean;
  /** open / upheld / overturned / voided / closed */
  status: string;
  receivedAt: string;
}

/**
 * 平台裁决（S7，`resolve/platform/PlatformViews.clientView`）：公示中、争议中、已结算、已作废，有确认或作废记录时有。
 * 加密自动裁决转人工、由人确认的也是这个结构。resolvedBy 是员工编号（数字字符串）或 "SYSTEM"，不公开姓名（D-83）；截图不公开（D-84）。
 */
export interface PlatformResolution {
  mode: 'platform';
  voided: boolean;
  /** 最终结果（改判后是改判的选项）；作废时为空 */
  resultOptionNo: string | null;
  /** 公示的（确认时的）结果 */
  publicizedOptionNo: string | null;
  /** 依据代码：NORMAL / E6_COMPLETED / E7_OFFICIAL / E11_USUAL，作废时 E10_UNDETERMINED 等 */
  basis: string | null;
  evidenceUrls: string[];
  /** 裁决理由（裁决员写的原文，一种语言） */
  explanation: string | null;
  actualStartAt: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  disputeDeadlineAt: string | null;
  disputeCount: number;
  dispute: DisputeDecision | null;
  myDispute: MyDispute | null;
  /** 本人能不能提交争议（有有效下注、在窗口内、没提交过） */
  canDispute: boolean;
}

export type MarketResolution = AutoResolution | PlatformResolution;

/**
 * 详情的结算信息（S11，后端 `settle/view/ClientSettleViews.ClientSettle`）：已结算时是结算单的公开数字（ΣPay + ΣF + D = T），
 * 已取消、已作废时是退款合计；mine 是本人的结果（没下注为空）。
 */
export interface MarketSettle {
  kind: 'payout' | 'refund';
  /** done / paying（退款单分批进行中） */
  status: string;
  /** 结算用的费率（百分比字符串）；退款为空 */
  feeRate: string | null;
  poolTotal: string;
  /** 派彩合计（已扣抽成）；退款时是退款合计 */
  payoutTotal: string;
  feeTotal: string;
  dust: string;
  winnerPositions: number;
  loserPositions: number;
  doneAt: string | null;
  mine: { stake: string; payout: string; fee: string; refund: string; compensation: string } | null;
}

/** 本人的 #23 开赛提醒状态（S11，后端 `market/remind/ReminderService.State`） */
export interface ReminderState {
  /** 现在能订阅（已上架或已暂停、还没到开放时间） */
  available: boolean;
  /** 本人能订阅（用邮箱和密码登录过，SQ-20） */
  eligible: boolean;
  status: 'none' | 'active' | 'sent' | 'cancelled' | 'missed';
}

/** "通知偏好"里的一条开赛提醒订阅（ReminderService.Item） */
export interface ReminderItem {
  marketNo: string;
  title: I18nText;
  marketStatus: MarketStatus;
  openAt: string;
  closeAt: string;
  status: ReminderState['status'];
  sentAt: string | null;
}

export interface ReminderListing {
  eligible: boolean;
  items: ReminderItem[];
}

/** 邮件里的退订链接的结果（ReminderService.Unsubscribed） */
export interface ReminderUnsubscribed {
  marketNo: string;
  title: I18nText;
  status: ReminderState['status'];
}

/** 市场详情（ClientViews.Detail） */
export interface MarketDetail extends Omit<MarketCard, 'options'> {
  rules: I18nText;
  resolutionMode: 'AUTO' | 'PLATFORM';
  sourcePrimary: string;
  sourceBackup: string;
  userCap: string;
  disputeWindowMinutes?: number | null;
  coverPath?: string | null;
  cancelReason?: string | null;
  voidReason?: string | null;
  /** 正在分批派彩（S5，显示"结算中"） */
  settling?: boolean;
  options: MarketOptionDetail[];
  clarifications: MarketClarification[];
  feeChanges: MarketFeeChange[];
  resolution?: MarketResolution | null;
  /** 结算合计和本人的结果（S11；已结算、已取消、已作废才有） */
  settle?: MarketSettle | null;
  /** 本人的开赛提醒（S11；还没开放、或者本人订阅过才有） */
  reminder?: ReminderState | null;
}

export interface MarketQuery {
  category?: MarketCategory;
  /** active（默认，可下注和暂停）/ closed（等结果）/ ended（已结束）；不传为全部 */
  status?: 'active' | 'closed' | 'ended' | 'all';
  /** closeAt 快截止（默认，PQ-2）/ newest 最新 / pool 奖池最大（S11 后端排序） */
  sort?: 'closeAt' | 'newest' | 'pool';
  /** 题目包含或市场编号等于（S11 后端搜索） */
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

/**
 * 已结算下注的结果（S11，PoolViews.Result）：结算按持仓算（同一选项的几笔下注合在一起），派彩、抽成是这个选项的持仓合计，
 * positionBets > 1 时页面写明"本选项 n 笔合计"。
 */
export interface MyBetResult {
  won: boolean;
  positionAmount: string;
  positionBets: number;
  payout: string;
  fee: string;
}

/** 我的下注的一条（PoolViews.MyBet）；status、refundReason S5 起；category、finalMult、result S11 起 */
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
  refundReason?: 'CANCELLED' | 'VOIDED' | 'LATE_BET' | 'STAFF' | null;
  multAtBet: string;
  feeRateAtBet: string;
  category?: MarketCategory | null;
  /** 截止后的最终赔率（定格的奖池）；还没截止、已退回为空 */
  finalMult?: string | null;
  /** 已结算的结果；其他分组为空 */
  result?: MyBetResult | null;
}

export interface MyBets {
  items: MyBet[];
  total: number;
  page: number;
  pageSize: number;
  summary: Record<MyBetTab, { count: number; amount: string }>;
  /** 顶部汇总（S11）：总下注（有效下注本金）、总派彩（派彩 + 补偿）、净盈亏（总派彩 − 已结算本金） */
  totals: { staked: string; payout: string; net: string };
}

/** 提交争议（S7，`POST /api/markets/{publicNo}/disputes`，`resolve/web/DisputeController`）：claimedOptionNo 是选项编号或 "void"（认为应作废） */
export interface DisputeRequest {
  claimedOptionNo: string;
  reason: string;
  link?: string;
}

/** 提交争议的回执（ResolveDeskApi.DisputeReceipt） */
export interface DisputeReceipt {
  disputeNo: string;
  marketNo: string;
  claimedOptionNo: string | null;
  claimVoid: boolean;
  status: string;
  receivedAt: string;
}
