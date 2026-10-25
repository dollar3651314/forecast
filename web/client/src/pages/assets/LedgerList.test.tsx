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
