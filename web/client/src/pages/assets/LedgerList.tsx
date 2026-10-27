import { useTranslation } from 'react-i18next';
import type { LedgerItem, LedgerMarket } from '../../api/types';
import { formatAmount, formatDateTime } from '../../lib/format';

const ICON_TONE: Record<LedgerItem['type'], string> = {
  deposit: 'in',
  withdraw: 'out',
  withdrawRefund: 'in',
  bet: 'out',
  payout: 'in',
  fee: 'out',
  refund: 'in',
  compensation: 'in',
  testCredit: 'in',
  adjustCredit: 'in',
  adjustDebit: 'out',
};

/** 市场题目按当前语言取（取不到用英文，RQ-18） */
function marketTitle(m: LedgerMarket, lang: string): string {
  const t = lang.startsWith('zh') ? (lang === 'zh-TW' ? m.title.zhTW : m.title.zhCN) : m.title.en;
  return t || m.title.en;
}

/** 资金流水列表 */
export function LedgerList({ items }: { items: LedgerItem[] }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="ledger">
      {items.map((l) => (
        <li key={l.id}>
          <span className={`lg-ic ${ICON_TONE[l.type]}`} aria-hidden="true">
            {t(`ledger.icon.${l.type}`)}
          </span>
          <div className="lg-t">
            <b>
              {t(`ledger.type.${l.type}`)}
              {l.market ? (
                <span> · {marketTitle(l.market, i18n.language)}</span>
              ) : l.category ? (
                <span> · {t(`ledger.category.${l.category}`)}</span>
              ) : (
                l.ref && <span className="num"> · {l.ref}</span>
              )}
            </b>
            <small className="num">{formatDateTime(l.time)}</small>
          </div>
          <div className={`lg-amt num ${l.amount.startsWith('-') ? 'down' : 'up'}`}>{formatAmount(l.amount, { sign: true })}</div>
        </li>
      ))}
    </ul>
  );
}
