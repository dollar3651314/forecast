/** 中英文翻译的键必须完全一致，代码里用到的静态键都必须存在（AC-27） */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import zh from './zh-CN.json';
import en from './en.json';

type Tree = { [k: string]: string | Tree };

function flatten(obj: Tree, prefix = ''): Record<string, string> {
  return Object.entries(obj).reduce<Record<string, string>>((acc, [k, v]) => {
    const key = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') acc[key] = v;
    else Object.assign(acc, flatten(v, key));
    return acc;
  }, {});
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return sourceFiles(p);
    return /\.(ts|tsx)$/.test(f) && !/\.test\./.test(f) ? [p] : [];
  });
}

describe('i18n', () => {
  const zhFlat = flatten(zh as Tree);
  const enFlat = flatten(en as Tree);

  it('zh-CN 和 en 的键完全一致', () => {
    expect(Object.keys(enFlat).sort()).toEqual(Object.keys(zhFlat).sort());
  });

  it('没有空翻译', () => {
    const empty = [...Object.entries(zhFlat), ...Object.entries(enFlat)].filter(([, v]) => !v.trim()).map(([k]) => k);
    expect(empty).toEqual([]);
  });

  it('英文翻译里没有中文残留', () => {
    const withChinese = Object.entries(enFlat).filter(([, v]) => /[一-鿿]/.test(v)).map(([k]) => k);
    expect(withChinese).toEqual([]);
  });

  it('代码里用到的静态翻译键都存在', () => {
    const root = join(__dirname, '..');
    const missing: string[] = [];
    for (const file of sourceFiles(root)) {
      const text = readFileSync(file, 'utf-8');
      for (const m of text.matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'/g)) if (!(m[1] in zhFlat)) missing.push(`${file}: ${m[1]}`);
    }
    expect(missing).toEqual([]);
  });
});
