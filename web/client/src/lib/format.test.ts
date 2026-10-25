import { add, dec, div, mul, toFixed, toPlain } from './decimal';
import { formatAmount, formatCountdown, formatDateTime, formatPercent, formatPrice, groupAddress } from './format';

describe('定点小数', () => {
  it('没有浮点误差', () => {
    expect(toFixed(add(dec('0.1'), dec('0.2')), 2)).toBe('0.30');
    expect(toFixed(add(dec('0.1'), dec('0.2')), 18)).toBe('0.300000000000000000');
  });
  it('四舍五入远离零', () => {
    expect(toFixed(dec('1.005'), 2)).toBe('1.01');
    expect(toFixed(dec('-1.005'), 2)).toBe('-1.01');
    expect(toFixed(dec('-0.001'), 2)).toBe('0.00');
  });
  it('乘除', () => {
    expect(toFixed(mul(dec('4126.75'), dec('10')), 2)).toBe('41267.50');
    expect(toFixed(div(dec('41267.5'), dec('50')), 2)).toBe('825.35');
    expect(toPlain(dec('0.10'))).toBe('0.1');
  });
  it('拒绝非法输入', () => {
    expect(() => dec('1e5')).toThrow();
    expect(() => dec(0.1)).toThrow();
  });
});

describe('金额和价格格式化', () => {
  it('千分位和小数位', () => {
    expect(formatAmount('1284567.891')).toBe('1,284,567.89');
    expect(formatAmount('0')).toBe('0.00');
    expect(formatAmount('-12')).toBe('-12.00');
    expect(formatPrice('112480.5', 1)).toBe('112,480.5');
    expect(formatPrice('1.1642', 5)).toBe('1.16420');
  });
  it('带符号', () => {
    expect(formatAmount('55', { sign: true })).toBe('+55.00');
    expect(formatAmount('-3.124', { sign: true })).toBe('-3.12');
    expect(formatAmount('0', { sign: true })).toBe('0.00');
    expect(formatPercent('0.4213', { sign: true })).toBe('+0.42%');
  });
  it('非法值显示占位符，不出现 NaN / undefined', () => {
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount('abc')).toBe('—');
    expect(formatDateTime('not a date')).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
  });
  it('倒计时和地址分组', () => {
    expect(formatCountdown(6300_000)).toBe('1:45:00');
    expect(formatCountdown(-5)).toBe('00:00');
    expect(groupAddress('TXr7mKp3Vd')).toBe('TXr7 mKp3 Vd');
  });
});
