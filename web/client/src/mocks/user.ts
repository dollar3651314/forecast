/**
 * 模拟：账户、认证、资料、KYC、通知偏好、站内信。
 */
import { ApiError } from '../api/errors';
import type {
  EmailCodePurpose,
  KycInfo,
  KycStatus,
  KycSubmit,
  Lang,
  NotificationItem,
  NotificationKind,
  NotifyPrefs,
  Page,
  Profile,
  ProfileUpdate,
  RegisterRequest,
  Session,
} from '../api/types';
import { emit } from './bus';
import { db, type StoredNotification } from './db';
import { mutate, respond } from './delay';
import { nextId } from './ids';

/** 原型固定验证码 */
export const MOCK_EMAIL_CODE = '123456';
const lastCodeSent = new Map<string, number>();

export function sendEmailCode(email: string, purpose: EmailCodePurpose): Promise<{ cooldownSeconds: number }> {
  return mutate(() => {
    const k = `${purpose}|${email.toLowerCase()}`;
    const last = lastCodeSent.get(k) ?? 0;
    // 和后端一致：60 秒内重发是 429，带还要等的秒数
    if (Date.now() - last < 60_000) throw new ApiError('codeTooFrequent', undefined, 429, Math.ceil((60_000 - (Date.now() - last)) / 1000));
    if (purpose === 'register' && email.toLowerCase() === 'taken@example.com') throw new ApiError('emailTaken');
    lastCodeSent.set(k, Date.now());
    return { cooldownSeconds: 60 };
  });
}

function makeSession(email: string): Session {
  return { token: nextId('tok_'), userId: db.profile.userId, email };
}

export function login(email: string, password: string): Promise<Session> {
  return mutate(() => {
    if (email.toLowerCase().includes('locked')) throw new ApiError('accountLocked');
    if (password === 'wrongpass1') throw new ApiError('invalidCredentials');
    return makeSession(email);
  });
}

export function register(req: RegisterRequest): Promise<Session> {
  return mutate(() => {
    // 和后端一样：没勾"已年满 18 周岁并同意服务条款"拒绝（S11）
    if (!req.adultConfirmed) throw new ApiError('validationFailed', { agree: ['validation.mustAgree'] });
    if (req.code !== MOCK_EMAIL_CODE) throw new ApiError('codeInvalid', { code: ['errors.codeInvalid'] });
    if (req.email.toLowerCase() === 'taken@example.com') throw new ApiError('emailTaken', { email: ['errors.emailTaken'] });
    db.profile = { ...db.profile, email: req.email, firstName: req.firstName, middleName: req.middleName, lastName: req.lastName, birthDate: req.birthDate, nationality: req.nationality };
    db.password = req.password;
    db.kyc = { status: 'none' };
    return makeSession(req.email);
  });
}

export function resetPassword(email: string, code: string, password: string): Promise<void> {
  return mutate(() => {
    if (code !== MOCK_EMAIL_CODE) throw new ApiError('codeInvalid', { code: ['errors.codeInvalid'] });
    void email;
    db.password = password;
  });
}

export function changePassword(current: string, password: string): Promise<void> {
  return mutate(() => {
    // 原型：原密码填 wrongpass1 时模拟"原密码错误"
    if (current === 'wrongpass1') throw new ApiError('passwordWrong', { current: ['errors.passwordWrong'] });
    db.password = password;
  });
}

export function logout(): Promise<void> {
  return mutate(() => undefined);
}

export function getProfile(): Promise<Profile> {
  return respond(() => ({ ...db.profile }));
}

export function updateProfile(p: ProfileUpdate): Promise<Profile> {
  return mutate(() => {
    db.profile = { ...db.profile, ...p };
    return { ...db.profile };
  });
}

// ---------- KYC ----------
const REJECT_REASON = {
  zh: '证件反面照片模糊，无法辨认有效期，请重新拍摄后提交。',
  en: 'The back of your ID is blurry and the expiry date cannot be read. Please retake the photo and resubmit.',
};

export function getKyc(lang: Lang): Promise<KycInfo> {
  return respond<KycInfo>(
    () => {
      const { rejectReasonI18n, ...rest } = db.kyc;
      void rejectReasonI18n;
      return { ...rest, rejectReason: db.kyc.status === 'rejected' ? (lang === 'en' ? REJECT_REASON.en : REJECT_REASON.zh) : undefined };
    },
    () => ({ status: 'none' as const }),
  );
}

export function submitKyc(s: KycSubmit): Promise<KycInfo> {
  return mutate(() => {
    // 正式环境：文件上传到对象存储，数据库只保存路径（A-13）
    db.kyc = { status: 'pending', docType: s.docType, docNumber: s.docNumber, submittedAt: new Date().toISOString() };
    return { ...db.kyc };
  });
}

/** 原型面板：直接设置 KYC 状态，模拟后台审核 */
export function protoSetKyc(status: KycStatus): void {
  const now = new Date().toISOString();
  db.kyc =
    status === 'none'
      ? { status }
      : { status, docType: db.kyc.docType ?? 'idCard', docNumber: db.kyc.docNumber ?? '110101199006181234', submittedAt: db.kyc.submittedAt ?? now, reviewedAt: status === 'pending' ? undefined : now };
  // 文案和后端模板 KYC_APPROVED / KYC_REJECTED 一致（基线迁移 V3）
  if (status === 'approved') pushNotification('kyc', ['身份认证已通过', 'Identity verified'], [KYC_APPROVED_BODY.zh, KYC_APPROVED_BODY.en]);
  if (status === 'rejected')
    pushNotification(
      'kyc',
      ['身份认证未通过', 'Identity verification not approved'],
      [
        `您的身份认证未通过，请到"身份认证"页面按原因修改后重新提交。\n驳回原因：${REJECT_REASON.zh}`,
        `Your identity verification was not approved. Please fix the issue on the Identity verification page and submit again.\nReason: ${REJECT_REASON.en}`,
      ],
    );
}

/** 身份认证通过的站内信正文（和后端模板 KYC_APPROVED 一致） */
export const KYC_APPROVED_BODY = {
  zh: '您的身份认证已通过，提现已开通。',
  en: 'Your identity has been verified. Withdrawals are now available.',
};

export function protoKycStatus(): KycStatus {
  return db.kyc.status;
}

// ---------- 通知偏好 ----------
export function getNotifyPrefs(): Promise<NotifyPrefs> {
  return respond(() => ({ ...db.prefs }));
}

export function updateNotifyPrefs(p: NotifyPrefs): Promise<NotifyPrefs> {
  return mutate(() => {
    db.prefs = { ...p };
    return { ...db.prefs };
  });
}

// ---------- 站内信 ----------
function view(n: StoredNotification, lang: Lang): NotificationItem {
  return {
    id: n.id,
    kind: n.kind,
    title: lang === 'en' ? n.title.en : lang === 'zh-TW' ? n.title.tw || n.title.zh : n.title.zh,
    body: lang === 'en' ? n.body.en : lang === 'zh-TW' ? n.body.tw || n.body.zh : n.body.zh,
    read: n.read,
    createdAt: n.createdAt,
    link: n.link ?? null,
  };
}

let currentLang: Lang = 'zh-CN';
export function setMockLang(l: Lang): void {
  currentLang = l;
}

export function listNotifications(lang: Lang, page: number, pageSize: number): Promise<Page<NotificationItem>> {
  return respond(
    () => {
      const sorted = [...db.notifications].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return { items: sorted.slice((page - 1) * pageSize, page * pageSize).map((n) => view(n, lang)), total: sorted.length, page, pageSize };
    },
    () => ({ items: [], total: 0, page, pageSize }),
  );
}

export function unreadCount(): Promise<number> {
  return respond(
    () => db.notifications.filter((n) => !n.read).length,
    () => 0,
  );
}

export function markRead(id: string): Promise<void> {
  return mutate(() => {
    const n = db.notifications.find((x) => x.id === id);
    if (n) n.read = true;
  });
}

export function markAllRead(): Promise<void> {
  return mutate(() => {
    db.notifications.forEach((n) => (n.read = true));
  });
}

/** 生成一条站内信并推送（成交、强平、出金、KYC 等事件都走这里） */
export function pushNotification(kind: NotificationKind, title: [string, string], body: [string, string]): void {
  const n: StoredNotification = {
    id: nextId('N'),
    kind,
    title: { zh: title[0], en: title[1] },
    body: { zh: body[0], en: body[1] },
    read: false,
    createdAt: new Date().toISOString(),
  };
  db.notifications.unshift(n);
  emit({ type: 'notification', item: view(n, currentLang) });
}
