/**
 * 接口错误。页面只显示 reason 对应的文案（i18n 键 errors.<reason>），不显示错误代码。
 *
 * 后端出错时统一返回 `{code, message, fields:[{field, code}]}`（`common.web.ApiError`）。
 * `fromServer` 把后端错误码和字段错误码换成这里的 reason 和翻译键；后端新增错误码时：
 * 在 SERVER_CODES / FIELD_CODES 里加映射，并在 zh-CN.json、en.json 加翻译（errors.test.ts 检查都有翻译）。
 */
export type ApiErrorReason =
  | 'network'
  | 'server'
  | 'timeout'
  | 'unauthorized'
  | 'invalidCredentials'
  | 'accountLocked'
  | 'accountFrozen'
  | 'emailTaken'
  | 'codeInvalid'
  | 'codeTooFrequent'
  | 'rateLimited'
  | 'passwordWrong'
  | 'kycRequired'
  | 'kycPending'
  | 'kycApproved'
  | 'docNumberUsed'
  | 'profileLocked'
  | 'whitelistFull'
  | 'whitelistDuplicate'
  | 'notWhitelisted'
  | 'whitelistNotActive'
  | 'withdrawNotCancellable'
  | 'withdrawInvalid'
  | 'withdrawBelowMin'
  | 'withdrawAboveMax'
  | 'withdrawDailyLimit'
  | 'withdrawInsufficient'
  | 'walletNotReady'
  | 'notFound'
  | 'insufficientFunds'
  // 下注的拒单原因（forecast S4，需求第 3.3 节；余额不足、需要身份认证、账户已冻结用上面已有的）
  | 'marketNotOpen'
  | 'marketClosed'
  | 'marketPaused'
  | 'marketCancelled'
  | 'betBelowMin'
  | 'betAboveMax'
  | 'betOverUserCap'
  | 'betStaffAccount'
  | 'betRequestReused'
  | 'optionNotFound'
  // 提交争议（forecast S7，resolve/web/DisputeController）
  | 'disputeClosed'
  | 'disputeNotBettor'
  | 'disputeExists'
  // #23 开赛提醒（forecast S11，market/remind/ReminderController）
  | 'reminderNotAvailable'
  | 'reminderLoginRequired'
  | 'reminderTooMany'
  // 分享卡片（forecast S14，market/card/CardController）：只能为自己押过、还在下注期的选项生成挑战卡，为押中的已结算市场生成开奖卡
  | 'cardNotAvailable'
  // 等候页（forecast S16，growth/waitlist/WaitlistController）：已经正式上线（不再收邮箱）、确认链接过期
  | 'waitlistClosed'
  | 'waitlistLinkExpired'
  | 'validationFailed'
  | 'badRequest'
  | 'forbidden'
  | 'fileTooLarge';

export class ApiError extends Error {
  readonly reason: ApiErrorReason;
  /** 字段级错误：键是字段名，值是 i18n 键（用于一次显示全部错误） */
  readonly fieldErrors?: Record<string, string[]>;
  /** HTTP 状态码（真实接口才有） */
  readonly status?: number;
  /** 操作太频繁时还要等多少秒（后端 429 的 cooldownSeconds，例如验证码 60 秒内重发） */
  readonly cooldownSeconds?: number;

  constructor(reason: ApiErrorReason, fieldErrors?: Record<string, string[]>, status?: number, cooldownSeconds?: number) {
    super(reason);
    this.name = 'ApiError';
    this.reason = reason;
    this.fieldErrors = fieldErrors;
    this.status = status;
    this.cooldownSeconds = cooldownSeconds;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/**
 * 把任意错误转换成 i18n 键。字段校验失败（validationFailed）且页面没有逐个字段显示时，显示第一个字段错误的原因。
 */
/** 充值地址暂不可用（后端 WALLET_NOT_READY：三条链都没开通，例如服务器还没配置合约地址和 TRON xpub）：页面显示"暂未开通"，不显示成加载失败 */
export function isWalletNotReady(e: unknown): boolean {
  return e instanceof ApiError && e.reason === 'walletNotReady';
}

export function errorKey(e: unknown): string {
  if (!isApiError(e)) return 'errors.server';
  if (e.reason === 'validationFailed' && e.fieldErrors) {
    const first = Object.values(e.fieldErrors).flat()[0];
    if (first) return first;
  }
  return `errors.${e.reason}`;
}

// ---------- 后端错误码 → reason ----------

/** 后端业务错误码（`BizException`、`GlobalExceptionHandler`）对应的 reason */
export const SERVER_CODES: Record<string, ApiErrorReason> = {
  UNAUTHORIZED: 'unauthorized',
  USER_FROZEN: 'accountFrozen',
  FORBIDDEN: 'forbidden',
  LOGIN_LOCKED: 'accountLocked',
  INVALID_CREDENTIALS: 'invalidCredentials',
  CODE_TOO_FREQUENT: 'codeTooFrequent',
  // 通用限流（P-05）：按 IP、按客户的资金写操作超限，或 Nginx 外层限流；429 带 cooldownSeconds 和 Retry-After
  RATE_LIMITED: 'rateLimited',
  CODE_INVALID: 'codeInvalid',
  EMAIL_TAKEN: 'emailTaken',
  CURRENT_PASSWORD_WRONG: 'passwordWrong',
  NOT_FOUND: 'notFound',
  USER_NOT_FOUND: 'notFound',
  ACCOUNT_NOT_FOUND: 'notFound',
  PROFILE_LOCKED: 'profileLocked',
  KYC_PENDING: 'kycPending',
  KYC_APPROVED: 'kycApproved',
  DOC_NUMBER_USED: 'docNumberUsed',
  KYC_REQUIRED: 'kycRequired',
  // 余额不足（复制源的下单拒绝原因在 forecast S1 删除）
  INSUFFICIENT_FUNDS: 'insufficientFunds',
  // 下注（forecast S4，需求第 3.3 节的 11 种拒单原因，后端 pool/bet/BetService.REJECT_CODES）：
  // 余额不足 INSUFFICIENT_FUNDS、需要先完成身份认证 KYC_REQUIRED、账户已冻结 USER_FROZEN 和上面共用
  MARKET_NOT_OPEN: 'marketNotOpen',
  MARKET_CLOSED: 'marketClosed',
  MARKET_PAUSED: 'marketPaused',
  MARKET_CANCELLED: 'marketCancelled',
  BET_BELOW_MIN: 'betBelowMin',
  BET_ABOVE_MAX: 'betAboveMax',
  BET_OVER_USER_CAP: 'betOverUserCap',
  BET_STAFF_ACCOUNT: 'betStaffAccount',
  BET_REQUEST_REUSED: 'betRequestReused',
  OPTION_NOT_FOUND: 'optionNotFound',
  // 提交争议（forecast S7）：不在公示期 / 在这个市场没有下注（403）/ 已经提交过（409）
  DISPUTE_CLOSED: 'disputeClosed',
  DISPUTE_NOT_BETTOR: 'disputeNotBettor',
  DISPUTE_EXISTS: 'disputeExists',
  // #23 开赛提醒（forecast S11）：已开放或已结束 / 没登录过（403，SQ-20；注册后的自动登录也算，SQ-27）/ 订阅中的太多
  REMINDER_NOT_AVAILABLE: 'reminderNotAvailable',
  REMINDER_LOGIN_REQUIRED: 'reminderLoginRequired',
  REMINDER_TOO_MANY: 'reminderTooMany',
  CARD_NOT_AVAILABLE: 'cardNotAvailable',
  WAITLIST_CLOSED: 'waitlistClosed',
  WAITLIST_LINK_EXPIRED: 'waitlistLinkExpired',
  // 钱包
  WALLET_NOT_READY: 'walletNotReady',
  WHITELIST_FULL: 'whitelistFull',
  WHITELIST_DUPLICATE: 'whitelistDuplicate',
  NOT_WHITELISTED: 'notWhitelisted',
  WHITELIST_NOT_ACTIVE: 'whitelistNotActive',
  AMOUNT_BELOW_MIN: 'withdrawBelowMin',
  AMOUNT_ABOVE_MAX: 'withdrawAboveMax',
  DAILY_LIMIT_EXCEEDED: 'withdrawDailyLimit',
  WITHDRAW_NOT_CANCELLABLE: 'withdrawNotCancellable',
  // 通用
  VALIDATION_FAILED: 'validationFailed',
  BAD_REQUEST: 'badRequest',
  METHOD_NOT_ALLOWED: 'badRequest',
  FILE_TOO_LARGE: 'fileTooLarge',
  INTERNAL_ERROR: 'server',
};

/**
 * 字段错误码 → 翻译键。后端的字段错误码有两种写法（认证、资料、KYC 用大写下划线，钱包、通知用和翻译键相同的小驼峰），
 * 按"字段名|错误码"优先、其次只按错误码查；查不到的显示"内容不正确"。
 */
export const FIELD_CODES: Record<string, string> = {
  // 按字段区分的
  'label|tooLong': 'validation.labelTooLong',
  'label|TOO_LONG': 'validation.labelTooLong',
  'firstName|TOO_LONG': 'validation.nameTooLong',
  'middleName|TOO_LONG': 'validation.nameTooLong',
  'lastName|TOO_LONG': 'validation.nameTooLong',
  // 大写下划线（account 模块）
  REQUIRED: 'validation.required',
  EMAIL_INVALID: 'validation.emailFormat',
  CODE_FORMAT: 'validation.codeFormat',
  PASSWORD_LENGTH: 'validation.passwordLength',
  PASSWORD_MIX: 'validation.passwordMix',
  TOO_LONG: 'validation.tooLong',
  BIRTH_DATE_INVALID: 'validation.dateInvalid',
  NATIONALITY_INVALID: 'validation.nationalityInvalid',
  // 注册（forecast S11，需求 P8）：没勾"已年满 18 周岁并同意服务条款"、未满 18 周岁
  ADULT_REQUIRED: 'validation.mustAgree',
  AGE_UNDER_18: 'validation.ageUnder18',
  DOC_TYPE_INVALID: 'validation.docTypeInvalid',
  DOC_NUMBER_INVALID: 'validation.docNumberInvalid',
  FILE_TOO_LARGE: 'validation.fileSize',
  FILE_TYPE_INVALID: 'validation.fileType',
  // 小驼峰（trading、wallet、notification 模块，和翻译键同名）
  required: 'validation.required',
  tooLong: 'validation.tooLong',
  invalid: 'validation.invalid',
  notFound: 'errors.notFound',
  positiveNumber: 'validation.positiveNumber',
  // 部分平仓（体验优化 1021 UX-6）：超过持仓手数、剩下的会小于最小手数
  amountDigits: 'validation.amountDigits',
  addressInvalid: 'validation.addressInvalid',
  selectAddress: 'validation.selectAddress',
};

export interface ServerErrorBody {
  code?: string;
  message?: string;
  fields?: Array<{ field?: string; code?: string }>;
  /** 只在"操作太频繁"（429）时有：还要等多少秒 */
  cooldownSeconds?: number;
}

/** 合法的冷却秒数（正整数）才用，否则当成没有 */
export function validCooldown(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.ceil(v) : undefined;
}

export interface ServerErrorOptions {
  /** 这个接口特有的错误码含义（例如提现的 INSUFFICIENT_BALANCE 是"超过可提金额"） */
  codes?: Record<string, ApiErrorReason>;
  /** 后端字段名 → 页面表单字段名（例如改密码的 currentPassword → current） */
  fieldNames?: Record<string, string>;
  /** 业务错误同时标在某个字段上（例如验证码错误标在验证码输入框） */
  codeFields?: Record<string, string>;
}

/** 把后端的错误响应换成 ApiError */
export function fromServer(status: number, body: ServerErrorBody | undefined, opt: ServerErrorOptions = {}): ApiError {
  const code = body?.code ?? '';
  const reason: ApiErrorReason =
    opt.codes?.[code] ?? SERVER_CODES[code] ?? (status === 401 ? 'unauthorized' : status === 403 ? 'forbidden' : status === 404 ? 'notFound' : status === 429 ? 'rateLimited' : status >= 500 || status === 0 ? 'server' : 'badRequest');

  let fieldErrors: Record<string, string[]> | undefined;
  const add = (field: string, key: string) => {
    fieldErrors ??= {};
    const list = (fieldErrors[field] ??= []);
    if (!list.includes(key)) list.push(key);
  };
  for (const f of body?.fields ?? []) {
    if (!f.field) continue;
    const field = opt.fieldNames?.[f.field] ?? f.field;
    const c = f.code ?? '';
    add(field, FIELD_CODES[`${f.field}|${c}`] ?? FIELD_CODES[c] ?? 'validation.invalid');
  }
  const onField = opt.codeFields?.[code];
  if (onField) add(onField, `errors.${reason}`);
  return new ApiError(reason, fieldErrors, status, validCooldown(body?.cooldownSeconds));
}
