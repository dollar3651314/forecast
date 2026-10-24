import { joinDate, parseDateText, partComplete, partOrder, problemParts, splitDate } from './dateParts';
import { validateBirthDate } from './validators';

const ZH = partOrder('zh-CN');
const EN = partOrder('en');

describe('三框日期：拆分和合成', () => {
  it('按语言排序：中文 年月日，英文 日月年', () => {
    expect(ZH).toEqual(['y', 'm', 'd']);
    expect(EN).toEqual(['d', 'm', 'y']);
  });

  it('填完整时输出补零的 YYYY-MM-DD，全空输出空串，没填完原样拼', () => {
    expect(joinDate({ y: '1988', m: '5', d: '2' })).toBe('1988-05-02');
    expect(joinDate({ y: '1988', m: '12', d: '31' })).toBe('1988-12-31');
    expect(joinDate({ y: '', m: '', d: '' })).toBe('');
    expect(joinDate({ y: '1988', m: '5', d: '' })).toBe('1988-5-');
    expect(joinDate({ y: '198', m: '05', d: '12' })).toBe('198-05-12');
  });

  it('拆分后端给的值和自己合成的值', () => {
    expect(splitDate('1990-01-31')).toEqual({ y: '1990', m: '01', d: '31' });
    expect(splitDate('1988-5-')).toEqual({ y: '1988', m: '5', d: '' });
    expect(splitDate('')).toEqual({ y: '', m: '', d: '' });
    expect(splitDate(null)).toEqual({ y: '', m: '', d: '' });
  });

  it('什么时候自动跳到下一格', () => {
    expect(partComplete('y', '198')).toBe(false);
    expect(partComplete('y', '1988')).toBe(true);
    expect(partComplete('m', '1')).toBe(false); // 可能是 10–12
    expect(partComplete('m', '0')).toBe(false);
    expect(partComplete('m', '2')).toBe(true);
    expect(partComplete('m', '05')).toBe(true);
    expect(partComplete('d', '3')).toBe(false); // 可能是 30、31
    expect(partComplete('d', '4')).toBe(true);
    expect(partComplete('d', '')).toBe(false);
  });
});

describe('整串粘贴', () => {
  it.each([
    ['1988-05-12', { y: '1988', m: '05', d: '12' }],
    ['1988/5/12', { y: '1988', m: '5', d: '12' }],
    ['1988.05.12', { y: '1988', m: '05', d: '12' }],
    ['1988年5月12日', { y: '1988', m: '5', d: '12' }],
    ['１９８８－０５－１２', { y: '1988', m: '05', d: '12' }],
    ['19880512', { y: '1988', m: '05', d: '12' }],
    [' 1988-05-12 ', { y: '1988', m: '05', d: '12' }],
  ])('中文界面 %s', (text, want) => {
    expect(parseDateText(text, ZH)).toEqual(want);
  });

  it('年在最后：大于 12 的是日，都不超过 12 时按语言（英文 日/月）', () => {
    expect(parseDateText('27/03/1990', EN)).toEqual({ y: '1990', m: '03', d: '27' });
    expect(parseDateText('03/27/1990', EN)).toEqual({ y: '1990', m: '03', d: '27' });
    expect(parseDateText('05/04/1990', EN)).toEqual({ y: '1990', m: '04', d: '05' });
    expect(parseDateText('05/04/1990', ZH)).toEqual({ y: '1990', m: '05', d: '04' });
    expect(parseDateText('27031990', EN)).toEqual({ y: '1990', m: '03', d: '27' });
  });

  it('认不出的返回 null', () => {
    expect(parseDateText('1988', ZH)).toBeNull();
    expect(parseDateText('abc', ZH)).toBeNull();
    expect(parseDateText('1988-05', ZH)).toBeNull();
    expect(parseDateText('1988-123-1', ZH)).toBeNull();
    expect(parseDateText('', ZH)).toBeNull();
  });
});

describe('逐框找错', () => {
  it('空的框、年不足 4 位、月超范围、日超出当月', () => {
    expect([...problemParts('')].sort()).toEqual(['d', 'm', 'y']);
    expect([...problemParts('1988-5-')]).toEqual(['d']);
    expect([...problemParts('198-05-12')]).toEqual(['y']);
    expect([...problemParts('1988-13-12')]).toEqual(['m']);
    expect([...problemParts('1990-02-30')]).toEqual(['d']);
    expect([...problemParts('1899-01-01')]).toEqual(['y']);
  });

  it('闰年 2 月 29 日没问题，平年有问题', () => {
    expect(problemParts('2000-02-29').size).toBe(0);
    expect([...problemParts('1900-02-29')]).toEqual(['d']); // 1900 不是闰年
    expect([...problemParts('1999-02-29')]).toEqual(['d']);
  });
});

describe('出生日期校验（三框合成的值）', () => {
  const today = new Date(Date.UTC(2026, 9, 16));
  it.each([
    ['', 'validation.required'],
    ['1988--', 'validation.dateIncomplete'],
    ['1988-5-', 'validation.dateIncomplete'],
    ['198-05-12', 'validation.yearFormat'],
    ['1990-02-30', 'validation.dateInvalid'],
    ['1990-13-01', 'validation.dateInvalid'],
    ['1990-00-10', 'validation.dateInvalid'],
    ['1899-12-31', 'validation.dateTooEarly'],
    ['2008-10-17', 'validation.ageUnder18'],
  ])('%s → %s', (v, key) => {
    expect(validateBirthDate(v, today)).toEqual([key]);
  });

  it('满 18 周岁当天、闰年 2 月 29 日都通过', () => {
    expect(validateBirthDate('2008-10-16', today)).toEqual([]);
    expect(validateBirthDate('2000-02-29', today)).toEqual([]);
    expect(validateBirthDate('1900-01-01', today)).toEqual([]);
  });
});
