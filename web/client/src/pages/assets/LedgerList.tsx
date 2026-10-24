import { useTranslation } from 'react-i18next';
import type { LedgerItem } from '../../api/types';
import { formatAmount, formatDateTime } from '../../lib/format';

const ICON_TONE: Record<LedgerItem['type'], string> = {
  deposit: 'in',
  withdraw: 'out',
  withdrawRefund: 'in',
};

/** 资金流水列表 */
export function LedgerList({ items }: { items: LedgerItem[] }) {
  const { t } = useTranslation();
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
              {l.ref && <span className="num"> · {l.ref}</span>}
            </b>
            <small className="num">{formatDateTime(l.time)}</small>
          </div>
          <div className={`lg-amt num ${l.amount.startsWith('-') ? 'down' : 'up'}`}>{formatAmount(l.amount, { sign: true })}</div>
        </li>
      ))}
    </ul>
  );
}
