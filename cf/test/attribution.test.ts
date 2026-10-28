/** 来源参数（forecast S19，P14）：例子和后端 AttributionTest 一样（同一套规则，S13）。 */
import { describe, expect, it } from 'vitest';
import { clean, fromBody, sourceOf } from '../src/attribution';

describe('来源参数', () => {
  it('清洗：不合法的当成没有', () => {
    expect(clean(' tg ')).toBe('tg');
    expect(clean('a.b_c~d-1')).toBe('a.b_c~d-1');
    expect(clean('<x>')).toBeNull();
    expect(clean('a b')).toBeNull();
    expect(clean('x'.repeat(65))).toBeNull();
    expect(clean('')).toBeNull();
    expect(clean(42)).toBeNull();
    expect(sourceOf(null, null, null, null)).toEqual({ src: null, cmp: null, ref: null, start: null });
  });

  it('start 参数：同样的编码，链接上的优先；解不出来的只留原文', () => {
    expect(sourceOf(null, null, null, 's-tg__c-w1__r-10023')).toEqual({ src: 'tg', cmp: 'w1', ref: '10023', start: 's-tg__c-w1__r-10023' });
    expect(sourceOf('x', null, null, 's-tg__c-w1')).toEqual({ src: 'x', cmp: 'w1', ref: null, start: 's-tg__c-w1' });
    expect(sourceOf(null, null, null, 'hello')).toEqual({ src: null, cmp: null, ref: null, start: 'hello' });
    expect(sourceOf(null, null, null, 'bad start!')).toEqual({ src: null, cmp: null, ref: null, start: null });
    expect(sourceOf(null, null, null, 'g-x__s-group_42')).toEqual({ src: 'group_42', cmp: null, ref: null, start: 'g-x__s-group_42' });
  });

  it('页面传来的 attribution（一组 src / cmp / ref / start）；不是对象当成没有', () => {
    expect(fromBody({ src: 'threads', cmp: 'tw1', ref: 'r', start: undefined })).toEqual({ src: 'threads', cmp: 'tw1', ref: 'r', start: null });
    expect(fromBody(undefined)).toEqual({ src: null, cmp: null, ref: null, start: null });
    expect(fromBody('src=x')).toEqual({ src: null, cmp: null, ref: null, start: null });
  });
});
