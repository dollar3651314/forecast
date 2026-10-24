import { compareAmounts, formatAmount, formatRatePercent, isValidAmountInput, subtractAmounts, sumAmounts, toScaled, usagePercent } from './money';

describe('金额格式化', () => {
  it('千分位和两位小数', () => {
    expect(formatAmount('18430.5')).toBe('18,430.50');
    expect(formatAmount('1250000.755')).toBe('1,250,000.76');
    expect(formatAmount('0')).toBe('0.00');
  });
  it('负数、正号和整数显示', () => {
    expect(formatAmount('-40000', { decimals: 0, showPlus: true })).toBe('-40,000');
    expect(formatAmount('230000', { decimals: 0, showPlus: true })).toBe('+230,000');
    expect(formatAmount('-0.001')).toBe('0.00');
  });
  it('多位小数', () => {
    expect(formatAmount('45000.123456', { decimals: 6 })).toBe('45,000.123456');
  });
  it('非法值显示破折号，不出现 NaN', () => {
    expect(formatAmount('abc')).toBe('—');
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount(1.5)).toBe('—');
  });
  it('定点累加不丢精度', () => {
    expect(sumAmounts(['0.1', '0.2'])).toBe('0.30');
    expect(sumAmounts(['5000.00', '800.00', '2350.00', '-150.5'])).toBe('7999.50');
    expect(subtractAmounts('20000.00', '1250.00')).toBe('18750.00');
  });
  it('比较和解析', () => {
    expect(compareAmounts('3000', '3000.00')).toBe(0);
    expect(compareAmounts('2999.99', '3000')).toBe(-1);
    expect(toScaled('1,234.5')).toBe(123450n);
  });
  it('费率百分比', () => {
    expect(formatRatePercent('0.0005')).toBe('0.05%');
    expect(formatRatePercent('-0.000192', 4)).toBe('-0.0192%');
    expect(formatRatePercent('0.3', 2)).toBe('30.00%');
    expect(formatRatePercent(null)).toBe('—');
  });
  it('使用率', () => {
    expect(usagePercent('230000', '250000')).toBe(92);
    expect(usagePercent('-65400', '300000')).toBe(22);
    expect(usagePercent('110000', '100000')).toBe(110);
    expect(usagePercent('1', '0')).toBe(0);
  });
  it('金额输入校验', () => {
    expect(isValidAmountInput('10.25')).toBe(true);
    expect(isValidAmountInput('10.255')).toBe(false);
    expect(isValidAmountInput('0')).toBe(false);
    expect(isValidAmountInput('-5')).toBe(false);
  });
});
