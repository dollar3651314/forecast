#!/usr/bin/env node
/**
 * 生成台湾用语词组表 backend/src/main/resources/content/tw-phrases.txt（forecast S18，P3 + P9）。
 * 内容来自 OpenCC 的台湾用语词典 TWPhrasesIT、TWPhrasesName、TWPhrasesOther（Apache-2.0，https://github.com/BYVoid/OpenCC），
 * 取 opencc-js 1.0.5 里的同一份（和 S15 的 s2tw.txt 同一个版本）。opencc-js 的 cn → twp 是两遍：先简转繁（STCharacters + STPhrases），
 * 再按 TWVariants + TWPhrases 换成台湾用语；后端 content/check/Hant 也是两遍：第一遍用 s2tw.txt（简转繁 + TWVariants），第二遍用本表。
 * 因为第一遍已经换过 TWVariants，词组的键同时收"原样"和"按 TWVariants 换过字"两种写法。
 * 用法（不进 CI，词典升级时手动跑一次）：
 *   npm i --prefix /tmp/occ opencc-js@1.0.5 && node scripts/gen-tw-phrases.mjs /tmp/occ/node_modules/opencc-js > backend/src/main/resources/content/tw-phrases.txt
 */
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir) {
  console.error('用法：node scripts/gen-tw-phrases.mjs <opencc-js 目录>');
  process.exit(2);
}
const load = async (n) => (await import(pathToFileURL(join(dir, 'dist/esm-lib/dict', `${n}.js`)).href)).default;
const pairs = (d) => (typeof d === 'string' ? d.split('|').map((x) => x.split(' ')) : d);
const variants = new Map(pairs(await load('TWVariants')));
const out = new Map();
for (const n of ['TWPhrasesIT', 'TWPhrasesName', 'TWPhrasesOther']) {
  for (const [k, v] of pairs(await load(n))) {
    if (!k || !v || k === v) continue;
    out.set(k, v);
    const kv = [...k].map((c) => variants.get(c) ?? c).join('');
    if (kv !== k && !out.has(kv)) out.set(kv, v);
  }
}
const lines = [
  '# 台湾用语词组表（forecast S18，P3 + P9）：繁体（台湾用字）→ 台湾用语，例如 軟件 → 軟體、數據 → 資料、默認 → 預設。',
  '# 由 OpenCC 的 TWPhrasesIT、TWPhrasesName、TWPhrasesOther（Apache-2.0，https://github.com/BYVoid/OpenCC）用 scripts/gen-tw-phrases.mjs 从 opencc-js 1.0.5 生成，不要手改；',
  '# 要补充或纠正（例如"文件"在法律文件里不能换成"檔案"）改 tw-terms.txt（市场可以直接改），它优先于本表。格式：P 词组<TAB>台湾用语（最长匹配优先）。',
];
for (const [k, v] of [...out.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))) lines.push(`P\t${k}\t${v}`);
process.stdout.write(lines.join('\n') + '\n');
