/**
 * 市场管理、裁决台、结算等 forecast 页面共用的显示规则：三语文字按后台语言取（后台只有中英文，中文界面显示简体）、UTC 时间、状态颜色、
 * 时限（还剩 / 已超时）、通知模板里不能出现的说法（市场策略 F-12、F-15）。
 */
import type { I18nText, MarketStatus } from '../api/fcTypes';
import type { PillTone } from '../components/Pill';

export const textOf = (t: I18nText | undefined | null, lang: string): string => (t ? (lang.startsWith('zh') ? t.zhCN : t.en) || t.en || t.zhCN : '');

const pad = (n: number) => String(n).padStart(2, '0');

/** "11-01 14:55 UTC"；withYear 时 "2026-11-01 14:55 UTC" */
export function utc(iso: string | null | undefined, withYear = false): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return `${withYear ? `${d.getUTCFullYear()}-` : ''}${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`;
}

/**
 * 场次时刻的宽松读入（S12c 测试环境反馈："1:00" 页面显示北京 09:00、后端却报"场次格式不正确"）。
 * 和后端 AutogenService.parseSlot 是同一套规则（后端为准，这里只是提前提示）：时 1～2 位，分可省（= 00）或 1～2 位，冒号半角或全角，
 * 两边和冒号两边可以有空格；"1:00"、"1:0"、"1"、" 01:00 " 都是 01:00。时 0～23、分 0～59。
 * 空白字符集固定成下面这几个（后端 SLOT_WS 同一组），不用 \s / trim，两种语言对 Unicode 空白的理解不一样。
 */
const SLOT_WS = '[ \\t\\r\\n\\u00A0\\u3000]*';
const SLOT_TIME = new RegExp(`^${SLOT_WS}([0-9]{1,2})(?:${SLOT_WS}[:：]${SLOT_WS}([0-9]{1,2}))?${SLOT_WS}$`);
const SLOT_DAYS = new RegExp(`^${SLOT_WS}[1-7](?:${SLOT_WS},${SLOT_WS}[1-7]){0,6}${SLOT_WS}$`);
const SLOT_BLANK = new RegExp(`^${SLOT_WS}$`);

/** 场次的字段错误码，和后端一致；后台翻译在 apiFieldCode 下 */
export type SlotProblem = 'required' | 'timeFormat' | 'outOfRange' | 'invalidDays' | 'hourOnly' | 'quarterOnly' | 'duplicate';

/** 时刻部分 → 标准 "HH:mm"；不合法给错误码 */
export function normalizeSlotTime(raw: string): { time: string; problem?: undefined } | { time?: undefined; problem: SlotProblem } {
  if (SLOT_BLANK.test(raw ?? '')) return { problem: 'required' };
  const m = SLOT_TIME.exec(raw);
  if (!m) return { problem: 'timeFormat' };
  const h = Number(m[1]);
  const min = m[2] === undefined ? 0 : Number(m[2]);
  if (h > 23 || min > 59) return { problem: 'outOfRange' };
  return { time: `${pad(h)}:${pad(min)}` };
}

/** UTC 的时刻换成北京时间（需求第 6.2 节：场次表页面同时显示北京时间）。看不懂的时刻显示 "—"，不会出现"页面显示正常、后端却拒绝" */
export function beijing(hhmm: string): string {
  const n = normalizeSlotTime(hhmm);
  if (!n.time) return '—';
  const [h, m] = n.time.split(':').map(Number);
  return `${pad((h + 8) % 24)}:${pad(m)}`;
}

/**
 * 场次表的一项（S13，MF-3）："HH:mm" 每天，"HH:mm@1,6,7" 只在这几天（ISO 星期 1 = 周一 … 7 = 周日，按 UTC 日期）；
 * 后端 AutogenService.parseSlot / format 同一个格式。days 为空 = 每天。time 是输入框里的原样文字（失焦或保存时才规范化）。
 */
export interface SlotSpec {
  time: string;
  days: number[];
}

export function parseSlot(s: string): SlotSpec {
  const at = s.indexOf('@');
  const time = at < 0 ? s : s.slice(0, at);
  const d = at < 0 ? '' : s.slice(at + 1);
  const days = d ? [...new Set(d.split(',').map((x) => Number(x.trim())).filter((x) => x >= 1 && x <= 7))].sort() : [];
  return { time: time.trim(), days: days.length === 7 ? [] : days };
}

export function formatSlot(s: SlotSpec): string {
  const days = [...new Set(s.days)].sort();
  return days.length === 0 || days.length === 7 ? s.time.trim() : `${s.time.trim()}@${days.join(',')}`;
}

/** 一项场次规范成后端保存的标准写法（"1:00@7,6" → "01:00@6,7"）；时刻不合法时原样返回（留给校验报错） */
export function normalizeSlot(s: string): string {
  const p = parseSlot(s);
  const n = normalizeSlotTime(p.time);
  return n.time ? formatSlot({ time: n.time, days: p.days }) : s;
}

/**
 * 场次表逐行检查，返回每行的错误码（null = 没问题），和后端 AutogenService.parse 的循环一样：
 * 先看时刻能不能读、在不在范围，再看 1 小时是不是整点、15 分钟是不是 15 的倍数，最后看和前面的行有没有重复。
 */
export function slotProblems(slots: string[], period: string): (SlotProblem | null)[] {
  const seen = new Set<string>();
  return slots.map((s) => {
    const at = s.indexOf('@');
    const n = normalizeSlotTime(at < 0 ? s : s.slice(0, at));
    if (n.time === undefined) return n.problem;
    if (at >= 0) {
      const d = s.slice(at + 1);
      const list = d.match(/[1-7]/g) ?? [];
      if (!SLOT_DAYS.test(d) || new Set(list).size !== list.length) return 'invalidDays';
    }
    const min = Number(n.time.slice(3));
    if (period === '1H' && min !== 0) return 'hourOnly';
    if (period === '15M' && min % 15 !== 0) return 'quarterOnly';
    if (seen.has(n.time)) return 'duplicate';
    seen.add(n.time);
    return null;
  });
}

/** 北京时间比 UTC 早 8 小时：UTC 16:00 以后的场次在北京是第二天（星期要 +1） */
export function beijingNextDay(hhmm: string): boolean {
  const n = normalizeSlotTime(hhmm);
  return !!n.time && Number(n.time.slice(0, 2)) >= 16;
}

/** ISO → 后台日期时间输入框里的 "YYYY-MM-DDTHH:mm"（UTC） */
export function toInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

/** 输入框的 "YYYY-MM-DDTHH:mm"（按 UTC 理解）→ ISO；空或不合法返回空串（后端报字段错误） */
export function fromInput(v: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v)) return '';
  return `${v}:00Z`;
}

export function statusTone(s: MarketStatus, submitted = false): PillTone {
  if (s === 'draft') return submitted ? 'pend' : 'muted';
  if (s === 'listed') return 'ok';
  if (s === 'paused') return 'warn';
  if (s === 'closed' || s === 'pending_resolution') return 'delay';
  if (s === 'publicizing' || s === 'disputed') return 'info';
  if (s === 'settled') return 'muted';
  return 'danger';
}

/** 剩余分钟数（负数表示已超时） */
export function minutesLeft(iso: string, now = Date.now()): number {
  return Math.round((Date.parse(iso) - now) / 60_000);
}

/** 市场管理的分组 → 后端的状态筛选（逗号分隔） */
export const TAB_STATUSES: Record<string, string | undefined> = {
  all: undefined,
  draft: 'draft',
  active: 'listed,paused',
  waiting: 'closed,pending_resolution,publicizing,disputed',
  ended: 'settled,cancelled,voided',
};

/** 市场策略 F-12、F-15：通知、卡片里不能出现的诱导收益、和事实不符的说法（后端 S9NotifyFlowTest 对初始模板做同样的检查） */
export const BANNED_WORDS = ['稳赚', '必中', '保本', '翻倍', '胜者瓜分', '最后入场', '别错过', '秒出', '全在链上', '链上公开', '私房钱', '恭喜', '穩賺', '勝者瓜分', '最後入場', '別錯過', 'guaranteed', 'risk-free', "don't miss", 'last chance', 'double your'];

export const bannedIn = (text: string): string[] => BANNED_WORDS.filter((w) => text.toLowerCase().includes(w.toLowerCase()));
