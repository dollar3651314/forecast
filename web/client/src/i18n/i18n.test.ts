import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import zh from './zh-CN.json';
import en from './en.json';
import tw from './zh-TW.json';

function flatten(o: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' ? flatten(v as Record<string, unknown>, `${prefix}${k}.`) : [`${prefix}${k}`]));
}

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return walk(p);
    return /\.(ts|tsx)$/.test(f) && !/\.test\./.test(f) ? [p] : [];
  });
}

describe('i18n', () => {
  const zhKeys = flatten(zh).sort();
  const enKeys = flatten(en).sort();
  const twKeys = flatten(tw).sort();

  it('简体、繁体、英文三份翻译文件的键完全一致（RQ-18、AC-53）', () => {
    expect(enKeys).toEqual(zhKeys);
    expect(twKeys).toEqual(zhKeys);
  });

  it('翻译值不为空（个别占位键除外）', () => {
    const allowEmpty = new Set(['withdraw.kycNeed.approved']);
    const empty = (o: Record<string, unknown>) => flatten(o).filter((k) => !allowEmpty.has(k) && k.split('.').reduce<unknown>((x, p) => (x as Record<string, unknown>)[p], o) === '');
    expect(empty(zh)).toEqual([]);
    expect(empty(en)).toEqual([]);
    expect(empty(tw)).toEqual([]);
  });

  it('英文翻译里没有中文残留（AC-27）', () => {
    const withCjk = flatten(en).filter((k) => /[一-鿿]/.test(String(k.split('.').reduce<unknown>((x, p) => (x as Record<string, unknown>)[p], en))));
    expect(withCjk).toEqual([]);
  });

  it('代码里用到的静态翻译键都存在', () => {
    const src = join(__dirname, '..');
    const used = new Set<string>();
    for (const f of walk(src)) {
      const text = readFileSync(f, 'utf8');
      for (const m of text.matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'/g)) used.add(m[1]);
      for (const m of text.matchAll(/'((?:validation|errors)\.[a-zA-Z]+)'/g)) used.add(m[1]);
    }
    const keys = new Set(zhKeys);
    expect([...used].filter((k) => !keys.has(k))).toEqual([]);
  });
});
