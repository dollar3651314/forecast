#!/usr/bin/env node
/**
 * 部署用的环境变量三处一致（forecast S12）：后端 application.yml、deploy/kit/docker-compose.yml、deploy/kit/env.example。
 * 来由：S10～S16 陆续加了变量，靠人记容易漏（项目地图第 8 节"新增环境变量"）。CI 的 checks 每次运行：
 *   1. application.yml 里的每个 ${FC_...}，要么在 compose 的 app 环境里传进容器，要么在下面 INTERNAL 里写明为什么不用传；
 *   2. compose 里引用的 .env 变量（${X:-...} / ${X:?...}），env.example 里都要有（Amos 才知道有这个变量）；
 *   3. env.example 里的每个变量，compose 里都要用到（CERT_EMAIL 只给 first-start.sh 申请证书用）；
 *   4. env.example 里的每个变量都标了【必填·自动】【验收前填】【可选】之一（标在同一段的注释里，对这一段后面的变量都有效）。
 * 用法：node scripts/check-deploy-env.mjs（仓库根目录）
 */
import { readFileSync } from 'node:fs';

const yml = readFileSync('backend/src/main/resources/application.yml', 'utf8');
const compose = readFileSync('deploy/kit/docker-compose.yml', 'utf8');
const example = readFileSync('deploy/kit/env.example', 'utf8');

/** application.yml 里有、但不用经过 compose 的 .env 传的变量 */
const INTERNAL = {
  FC_E2E_ENABLED: '只在端到端测试打开（e2e/compose.deployed.yml、e2e/start-backend.sh），测试环境不能设',
  FC_HTTP_PORT: '容器里固定 8080',
  FC_MANAGEMENT_PORT: '容器里固定（只在 127.0.0.1）',
  FC_REDIS_PORT: 'compose 里的 Redis 固定 6379',
  FC_STORAGE_DIR: 'deploy/app.Dockerfile 固定 /data/storage（compose 挂载 ./data/storage）',
  FC_VERSION: 'deploy/app.Dockerfile 构建时写入提交号',
};
/** env.example 里有、compose 里不用的变量 */
const NOT_IN_COMPOSE = { CERT_EMAIL: 'first-start.sh 申请证书、init-env.sh 生成推送联系方式时用' };
/** compose 里引用、但不归 .env 管的变量 */
const NOT_IN_EXAMPLE = { IMAGE_TAG: 'state/image.env（deploy.sh 写）', E2E_CERT_DIR: '只在端到端测试' };

const errors = [];
const uniq = (a) => [...new Set(a)];

const ymlVars = uniq([...yml.matchAll(/\$\{(FC_[A-Z0-9_]+)/g)].map((m) => m[1]));
// compose 里 app 环境的键（"      FC_XXX: ..."）
const composeKeys = new Set([...compose.matchAll(/^\s{6}([A-Z][A-Z0-9_]+):/gm)].map((m) => m[1]));
for (const v of ymlVars) {
  if (!composeKeys.has(v) && !INTERNAL[v]) errors.push(`application.yml 用了 ${v}，但 deploy/kit/docker-compose.yml 的 app 环境里没有（或在本脚本 INTERNAL 里写明原因）`);
}

const composeRefs = uniq([...compose.matchAll(/\$\{([A-Z][A-Z0-9_]+)(?::?[-?][^}]*)?\}/g)].map((m) => m[1]));
const exampleVars = [];
let tag = null;
for (const line of example.split('\n')) {
  if (/^#\s*----/.test(line)) tag = null;
  const t = /【(必填·自动|验收前填|可选)】/.exec(line);
  if (line.startsWith('#') && t) tag = t[1];
  const m = /^([A-Z][A-Z0-9_]*)=/.exec(line);
  if (m) {
    exampleVars.push(m[1]);
    if (!tag) errors.push(`env.example 的 ${m[1]} 没有标【必填·自动】【验收前填】【可选】`);
  }
}
const exampleSet = new Set(exampleVars);
for (const v of composeRefs) {
  if (!exampleSet.has(v) && !NOT_IN_EXAMPLE[v]) errors.push(`docker-compose.yml 引用了 \${${v}}，env.example 里没有`);
}
const refSet = new Set(composeRefs);
for (const v of exampleVars) {
  if (!refSet.has(v) && !NOT_IN_COMPOSE[v]) errors.push(`env.example 里的 ${v} 在 docker-compose.yml 里没有用到`);
}
const dup = exampleVars.filter((v, i) => exampleVars.indexOf(v) !== i);
if (dup.length) errors.push(`env.example 里重复的变量：${uniq(dup).join('、')}`);

if (errors.length) {
  console.error(`部署环境变量检查没有通过（${errors.length} 项）：`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}
console.log(`部署环境变量检查通过：application.yml ${ymlVars.length} 个、compose 引用 ${composeRefs.length} 个、env.example ${exampleVars.length} 个，都已标必填 / 可选`);
