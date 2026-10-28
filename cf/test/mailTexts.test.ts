/**
 * 确认邮件的文字和后端通知模板 WAITLIST_CONFIRM 一字不差（forecast S19）：简中、英文读迁移 V26，繁中读 S18 改成台湾用语的 V31（最新的为准）。
 * 改了后端模板忘了改这里（或者反过来），这个测试失败。
 */
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { CONFIRM_MAIL, render } from '../src/mailTexts';
import { CF_DIR } from './helpers';

const MIG = path.join(CF_DIR, '../backend/src/main/resources/db/migration');

/** SQL 字符串字面量：'' → '，\n → 换行 */
function unquote(s: string): string {
  return s.replace(/''/g, "'").replace(/\\n/g, '\n');
}

function sqlStrings(sql: string): string[] {
  return [...sql.matchAll(/'((?:[^']|'')*)'/g)].map((m) => unquote(m[1]));
}

describe('确认邮件的文字', () => {
  const files = readdirSync(MIG).sort((a, b) => Number(a.match(/^V(\d+)/)![1]) - Number(b.match(/^V(\d+)/)![1]));
  const insert = readFileSync(path.join(MIG, files.find((f) => f.startsWith('V26__'))!), 'utf8');
  const stmt = insert.slice(insert.indexOf("VALUES ('WAITLIST_CONFIRM'"));
  // 列顺序：code, audience, req_no, kind, name_zh, name_en, title_zh, title_en, body_zh, body_en, title_tw, body_tw
  const v = sqlStrings(stmt.slice(0, stmt.indexOf(');')));

  it('简中、英文和 V26 一样', () => {
    expect(v[0]).toBe('WAITLIST_CONFIRM');
    const [, , , , titleZh, titleEn, bodyZh, bodyEn] = v;
    expect(CONFIRM_MAIL['zh-CN']).toEqual({ subject: titleZh, body: bodyZh });
    expect(CONFIRM_MAIL.en).toEqual({ subject: titleEn, body: bodyEn });
  });

  it('繁中和最新改过它的迁移一样（S18 的 V31：台湾用语）', () => {
    let titleTw = v[8];
    let bodyTw = v[9];
    for (const f of files) {
      const sql = readFileSync(path.join(MIG, f), 'utf8');
      for (const m of sql.matchAll(/UPDATE `notify_template` SET `body_tw` = '((?:[^']|'')*)'\s*WHERE `code` = 'WAITLIST_CONFIRM'/g)) bodyTw = unquote(m[1]);
      for (const m of sql.matchAll(/UPDATE `notify_template` SET `title_tw` = '((?:[^']|'')*)'\s*WHERE `code` = 'WAITLIST_CONFIRM'/g)) titleTw = unquote(m[1]);
    }
    expect(bodyTw).toContain('電子信箱');
    expect(CONFIRM_MAIL['zh-TW']).toEqual({ subject: titleTw, body: bodyTw });
  });

  it('变量替换：只换认识的', () => {
    expect(render('a {confirm} b {x}', { confirm: 'C' })).toBe('a C b {x}');
  });
});
