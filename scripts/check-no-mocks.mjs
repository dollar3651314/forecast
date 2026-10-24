#!/usr/bin/env node
// 检查真实接口模式的前端构建产物里没有原型模拟层（src/mocks/）的代码。
// 用法（在 web/client 或 web/admin 目录下，npm run build 之后）：node ../../scripts/check-no-mocks.mjs [src/mocks] [dist]
// 做法：从模拟层源码里取出只在模拟层出现的字符串字面量（长度 >= 10，src 其他地方没有的），到 dist 的 .js 里找，
// 找到任何一个就失败。原型模式（VITE_API_MODE=mock）的构建不要跑这个检查。
import fs from 'node:fs';
import path from 'node:path';

const [mocksDir = 'src/mocks', distDir = 'dist'] = process.argv.slice(2);
const srcDir = path.dirname(path.resolve(mocksDir));

const walk = (d, pred) =>
  fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(d, e.name);
    return e.isDirectory() ? walk(p, pred) : pred(p) ? [p] : [];
  });
const literals = (text) =>
  [...text.matchAll(/'((?:[^'\\\n]|\\.){10,})'|"((?:[^"\\\n]|\\.){10,})"/g)].map((m) => m[1] ?? m[2]).filter((s) => !/[\\$]/.test(s));

const mocksAbs = path.resolve(mocksDir);
const mockFiles = walk(mocksAbs, (p) => /\.tsx?$/.test(p) && !/\.test\./.test(p));
const elsewhere = walk(srcDir, (p) => /\.(tsx?|json)$/.test(p) && !p.startsWith(mocksAbs + path.sep) && !/\.test\./.test(p))
  .map((p) => fs.readFileSync(p, 'utf8'))
  .join('\n');
const markers = [...new Set(mockFiles.flatMap((f) => literals(fs.readFileSync(f, 'utf8'))))].filter((s) => !elsewhere.includes(s));
const dist = walk(path.resolve(distDir), (p) => p.endsWith('.js'))
  .map((p) => fs.readFileSync(p, 'utf8'))
  .join('\n');
const found = markers.filter((s) => dist.includes(s));

if (markers.length === 0) {
  console.error('没有找到模拟层特有的字符串，检查不了（目录写错了？）');
  process.exit(2);
}
if (found.length > 0) {
  console.error(`构建产物里有 ${found.length} 个模拟层特有的字符串（共 ${markers.length} 个），模拟层被打包进来了，例如：`);
  for (const s of found.slice(0, 10)) console.error(`  ${s}`);
  process.exit(1);
}
console.log(`通过：模拟层特有的 ${markers.length} 个字符串都不在构建产物里`);
