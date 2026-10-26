import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { LEGAL } from './legal';
import { LEGAL_PAGES } from '../lib/route';
import { BRAND_NAME } from '../lib/brand';

/** 启用检查的旧品牌名（scripts/brand-check.json，和构建产物检查共用一份） */
const cfg = JSON.parse(readFileSync(join(__dirname, '../../../../scripts/brand-check.json'), 'utf8')) as { oldBrands: { pattern: string; enabled: boolean }[] };
const OLD_BRAND = new RegExp(cfg.oldBrands.filter((b) => b.enabled).map((b) => b.pattern).join('|'), 'i');

const CJK = /[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]/;
const text = (d: (typeof LEGAL)['terms']['en']) => [d.title, ...d.intro, ...d.sections.flatMap((s) => [s.h, ...s.p])].join('\n');

/** 不能出现的诱导收益、和事实不符的说法（市场策略 F-12、F-15；和后端 S9NotifyFlowTest.BANNED、后台 BANNED_WORDS 同一类） */
const BANNED = /稳赚|必中|保本|翻倍|胜者瓜分|最后入场|别错过|恭喜|穩賺|保證|guaranteed|risk-free|sure win|double your/i;

describe('法律页（PM 正文，forecast S11）', () => {
  it.each(LEGAL_PAGES)('%s：三种语言的章节数和每节段落数一致', (p) => {
    const zh = LEGAL[p]['zh-CN'];
    for (const other of [LEGAL[p].en, LEGAL[p]['zh-TW']]) {
      expect(other.sections.map((s) => s.p.length)).toEqual(zh.sections.map((s) => s.p.length));
      expect(other.intro.length).toBe(zh.intro.length);
    }
    expect(LEGAL[p]['zh-TW']).not.toBe(zh);
  });

  it.each(LEGAL_PAGES)('%s：没有诱导收益的说法', (p) => {
    for (const l of ['zh-CN', 'zh-TW', 'en'] as const) expect(text(LEGAL[p][l])).not.toMatch(BANNED);
  });

  it.each(LEGAL_PAGES)('%s：英文没有中文残留', (p) => {
    expect(CJK.test(text(LEGAL[p].en))).toBe(false);
  });

  it.each(LEGAL_PAGES)('%s：不写牌照、监管、公司主体，这些位置写"待提供"', (p) => {
    const zh = text(LEGAL[p]['zh-CN']);
    const en = text(LEGAL[p].en);
    expect(zh).toContain('待提供');
    expect(text(LEGAL[p]['zh-TW'])).toContain('待提供');
    expect(en).toMatch(/to be provided|to be determined/);
    expect(zh).not.toMatch(/监管机构|持有.{0,6}牌照|牌照号|有限公司/);
    expect(en).not.toMatch(/regulated by|licensed by|licen[cs]e number/i);
    expect(en).not.toMatch(/ Ltd\b| Limited\b| Inc\./);
    expect(zh + en).not.toMatch(OLD_BRAND);
    expect(zh).toContain(BRAND_NAME);
    expect(en).toContain(BRAND_NAME);
  });

  it('写到的事实和系统一致（三条链、最小充值 1 USDT、新地址 24 小时生效；S11：最小提现 100、冷静期 2 小时、默认抽成 5%、年满 18 周岁）', () => {
    const all = LEGAL_PAGES.map((p) => text(LEGAL[p]['zh-CN'])).join('\n');
    expect(all).toMatch(/ERC20.*TRC20.*BSC/);
    expect(all).toContain('1 USDT');
    expect(all).toContain('24 小时');
    expect(all).toContain('100 USDT');
    expect(all).toContain('2 小时冷静期');
    expect(all).toContain('盈利的 5%');
    expect(all).toContain('18 周岁');
  });
});
