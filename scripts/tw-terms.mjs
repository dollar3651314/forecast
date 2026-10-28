#!/usr/bin/env node
/**
 * 台湾用语转换（forecast S18，P3 + P9）：和后端 content/check/Hant 同一套规则、同一组表（backend/src/main/resources/content/）：
 *   自定义词表 tw-terms.txt（简体 → 台湾繁体，优先）→ s2tw.txt（OpenCC 简转繁）→ tw-phrases.txt（OpenCC 台湾用语）。
 * 用法（仓库根目录，不用装依赖）：
 *   node scripts/tw-terms.mjs "美联储加息 25 个基点"        把简体转成台湾繁体（看自动化程序会写成什么样）
 *   node scripts/tw-terms.mjs --localize "預設的數據"        已经是繁体的文字只换台湾用语（研发做客户端、官网繁体文案替换时用）
 *   node scripts/tw-terms.mjs --test                         检查词表格式（两列、没有重复）+ 跑两边共用的用例 backend/src/test/resources/content/tw-vectors.json
 * 市场改了 tw-terms.txt 以后跑一次 --test；CI 的后端测试（ContentChecksTest）也跑同一组用例。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const RES = join(ROOT, 'backend/src/main/resources/content');

function table(file, plain) {
  const chars = new Map();
  const phrases = new Map();
  let max = 1;
  const problems = [];
  readFileSync(join(RES, file), 'utf8')
    .split('\n')
    .forEach((line, n) => {
      if (!line.trim() || line.startsWith('#')) return;
      const p = line.split('\t');
      if (plain) {
        if (p.length < 2 || !p[0] || !p[1]) return problems.push(`${file} 第 ${n + 1} 行：要写成"简体<TAB>繁体"`);
        if (phrases.has(p[0])) problems.push(`${file} 第 ${n + 1} 行：${p[0]} 重复`);
        phrases.set(p[0], p[1]);
        max = Math.max(max, p[0].length);
      } else if (p.length === 3) {
        if (p[0] === 'C' && p[1].length === 1) chars.set(p[1], p[2]);
        else if (p[0] === 'P') {
          phrases.set(p[1], p[2]);
          max = Math.max(max, p[1].length);
        }
      }
    });
  return { chars, phrases, max, problems };
}

function replace(t, s) {
  let out = '';
  let i = 0;
  while (i < s.length) {
    let len = 0;
    for (let l = Math.min(t.max, s.length - i); l >= 2; l--) {
      if (t.phrases.has(s.slice(i, i + l))) {
        len = l;
        break;
      }
    }
    if (len) {
      out += t.phrases.get(s.slice(i, i + len));
      i += len;
      continue;
    }
    const c = s[i];
    out += t.chars.get(c) ?? t.phrases.get(c) ?? c;
    i++;
  }
  return out;
}

/** 按词表最长匹配切段：命中的直接换成词表的值，其余交给 rest() */
function withTerms(terms, s, rest) {
  let out = '';
  let gap = '';
  let i = 0;
  while (i < s.length) {
    let len = 0;
    for (let l = Math.min(terms.max, s.length - i); l >= 1; l--) {
      if (terms.phrases.has(s.slice(i, i + l))) {
        len = l;
        break;
      }
    }
    if (len) {
      if (gap) out += rest(gap);
      gap = '';
      out += terms.phrases.get(s.slice(i, i + len));
      i += len;
    } else {
      gap += s[i];
      i++;
    }
  }
  if (gap) out += rest(gap);
  return out;
}

const TERMS = table('tw-terms.txt', true);
const ST = table('s2tw.txt', false);
const TW = table('tw-phrases.txt', false);

/** 简体 → 台湾繁体（和 Hant.convert 一样） */
export function convert(s) {
  return withTerms(TERMS, s, (g) => replace(TW, replace(ST, g)));
}

/** 词表的键转成繁体以后的版本（给 localize 用：已经是繁体的文字也能命中词表） */
let localTerms;
function termsHant() {
  if (!localTerms) {
    const phrases = new Map();
    let max = 1;
    for (const [k, v] of TERMS.phrases) {
      const kh = replace(ST, k);
      // 键按 s2tw 转成繁体字形（例如 账 → 賬、美联储 → 美聯儲），已经是繁体的文字才能命中
      if (!phrases.has(kh)) phrases.set(kh, v);
      max = Math.max(max, kh.length);
    }
    localTerms = { chars: new Map(), phrases, max };
  }
  return localTerms;
}

/** 已经是繁体（台湾用字）的文字：只换台湾用语（词表 + OpenCC 的 TWPhrases），不重新转字形 */
export function localize(s) {
  return withTerms(termsHant(), s, (g) => replace(TW, g));
}

function selfTest() {
  const problems = [...TERMS.problems];
  const vectors = JSON.parse(readFileSync(join(ROOT, 'backend/src/test/resources/content/tw-vectors.json'), 'utf8'));
  for (const v of vectors.convert) {
    const got = convert(v.in);
    if (got !== v.out) problems.push(`convert(${v.in}) = ${got}，应为 ${v.out}`);
  }
  for (const v of vectors.localize || []) {
    const got = localize(v.in);
    if (got !== v.out) problems.push(`localize(${v.in}) = ${got}，应为 ${v.out}`);
  }
  if (problems.length) {
    console.error(`台湾用语检查没有通过（${problems.length} 项）：`);
    for (const p of problems) console.error(`  ✗ ${p}`);
    process.exit(1);
  }
  console.log(`台湾用语检查通过：词表 ${TERMS.phrases.size} 条，用例 ${vectors.convert.length + (vectors.localize || []).length} 个`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const [a, b] = process.argv.slice(2);
  if (a === '--test') selfTest();
  else if (a === '--localize') console.log(localize(b ?? ''));
  else if (a) console.log(convert(a));
  else {
    console.error('用法：node scripts/tw-terms.mjs "简体文字" | --localize "繁体文字" | --test');
    process.exit(2);
  }
}
