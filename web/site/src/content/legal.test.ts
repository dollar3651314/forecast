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

describe('法律页（占位正文，forecast S1）', () => {
  it.each(LEGAL_PAGES)('%s：中英文章节数和每节段落数一致', (p) => {
    const zh = LEGAL[p]['zh-CN'];
    const en = LEGAL[p].en;
    expect(en.sections.map((s) => s.p.length)).toEqual(zh.sections.map((s) => s.p.length));
    expect(en.intro.length).toBe(zh.intro.length);
  });

  it.each(LEGAL_PAGES)('%s：英文没有中文残留', (p) => {
    expect(CJK.test(text(LEGAL[p].en))).toBe(false);
  });

  it.each(LEGAL_PAGES)('%s：不写牌照、监管、公司主体，这些位置写"待提供"', (p) => {
    const zh = text(LEGAL[p]['zh-CN']);
    const en = text(LEGAL[p].en);
    expect(zh).toContain('待提供');
    expect(en).toMatch(/to be provided|to be determined/);
    expect(zh).not.toMatch(/监管机构|持有.{0,6}牌照|牌照号|有限公司/);
    expect(en).not.toMatch(/regulated by|licensed by|licen[cs]e number/i);
    expect(en).not.toMatch(/ Ltd\b| Limited\b| Inc\./);
    expect(zh + en).not.toMatch(OLD_BRAND);
    expect(zh).toContain(BRAND_NAME);
    expect(en).toContain(BRAND_NAME);
  });

  it('写到的事实和系统一致（三条链、最小充值 1 USDT、新地址 24 小时生效）', () => {
    const all = LEGAL_PAGES.map((p) => text(LEGAL[p]['zh-CN'])).join('\n');
    expect(all).toMatch(/ERC20.*TRC20.*BSC/);
    expect(all).toContain('1 USDT');
    expect(all).toContain('24 小时');
  });
});
