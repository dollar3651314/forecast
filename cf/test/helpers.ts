/** 测试工具（forecast S19）：Miniflare 的本地 D1、执行迁移、假的 Env 和 ExecutionContext、外部服务的替身。 */
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getPlatformProxy } from 'wrangler';
import type { Env } from '../src/env';

export const CF_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 迁移脚本按文件名顺序拆成一条条语句（去掉注释行） */
export function migrationStatements(): string[] {
  const dir = path.join(CF_DIR, 'migrations');
  return readdirSync(dir)
    .filter((f) => f.endsWith('.sql'))
    .sort()
    .flatMap((f) =>
      readFileSync(path.join(dir, f), 'utf8')
        .split('\n')
        .filter((l) => !l.trim().startsWith('--'))
        .join('\n')
        .split(';')
        .map((s) => s.trim())
        .filter(Boolean),
    );
}

export async function migrate(db: D1Database): Promise<void> {
  await db.batch(migrationStatements().map((s) => db.prepare(s)));
}

/** 本地 D1（wrangler 的 getPlatformProxy，底下是 Miniflare 的真实 SQLite；不持久化，每次一个新库），执行全部迁移 */
export async function newDb(): Promise<{ dispose: () => Promise<void>; db: D1Database }> {
  const proxy = await getPlatformProxy<{ DB: D1Database }>({
    configPath: path.join(CF_DIR, 'test/wrangler.test.toml'),
    persist: false,
    remoteBindings: false,
    envFiles: [],
  });
  const db = proxy.env.DB;
  await migrate(db);
  return { dispose: proxy.dispose, db };
}

export function testEnv(db: D1Database, over: Partial<Env> = {}): Env {
  return {
    DB: db,
    ASSETS: { fetch: async () => new Response('asset') } as unknown as Fetcher,
    ENVIRONMENT: 'test',
    PUBLIC_BASE_URL: 'https://www.example.test',
    MAIL_FROM: 'Quick Come <noreply@mail.example.test>',
    RESEND_API_KEY: 'ci_only_resend_key',
    ...over,
  };
}

/** 收集 waitUntil 的任务，测试里 await ctx.flush() 等它们做完 */
export function testCtx(): ExecutionContext & { flush(): Promise<void> } {
  const tasks: Promise<unknown>[] = [];
  return {
    waitUntil(p: Promise<unknown>) {
      tasks.push(p);
    },
    passThroughOnException() {},
    props: {},
    async flush() {
      await Promise.all(tasks.splice(0));
    },
  } as unknown as ExecutionContext & { flush(): Promise<void> };
}

export interface Sent {
  url: string;
  auth: string | null;
  body: { from: string; to: string[]; subject: string; text: string; headers?: Record<string, string> };
}

/**
 * 替身：拦下全局 fetch。Resend 记下邮件，返回 resendStatus；其他地址交给 handlers（按 URL 前缀），没有的 599。
 */
export function stubFetch(handlers: Record<string, (req: Request) => Response | Promise<Response>> = {}, resendStatus = 200) {
  const mails: Sent[] = [];
  const calls: string[] = [];
  const fn = async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = new Request(input as RequestInfo, init);
    calls.push(req.url);
    if (req.url === 'https://api.resend.com/emails') {
      mails.push({ url: req.url, auth: req.headers.get('Authorization'), body: await req.json() });
      return new Response(JSON.stringify({ id: 'x' }), { status: resendStatus });
    }
    for (const [prefix, h] of Object.entries(handlers)) if (req.url.startsWith(prefix)) return h(req);
    return new Response('no stub', { status: 599 });
  };
  return { fn, mails, calls };
}

export function post(pathname: string, body: unknown, ip = '198.51.100.7', headers: Record<string, string> = {}): Request {
  return new Request(`https://www.example.test${pathname}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip, ...headers },
    body: JSON.stringify(body),
  });
}

let ipSeq = 0;
/** 每个测试用自己的 IP（C80），互不影响限次 */
export function freshIp(): string {
  ipSeq++;
  return `203.0.${(ipSeq >> 8) & 0xff}.${ipSeq & 0xff}`;
}

export function tokenFrom(link: string): string {
  return new URL(link).searchParams.get('t') ?? '';
}

export function links(text: string): string[] {
  return text.match(/https:\/\/\S+/g) ?? [];
}
