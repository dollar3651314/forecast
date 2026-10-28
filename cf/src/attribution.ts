/**
 * 来源参数（forecast S19，P14）：和后端 common/attribution/Attribution.java 同一套规则（S13，MF-1）——
 * 每个值只接受 [A-Za-z0-9._~-]，1～64 个字符（前后空白去掉），不合法的当成没有；start 是 Telegram 深链参数 s-<src>__c-<cmp>__r-<ref>，
 * 只补链接上没有的那几个；解不出来的 start 只保留原文。只用于来源统计，不给任何人奖励（MQ-5）。
 * 两边的结果用同一组例子测（test/attribution.test.ts 和后端 AttributionTest 的例子一致）。
 */
const VALUE = /^[A-Za-z0-9._~-]{1,64}$/;
const START = /^[A-Za-z0-9_-]{1,64}$/;

export interface Source {
  src: string | null;
  cmp: string | null;
  ref: string | null;
  start: string | null;
}

export function clean(v: unknown): string | null {
  if (typeof v !== 'string') return null;
  const s = v.trim();
  return VALUE.test(s) ? s : null;
}

export function sourceOf(src: unknown, cmp: unknown, ref: unknown, start: unknown): Source {
  let s = clean(src);
  let c = clean(cmp);
  let r = clean(ref);
  let st = typeof start === 'string' ? start.trim() : null;
  if (st !== null && !START.test(st)) st = null;
  if (st !== null) {
    for (const seg of st.split('__')) {
      if (seg.length < 3 || seg[1] !== '-') continue;
      const v = clean(seg.slice(2));
      if (v === null) continue;
      if (seg[0] === 's') s ??= v;
      else if (seg[0] === 'c') c ??= v;
      else if (seg[0] === 'r') r ??= v;
    }
  }
  return { src: s, cmp: c, ref: r, start: st };
}

/** 页面传来的 attribution（客户端 api/real/board.ts 只传一组：最近一次的来源，没有时第一次的） */
export function fromBody(a: unknown): Source {
  const o = a && typeof a === 'object' ? (a as Record<string, unknown>) : {};
  return sourceOf(o.src, o.cmp, o.ref, o.start);
}
