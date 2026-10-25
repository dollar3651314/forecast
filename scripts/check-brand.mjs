#!/usr/bin/env node
// 旧品牌名和复制残留检查（forecast S1，技术方案/v1-架构方案.md 第 1.4 节 ⑦）。检查哪些名字、排除哪些文档都在 scripts/brand-check.json。
// 用法：
//   node ../../scripts/check-brand.mjs [dist]   在 web/client、web/admin、web/site 目录下 npm run build 之后：检查构建产物（只查旧品牌名）
//   node scripts/check-brand.mjs --repo         在仓库根目录：用 git grep 查所有纳入版本管理的文件（旧品牌名 + 复制残留，大小写不敏感）
// 产物会整个发到客户的浏览器，所以注释、代码里的内部名字出现在产物里也算。后端发给客户的文字由后端测试 BrandTextTest 检查（读同一份配置）。
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const config = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'brand-check.json'), 'utf8'));
const brands = config.oldBrands.filter((b) => b.enabled);
const off = config.oldBrands.filter((b) => !b.enabled).map((b) => b.name);
if (off.length > 0) console.log(`未启用的检查（见 scripts/brand-check.json 的说明）：${off.join('、')}`);

const args = process.argv.slice(2);

if (args[0] === '--repo') {
  const items = [...brands, ...config.residue];
  const pattern = items.map((i) => i.pattern).join('|');
  let out = '';
  try {
    out = execFileSync('git', ['grep', '-n', '-I', '-i', '-E', pattern, '--', '.', ...config.excludePaths.map((p) => `:!${p}`)], { encoding: 'utf8' });
  } catch (e) {
    // git grep 没找到时退出码是 1
    if (e.status !== 1) throw e;
  }
  const lines = out.split('\n').filter(Boolean);
  if (lines.length > 0) {
    console.error(`仓库里有旧品牌名或复制残留（${lines.length} 处）：${items.map((i) => i.name).join('、')}`);
    for (const l of lines.slice(0, 40)) console.error(`  ${l.slice(0, 200)}`);
    process.exit(1);
  }
  console.log(`通过：仓库里没有 ${items.map((i) => i.name).join('、')}（排除 ${config.excludePaths.length} 个文档）`);
  process.exit(0);
}

const [distDir = 'dist'] = args;
const OLD_BRAND = new RegExp(brands.map((b) => b.pattern.replaceAll(' ?', '\\s*')).join('|'), 'i');
const TEXT = /\.(js|mjs|css|html|webmanifest|json|svg|txt|map)$/i;

const root = path.resolve(distDir);
if (!fs.existsSync(root)) {
  console.error(`找不到构建产物目录 ${root}（先 npm run build）`);
  process.exit(2);
}
const walk = (d) =>
  fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(d, e.name);
    return e.isDirectory() ? walk(p) : TEXT.test(p) ? [p] : [];
  });

const files = walk(root);
const hits = [];
for (const f of files) {
  const text = fs.readFileSync(f, 'utf8');
  const m = text.match(OLD_BRAND);
  if (m) {
    const at = m.index ?? 0;
    hits.push(`${path.relative(root, f)}：…${text.slice(Math.max(0, at - 40), at + 40).replace(/\s+/g, ' ')}…`);
  }
}
if (files.length === 0) {
  console.error('构建产物里没有文本文件，检查不了（目录写错了？）');
  process.exit(2);
}
if (hits.length > 0) {
  console.error(`构建产物里有旧品牌名（${hits.length} 个文件），客户看得到。品牌名只放在 src/lib/brand.ts：`);
  for (const h of hits.slice(0, 20)) console.error(`  ${h}`);
  process.exit(1);
}
console.log(`通过：${files.length} 个产物文件里没有旧品牌名（${brands.map((b) => b.name).join('、')}）`);
