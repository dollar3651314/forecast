/**
 * 测试访问哪里（两种运行形态共用一份用例）：
 * - 默认（S19）：浏览器和替身接口都走 e2e/server.mjs（http://127.0.0.1:4173，客户端 /、后台 /admin/、/api 转发到后端；官网在 4174）。
 * - 部署形态（E2E_MODE=deployed，e2e/run-deployed.sh）：浏览器走 forecast-web 镜像里的 Nginx（HTTPS、两个假域名），
 *   替身接口 /api/e2e/** 直接访问后端 127.0.0.1:8080——Nginx 在客户端域名上故意把 /api/e2e/ 返回 404，不为测试放开。
 */
const PORT = Number(process.env.E2E_PORT || 4173);
const LOCAL = `http://127.0.0.1:${PORT}`;

/** 部署形态：经过 Nginx 和镜像（测试环境的同一套） */
export const DEPLOYED = process.env.E2E_MODE === 'deployed';
/** 客户端页面的地址（浏览器访问） */
export const CLIENT_URL = process.env.E2E_CLIENT_URL || LOCAL;
/** 后台的地址（浏览器访问；页面在它的 /admin/ 下） */
export const ADMIN_URL = process.env.E2E_ADMIN_URL || LOCAL;
/** 官网的地址（v2；默认是 server.mjs 的第二个端口，部署形态是官网假域名） */
export const SITE_URL = process.env.E2E_SITE_URL || `http://127.0.0.1:${Number(process.env.E2E_SITE_PORT || 4174)}`;
/** 替身接口 /api/e2e/** 的地址（测试代码访问，不经过浏览器） */
export const STUB_URL = process.env.E2E_STUB_URL || LOCAL;
/** 后端设了 FC_ENV_LABEL 时，两个前端顶部应该显示"测试环境"标识条（部署形态默认检查） */
export const EXPECT_ENV_BANNER = (process.env.E2E_EXPECT_ENV_BANNER || (DEPLOYED ? '1' : '')) === '1';
