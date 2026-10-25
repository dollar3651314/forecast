import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import zh from './zh-CN.json';
import en from './en.json';
import tw from './zh-TW.json';
import { translate } from './index';
import { detectLang } from './lang';
import { BRAND_NAME } from '../lib/brand';

/** 启用检查的旧品牌名（scripts/brand-check.json，和构建产物检查共用一份） */
const cfg = JSON.parse(readFileSync(join(__dirname, '../../../../scripts/brand-check.json'), 'utf8')) as { oldBrands: { pattern: string; enabled: boolean }[] };
const OLD_BRAND = new RegExp(cfg.oldBrands.filter((b) => b.enabled).map((b) => b.pattern).join('|'), 'i');

function flatten(o: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' ? flatten(v as Record<string, unknown>, `${prefix}${k}.`) : [`${prefix}${k}`]));
}
const value = (o: Record<string, unknown>, k: string) => String(k.split('.').reduce<unknown>((x, p) => (x as Record<string, unknown>)[p], o));

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return walk(p);
    return /\.(ts|tsx)$/.test(f) && !/\.test\./.test(f) ? [p] : [];
  });
}

describe('官网翻译文件（AC2-05）', () => {
  const zhKeys = flatten(zh).sort();
  const enKeys = flatten(en).sort();

  it('简体、繁体、英文的键完全一致（RQ-18）', () => {
    expect(enKeys).toEqual(zhKeys);
    expect(flatten(tw).sort()).toEqual(zhKeys);
  });

  it('翻译值都不为空', () => {
    expect(zhKeys.filter((k) => value(zh, k).trim() === '')).toEqual([]);
    expect(enKeys.filter((k) => value(en, k).trim() === '')).toEqual([]);
  });

  it('英文里没有中文残留，中文里的占位符和英文一致', () => {
    expect(enKeys.filter((k) => /[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]/.test(value(en, k)))).toEqual([]);
    const ph = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
    expect(zhKeys.filter((k) => ph(value(zh, k)).join() !== ph(value(en, k)).join())).toEqual([]);
  });

  it('没有旧品牌名，品牌名不写死（用 {brand}，src/lib/brand.ts），也不写牌照号', () => {
    for (const d of [zh, en]) {
      const all = flatten(d).map((k) => value(d, k)).join('\n');
      expect(all).not.toMatch(OLD_BRAND);
      expect(all).not.toContain(BRAND_NAME);
      expect(all).not.toMatch(/licen[cs]e (no|number)|牌照号/i);
    }
  });

  it('代码里用到的翻译键都存在（静态键 + 按分类、编号拼出来的键）', () => {
    const used = new Set<string>();
    for (const f of walk(join(__dirname, '..'))) {
      for (const m of readFileSync(f, 'utf8').matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'/g)) used.add(m[1]);
    }
    for (const k of ['football', 'nba', 'crypto', 'macro']) used.add(`cats.${k}`).add(`cats.${k}D`);
    for (const k of ['s1', 's2', 's3', 's4']) used.add(`safe.${k}t`).add(`safe.${k}d`);
    for (const k of ['s1', 's2', 's3']) used.add(`steps.${k}t`).add(`steps.${k}d`);
    for (let n = 1; n <= 4; n++) used.add(`faq.q${n}`).add(`faq.a${n}`);
    const keys = new Set(zhKeys);
    expect([...used].filter((k) => !keys.has(k))).toEqual([]);
  });

  it('translate 替换占位符（{brand} 自动换成品牌名），键不存在时返回键本身', () => {
    expect(translate('en', 'footer.contact', { email: 'a@example.com' })).toBe('Contact us: a@example.com');
    expect(translate('en', 'footer.copyright')).toBe(`© ${BRAND_NAME}`);
    expect(translate('zh-CN', 'no.such.key')).toBe('no.such.key');
  });
});

describe('默认语言跟随浏览器（第 10 节 W6）', () => {
  it('记住的语言优先', () => {
    expect(detectLang('en', ['zh-CN'])).toBe('en');
    expect(detectLang('zh-CN', ['en-US'])).toBe('zh-CN');
  });
  it('浏览器首选语言：繁体地区用繁体，其他 zh 用简体，否则英文', () => {
    expect(detectLang(null, ['zh-CN', 'en'])).toBe('zh-CN');
    expect(detectLang(null, ['zh-TW'])).toBe('zh-TW');
    expect(detectLang(null, ['zh-HK'])).toBe('zh-TW');
    expect(detectLang('zh-TW', ['en'])).toBe('zh-TW');
    expect(detectLang(null, ['zh-Hans-CN'])).toBe('zh-CN');
    expect(detectLang(null, ['zu'])).toBe('en');
    expect(detectLang(null, [''], '')).toBe('en');
    expect(detectLang(null, ['zh'])).toBe('zh-CN');
    expect(detectLang(null, ['en-US', 'zh-CN'])).toBe('en');
    expect(detectLang(null, ['ja-JP'])).toBe('en');
    expect(detectLang(null, [], 'zh-CN')).toBe('zh-CN');
    expect(detectLang(null, undefined, undefined)).toBe('en');
    expect(detectLang('fr', ['zh-CN'])).toBe('zh-CN');
  });
});
