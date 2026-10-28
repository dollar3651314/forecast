/**
 * 等候页确认邮件的文字（forecast S19，P12）：和后端通知模板 WAITLIST_CONFIRM **一字不差**——简中、英文来自迁移 V26，
 * 繁中来自 S18 改成台湾用语的 V31（郵箱 → 電子信箱）。test/mailTexts.test.ts 从迁移脚本里读出原文逐字比对，改了一边另一边会失败。
 * 变量 {confirm}、{unsubscribe} 是确认、退订链接。品牌名上线前沿用 Quick Come（项目地图约定 17）。
 */
export type Lang = 'zh-TW' | 'zh-CN' | 'en';

export const CONFIRM_MAIL: Record<Lang, { subject: string; body: string }> = {
  'zh-CN': {
    subject: '请确认订阅 Quick Come 上线通知',
    body:
      '你在 Quick Come 的数据页留下了这个邮箱。请打开下面的链接，点"确认订阅"（7 天内有效）：\n{confirm}\n\n确认以后，我们只会在正式上线时给你发一封通知，不发其他邮件。\n如果不是你本人提交的，忽略这封邮件即可，不会订阅。\n不想再收到：{unsubscribe}',
  },
  en: {
    subject: 'Confirm your Quick Come launch notice',
    body:
      'This email address was left on the Quick Come data page. Open the link below and click "Confirm" (valid for 7 days):\n{confirm}\n\nOnce confirmed, we will send you a single email when we officially launch, and nothing else.\nIf this was not you, just ignore this email and you will not be subscribed.\nUnsubscribe: {unsubscribe}',
  },
  'zh-TW': {
    subject: '請確認訂閱 Quick Come 上線通知',
    body:
      '你在 Quick Come 的資料頁留下了這個電子信箱。請打開下面的連結，點「確認訂閱」（7 天內有效）：\n{confirm}\n\n確認以後，我們只會在正式上線時給你發一封通知，不發其他郵件。\n如果不是你本人提交的，忽略這封郵件即可，不會訂閱。\n不想再收到：{unsubscribe}',
  },
};

export function render(tpl: string, vars: Record<string, string>): string {
  return tpl.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? vars[k] : m));
}
