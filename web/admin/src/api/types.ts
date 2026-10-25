/**
 * 后台接口的数据类型，字段和后端 `backend/src/main/java/com/forecast/app/admin/` 返回的 record 一致。
 * 金额一律是十进制字符串。时间：真实接口是 ISO UTC 字符串（如 "2026-10-14T01:12:33Z"），模拟数据是 "YYYY-MM-DD HH:mm:ss"（UTC+8），
 * 页面统一用 utils/display 的 fullTime / shortTime 显示成 UTC+8。
 * Admin API types. Amounts are decimal strings; real timestamps are ISO UTC, mock ones "YYYY-MM-DD HH:mm:ss".
 */

export type Chain = 'ERC20' | 'TRC20' | 'BSC';
export type Lang = 'zh-CN' | 'en';

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface PageQuery {
  page: number;
  pageSize: number;
}

/** 后端字段错误（`{field, code}`，code 如 required、invalid、tooLong、weak） */
export interface FieldError {
  field: string;
  code: string;
}

/**
 * 接口错误：reasonKey 是翻译键，界面显示具体原因，不显示错误代码。
 * 真实接口另带 HTTP 状态、后端错误码和字段错误（字段错误由 utils/errors 翻译成"哪个字段、什么问题"）。
 */
export class ApiError extends Error {
  readonly reasonKey: string;
  readonly params?: Record<string, string | number>;
  readonly status?: number;
  readonly code?: string;
  readonly fields?: FieldError[];
  constructor(reasonKey: string, params?: Record<string, string | number>, extra?: { status?: number; code?: string; fields?: FieldError[] }) {
    super(reasonKey);
    this.name = 'ApiError';
    this.reasonKey = reasonKey;
    this.params = params;
    this.status = extra?.status;
    this.code = extra?.code;
    this.fields = extra?.fields;
  }
}

// ---------- 认证 / 身份 ----------
export type RoleCode = 'super_admin' | 'operations' | 'risk' | string;

/** 登录结果。真实接口不返回令牌（令牌只在 HttpOnly Cookie 里，后台方案第 7 节）；模拟层带一个演示用的 token */
export interface AdminSession {
  token?: string;
  adminId: number;
  username: string;
  displayName: string;
  /** 首次登录必须先改密码 */
  mustChangePassword: boolean;
}

// ---------- 仪表盘 ----------
export interface DashboardStats {
  todayRegistrations: number;
  onlineCustomers: number;
  todayDeposit: string;
  todayDepositCount: number;
  todayWithdraw: string;
  todayWithdrawCount: number;
  pendingKyc: number;
  pendingWithdrawals: number;
  failedTasks: number;
  openReconciliations: number;
  updatedAt: string;
}

/** 当前管理员（GET /api/admin/me）。menus 是后端按权限点和"是否显示"过滤后的菜单；模拟层不返回，按本地菜单配置显示 */
export interface CurrentAdmin {
  admin: AdminUser;
  roles: Role[];
  permissions: string[];
  isSuperAdmin: boolean;
  menus?: MenuNode[];
}

export interface MenuBadges {
  kyc: number;
  withdrawals: number;
}

// ---------- 客户 ----------
export type CustomerStatus = 'active' | 'frozen';
export type KycStatus = 'none' | 'pending' | 'approved' | 'rejected';

export interface CustomerSummary {
  id: number;
  name: string;
  email: string;
  nationality: string;
  status: CustomerStatus;
  kycStatus: KycStatus;
  balance: string;
  registeredAt: string;
  lastLoginAt: string | null;
}

export interface CustomerQuery extends PageQuery {
  keyword?: string;
  status?: CustomerStatus;
  kycStatus?: KycStatus;
}

/** 资金流水类型（可用余额的变动）：充值、提现、提现退回；S2 起后端还会给出下注、派彩、抽成、下注退回、补偿（S4 起才有记录）；没有对应的用小写原名 */
export type LedgerType = 'deposit' | 'withdraw' | 'refund' | 'bet' | 'payout' | 'fee' | 'betRefund' | 'compensation' | string;

export interface LedgerEntry {
  id: string;
  type: LedgerType;
  amount: string;
  balanceAfter: string;
  ref: string;
  time: string;
}

export interface CustomerDetail extends CustomerSummary {
  /** 最近一次登录的 IP 和时间（v2 E6，取最近一个登录会话）；从没登录过为空 */
  lastLoginIp?: string | null;
  lastLoginIpAt?: string | null;
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  birthDate: string | null;
  frozenReason: string | null;
  kyc: {
    status: KycStatus;
    docType: DocType | null;
    docNumber: string | null;
    reviewedAt: string | null;
    rejectReason: string | null;
  };
  /** S2：余额 = 可用 + 下注中；available = 可提金额（= 可用余额）；betLocked = 下注中（下注锁定）；frozenWithdraw = 提现冻结 */
  account: {
    balance: string;
    available: string;
    betLocked: string;
    frozenWithdraw: string;
  };
  ledger: LedgerEntry[];
}

/** 后台编辑客户资料（v2 E1）：只有这 5 项；邮箱不能改，状态只能用冻结 / 解冻改 */
export interface CustomerProfileInput {
  firstName: string;
  middleName: string;
  lastName: string;
  /** YYYY-MM-DD */
  birthDate: string;
  /** ISO 3166-1 两位大写字母 */
  nationality: string;
}

// ---------- KYC ----------
export type DocType = 'id_card' | 'passport';

export interface KycApplication {
  id: string;
  customerId: number;
  name: string;
  email: string;
  nationality: string;
  birthDate: string;
  docType: DocType;
  docNumber: string;
  status: Exclude<KycStatus, 'none'>;
  submittedAt: string;
  reviewer: string | null;
  reviewedAt: string | null;
  rejectReason: string | null;
}

export interface KycQuery extends PageQuery {
  status?: KycApplication['status'];
  keyword?: string;
}

// ---------- 资金 ----------
export type DepositStatus = 'confirming' | 'credited' | 'ignored' | 'reversed';

export interface Deposit {
  id: string;
  customerId: number;
  customerName: string;
  email: string;
  chain: Chain;
  txHash: string;
  amount: string;
  confirmations: number;
  requiredConfirmations: number;
  status: DepositStatus;
  detectedAt: string;
  creditedAt: string | null;
}

export interface DepositQuery extends PageQuery {
  chain?: Chain;
  status?: DepositStatus;
  keyword?: string;
}

export type WithdrawalStatus =
  | 'cooling'
  | 'pending'
  | 'delayed'
  | 'broadcasting'
  | 'completed'
  | 'rejected'
  | 'cancelled'
  | 'failed';

export interface Withdrawal {
  id: string;
  customerId: number;
  customerName: string;
  email: string;
  amount: string;
  fee: string;
  chain: Chain;
  address: string;
  firstUse: boolean;
  whitelistAddedAt: string;
  status: WithdrawalStatus;
  /** 延迟期剩余分钟数（仅 delayed） */
  delayRemainingMinutes: number | null;
  submittedAt: string;
  reviewer: string | null;
  reason: string | null;
  txHash: string | null;
  /** v2 自动审批（技术方案/v2-提现自动审批.md）：最近一次规则判定的摘要；没有判定记录（功能上线前的提现）时为空 */
  autoReview?: AutoReviewSummary | null;
}

// ---------- v2 提现自动审批（技术方案/v2-提现自动审批.md 第 7.2 节；后端 admin/ops/AutoApproveOps.java） ----------
export type AutoRuleCode = 'R1' | 'R2' | 'R3' | 'R4' | 'R5' | 'R6' | 'R7' | 'R8' | 'R9' | 'R10' | 'R11' | 'R12' | 'R13';
/** 判定结果：自动通过 / 转人工（有规则没通过）/ 总开关关闭（全部人工，规则照常判定做参考）/ 判定出错（按转人工处理） */
export type AutoReviewOutcome = 'auto_approved' | 'manual' | 'master_off' | 'error';
/** 判定时机：提交时预判（只做参考，不占额度）/ 冷静期结束时复判（以这次为准） */
export type AutoReviewPhase = 'precheck' | 'final';

export interface AutoReviewSummary {
  phase: AutoReviewPhase;
  outcome: AutoReviewOutcome;
  /** 没通过的规则（含无法判定的），按编号排序；全部通过时为空 */
  failed: AutoRuleCode[];
  decidedAt: string;
}

/** 一条规则的判定结果。values 是判定时刻的实际数值和阈值（快照），前端按规则的模板显示，以后改规则不影响 */
export interface AutoRuleResult {
  code: AutoRuleCode;
  status: 'passed' | 'failed' | 'disabled' | 'unknown';
  values: Record<string, string>;
}

export interface AutoReviewDetail extends AutoReviewSummary {
  /** 判定时总开关的状态（快照） */
  masterEnabled: boolean;
  rules: AutoRuleResult[];
  /** 判定时用的规则配置版本和这一版的修改人、时间 */
  configVersion: number;
  configChangedAt: string | null;
  configChangedBy: string | null;
  /** 复判记录上附带提交时的预判摘要（冷静期中的提现只有预判） */
  precheck: AutoReviewSummary | null;
}

/** 出金列表的"规则判定"筛选：判定结果，或触发了某条规则 */
export type AutoReviewFilter = 'auto_approved' | 'manual' | 'master_off' | 'none' | AutoRuleCode;

export interface AutoRuleSetting {
  code: AutoRuleCode;
  enabled: boolean;
  /** 数值参数（字符串，和系统配置一样）；没有数值的规则为空对象 */
  params: Record<string, string>;
}

export interface AutoApproveSettings {
  /** 总开关，默认关闭（= 全部人工审核） */
  masterEnabled: boolean;
  rules: AutoRuleSetting[];
  /** 配置版本：每次保存 +1，判定记录里记这个号 */
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface AutoApproveSettingsInput {
  masterEnabled: boolean;
  rules: AutoRuleSetting[];
  reason: string;
  /** 页面打开时读到的版本；后端发现版本变了返回 409 CONFIG_CHANGED（防止两个人同时改互相覆盖） */
  expectedVersion: number;
}

/** 一次保存的修改记录（同时写审计日志 withdrawal.auto_rule_update） */
export interface AutoApproveChange {
  id: string;
  version: number;
  time: string;
  adminName: string;
  reason: string;
  /** field：master，或 R1.enabled、R1.maxAmount 这样的"规则.参数" */
  changes: { field: string; before: string; after: string }[];
}

export interface WithdrawalDetail extends Withdrawal {
  /** v2 自动审批：规则判定卡片的数据（逐条结果；判定时有几条就是几条，R13 上线前的记录只有 12 条） */
  autoReviewDetail?: AutoReviewDetail | null;
  kycStatus: KycStatus;
  registeredDays: number;
  totalDeposit: string;
  balance: string;
  recentDeposits: { time: string; chain: Chain; amount: string }[];
  recentWithdrawals: { time: string; chain: Chain; amount: string }[];
}

/** 筛选：open = 待处理（全部：冷静期 + 待审核 + 延迟期） */
export interface WithdrawalQuery extends PageQuery {
  status?: WithdrawalStatus | 'open';
  chain?: Chain;
  minAmount?: string;
  maxAmount?: string;
  keyword?: string;
  /** v2 自动审批：按最近一次判定的结果或触发的规则筛选 */
  autoReview?: AutoReviewFilter;
}

export interface WithdrawalSummary {
  pending: { count: number; amount: string };
  delayed: { count: number; amount: string };
  cooling: { count: number; amount: string };
  completedToday: { count: number; amount: string };
}

/** 对账问题类型：按后端的 7 种（数据库设计第 16 节、后台方案第 8 节），不再用原型的 4 种；v2 资金归集加 3 种（方案第 4.8 节） */
export type ReconType =
  | 'sweep_unknown_outflow'
  | 'sweep_dest_mismatch'
  | 'sweep_amount_mismatch'
  | 'deposit_ledger_mismatch'
  | 'deposit_unknown_credit'
  | 'deposit_reversal_mismatch'
  | 'deposit_not_on_chain'
  | 'suspense_open'
  | 'withdraw_ledger_mismatch'
  | 'withdraw_frozen_mismatch';

/** 对账问题（后台方案第 8 节）：ref 是关联的业务编号，expected 应有、actual 实际、diff = actual - expected */
export interface ReconItem {
  id: string;
  type: ReconType | string;
  ref: string;
  customerId: number | null;
  customerName: string | null;
  expected: string | null;
  actual: string | null;
  diff: string | null;
  /** 后端生成的说明（中文） */
  detail: string | null;
  status: 'open' | 'resolved';
  note: string | null;
  handler: string | null;
  detectedAt: string;
  resolvedAt: string | null;
}

/**
 * 热钱包余额（对账页上方，GET /api/admin/hot-wallets）。status：ok 正常、low 低于告警下限、off 下限为 0 不告警、unknown 还没查到余额。
 * threshold 是系统配置页的下限（hotWallet.*）；error 是最近一次查询失败的原因
 */
export interface HotWalletBalance {
  chain: 'ERC20' | 'BSC' | 'TRC20';
  asset: 'USDT' | 'ETH' | 'BNB' | 'TRX';
  gas: boolean;
  address: string;
  balance: string | null;
  threshold: string;
  status: 'ok' | 'low' | 'off' | 'unknown';
  checkedAt: string | null;
  error: string | null;
}

export interface ReconQuery extends PageQuery {
  status?: ReconItem['status'];
}

/** 任务类型：只有邮件和推送（提现的广播和确认不走任务表，后台方案第 8 节） */
export type TaskType = 'email' | 'push';
export type TaskStatus = 'pending' | 'processing' | 'success' | 'failed';

export interface TaskItem {
  id: string;
  type: TaskType;
  bizRef: string;
  status: TaskStatus;
  attempts: number;
  maxAttempts: number;
  nextRetryAt: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaskQuery extends PageQuery {
  type?: TaskType;
  status?: TaskStatus;
}

// ---------- 通知 ----------
export type NotifyChannel = 'inbox' | 'push' | 'email';

export interface NotifyTemplate {
  id: string;
  code: string;
  nameZh: string;
  nameEn: string;
  builtIn: boolean;
  enabled: boolean;
  channels: NotifyChannel[];
  titleZh: string;
  bodyZh: string;
  titleEn: string;
  bodyEn: string;
  variables: string[];
  updatedAt: string;
}

export interface NotifyRecord {
  id: string;
  /** 按邮箱发的（风控人员等）没有客户号，customerName 是收件人 */
  customerId: number | null;
  customerName: string;
  source: 'template' | 'manual';
  templateCode: string | null;
  channel: NotifyChannel;
  title: string;
  status: 'pending' | 'sent' | 'failed' | 'skipped';
  error: string | null;
  operator: string | null;
  time: string;
}

export interface NotifyRecordQuery extends PageQuery {
  channel?: NotifyChannel;
  status?: NotifyRecord['status'];
  keyword?: string;
}

export interface ManualNotifyInput {
  customerId: number;
  channels: NotifyChannel[];
  title: string;
  body: string;
  reason: string;
}

// ---------- 系统 ----------
export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  email: string;
  roleIds: string[];
  status: 'active' | 'disabled';
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

/** 新建管理员、重置密码的结果：临时密码只返回这一次（后台方案第 7 节），页面在弹窗里显示一次 */
export interface TemporaryPassword {
  id: number;
  temporaryPassword: string;
}

export interface AdminInput {
  username: string;
  displayName: string;
  email: string;
  roleIds: string[];
}

export interface Role {
  id: string;
  code: RoleCode;
  nameZh: string;
  nameEn: string;
  description: string;
  permissions: string[];
  builtIn: boolean;
  memberCount: number;
}

export interface RoleInput {
  code: string;
  nameZh: string;
  nameEn: string;
  description: string;
  permissions: string[];
}

export interface MenuNode {
  key: string;
  path: string | null;
  labelKey: string;
  permission: string | null;
  order: number;
  visible: boolean;
  children?: MenuNode[];
}

export interface PermissionDef {
  code: string;
  module: string;
  highRisk: boolean;
}

export type AuditValue = string | number | boolean | null | string[];

export interface AuditLog {
  id: string;
  time: string;
  adminName: string;
  ip: string;
  action: string;
  target: string;
  highRisk: boolean;
  result: 'success' | 'failure';
  reason: string | null;
  before: Record<string, AuditValue> | null;
  after: Record<string, AuditValue> | null;
}

export interface AuditQuery extends PageQuery {
  action?: string;
  admin?: string;
  highRiskOnly?: boolean;
  keyword?: string;
}

/** 参数设置的一组（forecast S3）：fee 品类默认费率（fee:edit）；betting 下注限额、争议窗口、KYC 阈值、单笔最小提现等（betting_params:edit） */
export type ParamSection = 'fee' | 'betting';

/** 参数设置的一项（后端 SystemService.ParamView，GET /api/admin/params/{section}）；页面在原型确认后做 */
export interface ParamItem {
  key: string;
  value: string;
  unit: 'usdt' | 'percent' | 'minutes' | 'seconds';
  group: string;
  section: ParamSection;
  /** 从没改过时为空 */
  updatedAt: string | null;
}

export interface SystemConfig {
  key: string;
  value: string;
  /** eth / bnb / trx：热钱包手续费币的告警下限 */
  unit: 'usdt' | 'hours' | 'minutes' | 'count' | 'percent' | 'text' | 'eth' | 'bnb' | 'trx';
  /** hotWallet：热钱包余额告警下限（0 = 不告警） */
  group: 'withdraw' | 'deposit' | 'security' | 'adjust' | 'notify' | 'hotWallet';
  /** 从没改过时为空 */
  updatedAt: string | null;
}

// ---------- v2 资金归集（原型，技术方案/v2-半自动归集.md 路线 (b)；真实接口上线前只有模拟层提供） ----------
export type SweepChain = 'ERC20' | 'BSC' | 'TRC20';
/** 归集方式：以太坊、BSC 是合约转发地址 + 服务器自动归集；TRON 是普通地址 + Amos 离线签名 */
export type SweepMode = 'contract' | 'offline';
export type SweepBatchStatus =
  /** 真实接口：已建批次、交易还没发出 */
  | 'pending'
  | 'sent'
  | 'confirmed'
  | 'partial'
  | 'failed'
  | 'deferred'
  | 'topping_up'
  | 'awaiting_signature'
  | 'signed'
  | 'broadcasting'
  | 'cancelled'
  | 'expired';
export type SweepItemStatus = 'pending' | 'swept' | 'failed' | 'topup_sent' | 'ready' | 'signed' | 'sign_rejected' | 'broadcast' | 'expired' | 'cancelled';
/** 预览里这个地址这次为什么不归集 */
export type SweepSkipReason =
  | 'pendingDeposit'
  | 'recentCredit'
  | 'inBatch'
  | 'feeRatio'
  | 'belowThreshold'
  | 'overAddressCap'
  | 'overBatchCap'
  /** 真实接口：超过每批地址数（100） */
  | 'batchFull'
  /** 真实接口：旧一套合约参数的地址（换过工厂 / parent / 批量合约），这一批不处理 */
  | 'otherScheme';
/** TRON 租能量的状态（方案 v1.1 第 4.3 节）：广播前给每个充值地址租能量，确认到账再广播 */
export type SweepRentStatus = 'none' | 'ordered' | 'delivered' | 'fallbackBurn' | 'notDelivered' | 'expired';
export interface SweepRent {
  status: SweepRentStatus;
  /** 租的能量 */
  energy: number;
  /** 租金（TRX）；回退烧 TRX 时是热钱包补的 TRX */
  trx: string;
  /** 租赁到期时间 */
  expiresAt: string | null;
}

export interface SweepOverview {
  chain: SweepChain;
  mode: SweepMode;
  /** 达到门槛的地址的 USDT 合计 */
  pendingUsdt: string;
  eligibleCount: number;
  /** 有余额但没到门槛的地址数 */
  belowCount: number;
  /** 冷钱包地址（以太坊、BSC 是合约里的 parent，TRON 是后台配置的） */
  destination: string | null;
  /** 以太坊、BSC 发起账户的手续费币余额；TRON 为空 */
  operatorBalance: string | null;
  gasAsset: string;
  lastSweepAt: string | null;
  /** 进行中的批次号 */
  activeBatch: string | null;
  /** 真实接口：这条链现在能不能执行归集；不能时 unavailable 是原因（例如没配合约地址、TRON 下一阶段） */
  available?: boolean;
  unavailable?: string | null;
  /** 真实接口：待归集金额的查询时间（每 5 分钟或打开预览时） */
  checkedAt?: string | null;
}

export interface SweepCandidate {
  userId: number;
  customerName: string;
  address: string;
  /** TRON：派生序号（= 用户 ID） */
  index: number;
  usdt: string;
  /** 以太坊、BSC：这次是否要顺便部署转发合约 */
  deploy: boolean;
  /** TRON：热钱包转入的 TRX（只有没激活的地址要转 1 TRX 激活）；以太坊、BSC 为 "0" */
  topup: string;
  /** TRON：这个地址还没激活（只收过 TRC20 不会激活账户），要先转 TRX */
  activate: boolean;
  /** TRON：预计租金（TRX）；以太坊、BSC 为 "0" */
  rentTrx: string;
  /** 预估手续费（手续费币） */
  estFee: string;
  estFeeUsd: string;
  skip: SweepSkipReason | null;
}

export interface SweepPreview {
  chain: SweepChain;
  mode: SweepMode;
  destination: string | null;
  threshold: string;
  maxFeeRatioPct: string;
  /** 当前 gas 价格（显示用文字） */
  gasPrice: string;
  gasAsset: string;
  candidates: SweepCandidate[];
  totals: { count: number; usdt: string; fee: string; feeUsd: string; feePct: string; topup: string; rent: string; skipped: number };
  /** TRON：租能量的报价（以太坊、BSC 为空） */
  tronRent: { mode: SweepTronEnergyMode; provider: string; priceSun: string; energyPerItem: number; durationMin: number; burnSun: string } | null;
  /** 真实接口：提示，例如 priceMissing（查不到 ETH / BNB 价格，没有检查手续费占比） */
  warnings?: string[];
}

export interface SweepBatch {
  no: string;
  chain: SweepChain;
  mode: SweepMode;
  status: SweepBatchStatus;
  trigger: 'manual' | 'auto';
  addressCount: number;
  expectedUsdt: string;
  sweptUsdt: string;
  fee: string;
  gasAsset: string;
  destination: string;
  createdBy: string;
  createdAt: string;
  finishedAt: string | null;
  /** TRON：待签名清单的有效期 */
  expiresAt: string | null;
  /** 以太坊、BSC：批量交易的哈希 */
  txHash: string | null;
}

export interface SweepItem {
  itemNo: number;
  userId: number;
  customerName: string;
  address: string;
  amount: string;
  deploy: boolean;
  topup: string;
  status: SweepItemStatus;
  txHash: string | null;
  /** 失败或核对不通过的原因：原型是翻译键 sweepFail.* 的后缀；真实接口是后端给的中文说明 */
  failReason: string | null;
  /** 真实接口：执行前查到的余额（amount 是链上实际转走的金额） */
  balanceBefore?: string;
  /** TRON：这个地址的能量租赁（以太坊、BSC 为空） */
  rent: SweepRent | null;
}

export interface SweepBatchDetail extends SweepBatch {
  reason: string;
  /** 真实接口：批次失败或部分成功的原因 */
  failReason?: string | null;
  items: SweepItem[];
  unsignedSha256: string | null;
  signedSha256: string | null;
  events: { time: string; actor: string; text: string }[];
}

export interface SweepImportResult {
  accepted: number;
  rejected: { itemNo: number; reason: string }[];
}

export interface SweepTronTarget {
  address: string | null;
  pending: { address: string; requestedAt: string; effectiveAt: string; requestedBy: string; reason: string } | null;
}

export interface SweepSettings {
  thresholds: Record<SweepChain, string>;
  maxFeeRatioPct: string;
  autoEnabled: boolean;
  autoHourUtc: number;
  ethMaxBaseFeeGwei: string;
  operatorMin: { ERC20: string; BSC: string };
  remind: Record<SweepChain, string>;
  tronTopupPerItem: string;
  tronTopupPerDay: string;
  tronExpireHours: number;
  tronTargetDelayHours: number;
  /** TRON 能量（Amos 10-20：直接租能量） */
  tronEnergy: SweepTronEnergy;
  /** 以太坊、BSC 上线初期的金额上限（SW-4 不审计的替代措施）；"0" = 不限 */
  evmCaps: { perAddress: string; perBatch: string };
  /** 以太坊、BSC 的合约和冷钱包（从链上读，只读） */
  evm: {
    parent: string;
    safeThreshold: string;
    factory: string;
    implementation: string;
    batcher: string;
    operator: string;
    operatorBalances: { ERC20: string; BSC: string };
    readAt: string;
    /** 真实接口：合约地址参数配齐并检查通过；problem 是配置问题 */
    configured?: boolean;
    problem?: string | null;
    /** 真实接口：发起账户私钥和地址都配了并且对得上；operatorProblem 是原因 */
    operatorReady?: boolean;
    operatorProblem?: string | null;
  };
  tronTarget: SweepTronTarget;
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

export type SweepSettingsInput = Pick<
  SweepSettings,
  'thresholds' | 'maxFeeRatioPct' | 'autoEnabled' | 'autoHourUtc' | 'ethMaxBaseFeeGwei' | 'operatorMin' | 'remind' | 'tronTopupPerItem' | 'tronTopupPerDay' | 'tronExpireHours' | 'evmCaps'
> & { tronEnergy: SweepTronEnergyInput; reason: string; expectedVersion: number };

export type SweepTronEnergyMode = 'rent' | 'burn';

export interface SweepTronEnergy {
  /** rent = 租能量（默认）；burn = 热钱包补 TRX、烧 TRX */
  mode: SweepTronEnergyMode;
  /** 租赁平台（显示用，平台本身在服务器配置） */
  provider: string;
  /** 最高单价（sun / 能量），报价高于它就不租 */
  maxPriceSun: string;
  /** 每天租金上限（TRX） */
  perDayTrx: string;
  /** 租不到 / 没到账时回退到烧 TRX（热钱包补 TRX） */
  fallbackBurn: boolean;
  /** 每笔租多少分钟 */
  durationMin: number;
  /** 平台账户余额（只读，从平台接口读） */
  providerBalance: string;
  /** 平台余额告警下限，0 = 不告警 */
  providerBalanceMin: string;
  /** 今天已花的租金（只读） */
  spentTodayTrx: string;
}

export type SweepTronEnergyInput = Pick<SweepTronEnergy, 'mode' | 'maxPriceSun' | 'perDayTrx' | 'fallbackBurn' | 'durationMin' | 'providerBalanceMin'>;

export interface SweepBatchQuery extends PageQuery {
  chain?: SweepChain;
}

// ---------- 市场管理、裁决台（forecast M1 原型；需求第 4、6 节） ----------
/**
 * 市场管理用到的结构：字段按后端 S3 已有的 `MarketAdminApi.Summary / Detail` 定（编号是字符串、费率是百分比字符串、时间是 ISO UTC），
 * 奖池和人数来自 `GET /api/admin/markets/{publicNo}/bets`（S4）。裁决台（S7）的结构是 M1 提议，正式开发时由后端照着定。
 */
export interface I18nText {
  en: string;
  zhCN: string;
  zhTW: string;
}

export type MarketCategory = 'FOOTBALL' | 'NBA' | 'CRYPTO_15M' | 'CRYPTO_1H' | 'MACRO';
export type AdminMarketStatus = 'draft' | 'listed' | 'paused' | 'closed' | 'pending_resolution' | 'publicizing' | 'disputed' | 'settled' | 'cancelled' | 'voided';

export interface AdminMarketRow {
  publicNo: string;
  category: MarketCategory;
  title: I18nText;
  status: AdminMarketStatus;
  /** 草稿已提交上架（出现在"待上架"） */
  submitted: boolean;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  feeRate: string;
  pool: string;
  bettors: number;
  resolutionMode: 'AUTO' | 'PLATFORM';
  /** 来源：手动、赛程生成、加密自动生成 */
  origin: 'manual' | 'schedule' | 'autogen';
  createdBy: string;
}

export interface AdminMarketQuery extends PageQuery {
  tab?: 'all' | 'review' | 'draft' | 'active' | 'waiting' | 'ended';
  category?: MarketCategory;
  keyword?: string;
}

export interface MarketOptionInput {
  label: I18nText;
  winCondition: I18nText;
}

/** 第 4.1 节的规则模板（MarketAdminApi.DraftInput） */
export interface MarketDraft {
  publicNo: string | null;
  category: MarketCategory;
  title: I18nText;
  options: MarketOptionInput[];
  rules: I18nText;
  resolutionMode: 'AUTO' | 'PLATFORM';
  sourcePrimary: string;
  sourceBackup: string;
  openAt: string;
  closeAt: string;
  eventStartAt: string;
  eventEndAt: string;
  voidRules: I18nText;
  correction: I18nText;
  disputeWindowMinutes: number | null;
  /** 只读：新建时按品类默认值带出，改要 fee:edit */
  feeRate: string;
  userCap: string;
  /** 后端返回的"还缺哪些必填项"（字段名），空表示可以提交上架 */
  missing: string[];
  status: AdminMarketStatus;
  submitted: boolean;
  version: number;
}

export type ResolveStage = 'toPropose' | 'toConfirm' | 'autoFailed' | 'overdue';

/**
 * 裁决台的一条（M1 原型的页面模型）。后端 S7 已有接口（`resolve/api/ResolveDeskApi`：`GET /api/admin/resolve/cases` 的 `CaseItem`、
 * `GET /api/admin/resolve/cases/{publicNo}` 的 `CaseDetail`）；S9 做正式页面时按后端字段改这里和模拟层（项目地图约定 48），原型阶段只在模拟层用。
 */
export interface ResolveCase {
  publicNo: string;
  category: MarketCategory;
  title: I18nText;
  mode: 'AUTO' | 'PLATFORM';
  stage: ResolveStage;
  eventEndAt: string;
  /** 提议或确认的截止时间（超时标红） */
  dueAt: string;
  pool: string;
  bettors: number;
  feeRate: string;
  disputeWindowMinutes: number;
  options: Array<{ optionNo: string; label: I18nText; pool: string; bettors: number; positions: number }>;
  /** 自动裁决失败时两个来源的原始数据 */
  sources?: Array<{ name: string; value: string; fetchedAt: string; ok: boolean }>;
  proposal?: {
    by: string;
    at: string;
    optionNo: string;
    score?: string;
    actualStartAt?: string;
    evidence: string[];
    screenshots: number;
    note: string;
  };
  rulesExcerpt: I18nText;
}

/**
 * 按某个结果的结算预览（需求第 3.5 节：派彩合计 + 抽成合计 + 尾差 = 奖池合计）。
 * 字段和后端 S7 `GET /api/admin/markets/{publicNo}/settle-preview?optionNo=&actualStartAt=` 一致（`settle/api/SettleAdminApi.Preview`；整合 2 时按后端改）。
 * outcome：settle 结算 / void_no_winner 没人押赢方会作废（E1）/ void_no_pool E14、E15 退回后奖池为 0 会作废。
 */
export interface SettlePreview {
  marketNo: string;
  optionNo: string;
  actualStartAt: string | null;
  outcome: 'settle' | 'void_no_winner' | 'void_no_pool';
  feeRate: string;
  poolTotal: string;
  winnerPool: string;
  loserPool: string;
  sumGross: string;
  sumFee: string;
  dust: string;
  sumPayout: string;
  platformIncome: string;
  winnerPositions: number;
  loserPositions: number;
  /** ΣPay + ΣF + D = T 且 0 ≤ D < 0.01 × 赢方持仓数 */
  conserved: boolean;
  lateBets: number;
  lateAmount: string;
  staffBets: number;
  staffAmount: string;
}

export interface ResolveSummary {
  toPropose: number;
  toConfirm: number;
  autoFailed: number;
  overdue: number;
}
