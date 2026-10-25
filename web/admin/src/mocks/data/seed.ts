/**
 * 模拟数据的公共工具：固定随机种子（每次打开数据一样）、原型的"当前时间"、金额生成。
 */
import { fromScaled } from '../../utils/money';

/** 原型里的"现在"：2026-10-14 16:20:35（UTC+8 显示） */
export const NOW_MS = Date.UTC(2026, 9, 14, 16, 20, 35);

export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function createRng(seed: number) {
  const next = mulberry32(seed);
  return {
    next,
    int(min: number, max: number): number {
      return min + Math.floor(next() * (max - min + 1));
    },
    pick<T>(arr: readonly T[]): T {
      return arr[Math.floor(next() * arr.length)];
    },
    bool(p = 0.5): boolean {
      return next() < p;
    },
    /** 生成 [min, max] 之间的金额字符串，scale 位小数 */
    amount(min: number, max: number, scale = 2): string {
      const factor = 10 ** scale;
      const v = BigInt(min * factor + Math.floor(next() * (max - min) * factor));
      return fromScaled(v, scale);
    },
    hex(len: number): string {
      let s = '';
      for (let i = 0; i < len; i++) s += '0123456789abcdef'[Math.floor(next() * 16)];
      return s;
    },
    base58(len: number): string {
      const chars = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
      let s = '';
      for (let i = 0; i < len; i++) s += chars[Math.floor(next() * chars.length)];
      return s;
    },
  };
}

const pad = (n: number) => String(n).padStart(2, '0');

/** 把毫秒时间格式化成 "YYYY-MM-DD HH:mm:ss"（按 UTC 字段取值，原型里视为北京时间） */
export function fmtTime(ms: number): string {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
}

export function minutesAgo(min: number): string {
  return fmtTime(NOW_MS - min * 60_000);
}

export function nowString(): string {
  return fmtTime(NOW_MS + (Date.now() % 60_000));
}

export function evmAddress(rng: ReturnType<typeof createRng>): string {
  return '0x' + rng.hex(40);
}

export function tronAddress(rng: ReturnType<typeof createRng>): string {
  return 'T' + rng.base58(33);
}

export const CHINESE_NAMES = [
  '张三', 'Li Wei', '王五', '陈晓', '赵敏', '刘洋', '孙磊', '周婷', '吴昊天', '郑爽',
  '欧阳娜娜', '<b>测试</b>客户', 'Alexandria Konstantinopoulou-Wellington', '黄晓明', '马丽', '杨洋', '林志强', '何静', '高峰', '罗琳',
  '梁思成', '谢婷婷', '韩雪', '唐嫣然', '冯程程', '曹宇', '彭于晏', '曾小贤', '吕一鸣', '苏菲',
  'Lucas Martin', '蒋欣', '蔡文静', '贾宝玉', '丁一', '魏来', '薛冰', '叶子', '司马长风长风长风', '余欢水',
  '潘多拉', '杜子腾', 'Diana Prince', '夏雨', '钟南', '汪洋', '田甜', '任盈盈', '姜子牙', '范冰冰',
  '方圆', '石磊', '姚晨', '谭咏麟', '廖凡', '熊大', '金莉莉',
];

export const EMAIL_DOMAINS = ['qq.com', '163.com', 'foxmail.com', '126.com', 'outlook.com', 'sina.com', 'gmail.com'];

export const NATIONALITIES = ['CN', 'CN', 'CN', 'CN', 'HK', 'TW', 'MY', 'SG', 'US', 'GB'];
