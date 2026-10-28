import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderPage } from '../../test/renderApp';
import type { LedgerItem } from '../../api/types';
import { LedgerList } from './LedgerList';

// forecast S5（AC-39）：资金流水能看到派彩、抽成、下注退回，每条带市场（点到对应市场）
const market = { publicNo: '107700000000000001', title: { en: 'Man City vs Liverpool', zhCN: '曼城 vs 利物浦', zhTW: '曼城 vs 利物浦' } };
const items: LedgerItem[] = [
  { id: '1', type: 'payout', amount: '166.66', ref: '', time: '2026-10-25T10:00:00Z', market },
  { id: '2', type: 'fee', amount: '-3.33', ref: '', time: '2026-10-25T10:00:00Z', market },
  { id: '3', type: 'refund', amount: '50', ref: '', time: '2026-10-25T09:00:00Z', market },
  { id: '4', type: 'bet', amount: '-100', ref: '', time: '2026-10-25T08:00:00Z', market },
];

describe('资金流水：结算和退款（S5）', () => {
  it('派彩、抽成、下注退回有自己的名字和收支方向，带市场题目', () => {
    renderPage(<LedgerList items={items} />, '/assets/ledger');
    const rows = screen.getAllByRole('listitem');
    expect(rows.map((r) => r.querySelector('b')?.textContent)).toEqual([
      '派彩 · 曼城 vs 利物浦',
      '抽成 · 曼城 vs 利物浦',
      '下注退回 · 曼城 vs 利物浦',
      '下注 · 曼城 vs 利物浦',
    ]);
    expect(rows.map((r) => r.querySelector('.lg-ic')?.className)).toEqual(['lg-ic in', 'lg-ic out', 'lg-ic in', 'lg-ic out']);
    expect(rows[0].querySelector('.lg-amt')?.textContent).toBe('+166.66');
    expect(rows[1].querySelector('.lg-amt')?.textContent).toBe('-3.33');
  });
});

// forecast S17A：测试入金（只在测试环境有）单独一类，不显示成"充值"；三种语言
describe('资金流水：测试入金（S17A）', () => {
  const credit: LedgerItem[] = [{ id: '9', type: 'testCredit', amount: '1000', ref: '', time: '2026-10-27T10:00:00Z', market: null }];

  it('简体：测试入金、收入方向', () => {
    renderPage(<LedgerList items={credit} />, '/assets/ledger');
    const row = screen.getByRole('listitem');
    expect(row.querySelector('b')?.textContent).toBe('测试入金');
    expect(row.querySelector('.lg-ic')?.className).toBe('lg-ic in');
    expect(row.querySelector('.lg-amt')?.textContent).toBe('+1,000.00');
  });

  it('繁体、英文', async () => {
    const { default: i18n } = await import('../../i18n');
    await i18n.changeLanguage('zh-TW');
    const { unmount } = renderPage(<LedgerList items={credit} />, '/assets/ledger');
    expect(screen.getByRole('listitem').querySelector('b')?.textContent).toBe('測試入金');
    unmount();
    await i18n.changeLanguage('en');
    renderPage(<LedgerList items={credit} />, '/assets/ledger');
    expect(screen.getByRole('listitem').querySelector('b')?.textContent).toBe('Test credit');
    await i18n.changeLanguage('zh-CN');
  });
});

// forecast S17B：人工调整（加款 / 扣款）带类别；三种语言
describe('资金流水：人工调整（S17B）', () => {
  const items: LedgerItem[] = [
    { id: '11', type: 'adjustCredit', amount: '800', ref: '', time: '2026-10-27T10:00:00Z', market: null, category: 'deposit_missing' },
    { id: '12', type: 'adjustDebit', amount: '-300', ref: '', time: '2026-10-27T11:00:00Z', market: null, category: 'wrong_credit' },
  ];

  it('简体：名字带类别，加款收入、扣款支出', () => {
    renderPage(<LedgerList items={items} />, '/assets/ledger');
    const rows = screen.getAllByRole('listitem');
    expect(rows.map((r) => r.querySelector('b')?.textContent)).toEqual(['人工调整（加款） · 充值未到账补入', '人工调整（扣款） · 误入账扣回']);
    expect(rows.map((r) => r.querySelector('.lg-ic')?.className)).toEqual(['lg-ic in', 'lg-ic out']);
    expect(rows[1].querySelector('.lg-amt')?.textContent).toBe('-300.00');
  });

  it('繁体、英文', async () => {
    const { default: i18n } = await import('../../i18n');
    await i18n.changeLanguage('zh-TW');
    const { unmount } = renderPage(<LedgerList items={items} />, '/assets/ledger');
    expect(screen.getAllByRole('listitem')[0].querySelector('b')?.textContent).toBe('人工調整（加款） · 充值未到帳補入');
    unmount();
    await i18n.changeLanguage('en');
    renderPage(<LedgerList items={items} />, '/assets/ledger');
    expect(screen.getAllByRole('listitem')[1].querySelector('b')?.textContent).toBe('Manual adjustment (debit) · Erroneous credit reversed');
    await i18n.changeLanguage('zh-CN');
  });
});
