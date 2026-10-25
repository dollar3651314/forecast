// 端到端测试用的站点（S19）：只用 Node 自带模块，不引入额外依赖。
// - 客户端构建产物（web/client/dist）在 `/`，后台（web/admin/dist）在 `/admin/`，找不到的页面路径回到各自的 index.html（浏览器路由）
// - `/api/**`（含 WebSocket）原样转发到后端（默认 http://127.0.0.1:8080），保留浏览器的 Host 头（后端的 WebSocket 只接受同源），和测试环境 Nginx 的同源方式一致
// - 官网（v2，web/site/dist）在另一个端口 E2E_SITE_PORT（默认 4174）：和测试环境的官网域名一样，只转发 /api/health，
//   其他 /api/ 返回 404；页面里的客户端地址占位符换成本站客户端地址（Nginx 换成 https://${APP_DOMAIN}，见 deploy/nginx/site.conf.template）
// 环境变量：E2E_PORT（默认 4173）、E2E_SITE_PORT（默认 4174）、E2E_BACKEND（默认 http://127.0.0.1:8080）
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = Number(process.env.E2E_PORT || 4173);
const SITE_PORT = Number(process.env.E2E_SITE_PORT || 4174);
const BACKEND = new URL(process.env.E2E_BACKEND || 'http://127.0.0.1:8080');
const WWW_DIR = path.join(root, 'web/site/dist');
const WWW_API = new Set(['/api/health']);
const SITES = [
  { prefix: '/admin/', dir: path.join(root, 'web/admin/dist') },
  { prefix: '/', dir: path.join(root, 'web/client/dist') },
];
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

for (const dir of [...SITES.map((s) => s.dir), WWW_DIR]) {
  if (!fs.existsSync(path.join(dir, 'index.html'))) {
    console.error(`缺少构建产物 ${dir}/index.html：先在 web/client、web/admin、web/site 里运行 npm run build`);
    process.exit(1);
  }
}

function serveStatic(req, res) {
  const url = new URL(req.url, 'http://x');
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/admin') pathname = '/admin/';
  const site = SITES.find((s) => pathname.startsWith(s.prefix));
  const rel = pathname.slice(site.prefix.length);
  let file = path.join(site.dir, rel);
  if (!file.startsWith(site.dir)) {
    res.writeHead(400).end();
    return;
  }
  if (!rel || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    // 带扩展名的资源不存在就是 404；页面路径回到 index.html
    if (path.extname(rel)) {
      res.writeHead(404).end();
      return;
    }
    file = path.join(site.dir, 'index.html');
  }
  const type = TYPES[path.extname(file)] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  fs.createReadStream(file).pipe(res);
}

function proxy(req, res) {
  const up = http.request(
    { hostname: BACKEND.hostname, port: BACKEND.port, method: req.method, path: req.url, headers: req.headers },
    (r) => {
      res.writeHead(r.statusCode || 502, r.headers);
      r.pipe(res);
    },
  );
  up.on('error', () => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
    res.end('{"code":"BAD_GATEWAY","message":"后端没有启动","fields":[]}');
  });
  req.pipe(up);
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) proxy(req, res);
  else serveStatic(req, res);
});

// WebSocket：把升级请求原样转给后端，之后双向转发字节
server.on('upgrade', (req, socket, head) => {
  if (!req.url.startsWith('/api/')) {
    socket.destroy();
    return;
  }
  const up = net.connect(Number(BACKEND.port), BACKEND.hostname, () => {
    const lines = [`${req.method} ${req.url} HTTP/${req.httpVersion}`];
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      lines.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}`);
    }
    up.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) up.write(head);
    up.pipe(socket);
    socket.pipe(up);
  });
  up.on('error', () => socket.destroy());
  socket.on('error', () => up.destroy());
});

// 官网：静态页面（找不到的页面路径回到 index.html），只放行两个接口
function serveWww(req, res) {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/')) {
    if (WWW_API.has(url.pathname)) proxy(req, res);
    else res.writeHead(404, { 'Content-Type': 'application/json' }).end('{"code":"NOT_FOUND","message":"not found","fields":[]}');
    return;
  }
  const rel = decodeURIComponent(url.pathname).slice(1);
  let file = path.join(WWW_DIR, rel);
  if (!file.startsWith(WWW_DIR)) {
    res.writeHead(400).end();
    return;
  }
  if (!rel || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    if (path.extname(rel)) {
      res.writeHead(404).end();
      return;
    }
    file = path.join(WWW_DIR, 'index.html');
  }
  const type = TYPES[path.extname(file)] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  if (file.endsWith('.html')) res.end(fs.readFileSync(file, 'utf8').replace('__FC_CLIENT_URL__', `http://127.0.0.1:${PORT}`));
  else fs.createReadStream(file).pipe(res);
}
/**
 * 空闲连接保持 120 秒（Node 默认只有 5 秒）。forecast S3 查到的偶发失败：测试进程的请求上下文空闲约 6 秒后复用同一个连接，
 * 正好碰上服务器按 5 秒超时关闭它，Playwright 报 "socket hang up"（主流程第 06 步注入充值）。服务器保持得比客户端久就不会出现
 * （和 Nginx 的 keepalive_timeout 65 秒同一个道理）；headersTimeout 必须大于 keepAliveTimeout。
 */
function keepAlive(s) {
  s.keepAliveTimeout = 120_000;
  s.headersTimeout = 125_000;
  return s;
}
keepAlive(server);

keepAlive(http.createServer(serveWww)).listen(SITE_PORT, '127.0.0.1', () => {
  console.log(`端到端测试官网 http://127.0.0.1:${SITE_PORT}（/api/health → ${BACKEND.origin}）`);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`端到端测试站点 http://127.0.0.1:${PORT}（客户端 /，后台 /admin/，/api → ${BACKEND.origin}）`);
});
