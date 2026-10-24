/**
 * 充值（效果图 M3）：先选网络，再显示地址、二维码、复制按钮和警示（含最小充值金额，AC-41）。ERC20 与 BSC 是同一个地址。
 * 下方是最近的充值记录（待确认显示确认进度；低于最小充值金额的不入账，AC-41）。
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getDepositAddresses, listDeposits } from '../../api/wallet';
import type { DepositRecord, Network } from '../../api/types';
import { isWalletNotReady } from '../../api/errors';
import { useToast } from '../../app/contexts';
import { AsyncView, EmptyState } from '../../components/AsyncView';
import { Icon } from '../../components/Icon';
import { QrCode } from '../../components/QrCode';
import { useAsync } from '../../hooks/useAsync';
import { useServerEvent } from '../../hooks/useServerEvent';
import { formatAmount, formatDateTime, groupAddress } from '../../lib/format';

const NETS: Network[] = ['ERC20', 'TRC20', 'BSC'];

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // 旧浏览器或非安全上下文的兜底
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  }
}

export function DepositCard() {
  const { t } = useTranslation();
  const toast = useToast();
  const s = useAsync(getDepositAddresses, 'deposit');
  const [net, setNet] = useState<Network | null>(null);
  return (
    <section className="card" id="dep-card" aria-labelledby="dep-title">
      <div className="card-head">
        <h2 id="dep-title">{t('deposit.chooseNetwork')}</h2>
        <span className="muted small">{t('deposit.onlyUsdt')}</span>
      </div>
      <div className="net-grid" role="radiogroup" aria-label={t('deposit.chooseNetwork')}>
        {NETS.map((n) => (
          <button key={n} type="button" role="radio" aria-checked={net === n} className={`net${net === n ? ' on' : ''}`} onClick={() => setNet(n)}>
            <b>{n}</b>
            <small>{t(`deposit.chain.${n}`)}</small>
          </button>
        ))}
      </div>
      {!net ? (
        <p className="pick-first">{t('deposit.pickFirst')}</p>
      ) : isWalletNotReady(s.error) && !s.data ? (
        // 服务器还没有配置区块链（例如测试环境没接测试网）：说明"暂未开通"，不显示成"加载失败 + 重试"
        <EmptyState compact title={t('deposit.notOpenTitle')} hint={t('deposit.notOpenHint')} />
      ) : (
        <AsyncView state={s} rows={4}>
          {(list) => {
            const a = list.find((x) => x.network === net)!;
            return (
              <>
                <div className="qr-wrap">
                  <QrCode value={a.address} label={t('deposit.qrLabel', { network: net })} />
                  <div className="addr">
                    <span className="num">{groupAddress(a.address)}</span>
                    <button
                      type="button"
                      className="btn btn-primary btn-sm"
                      onClick={async () => {
                        const ok = await copyText(a.address);
                        toast.show({ tone: ok ? 'success' : 'error', title: ok ? t('deposit.copied') : t('deposit.copyFailed') });
                      }}
                    >
                      <Icon name="copy" size={14} />
                      {t('common.copy')}
                    </button>
                  </div>
                  {net !== 'TRC20' && <p className="muted small">{t('deposit.sameAddress')}</p>}
                </div>
                <div className="warn-box" role="note">
                  <h3>
                    <Icon name="warn" size={16} strokeWidth={2.2} />
                    {t('deposit.warnTitle')}
                  </h3>
                  <ul>
                    <li>{t('deposit.warnOnly', { network: net })}</li>
                    <li>{t('deposit.warnWrong')}</li>
                    <li>{t('deposit.warnConfirm', { n: a.confirmations, m: a.etaMinutes })}</li>
                    {a.minDeposit && <li>{t('deposit.warnMin', { min: a.minDeposit })}</li>}
                  </ul>
                </div>
              </>
            );
          }}
        </AsyncView>
      )}
      <DepositRecords />
    </section>
  );
}

/** 只显示最近几条，完整的到账记录在资金流水里 */
const RECENT = 5;

function DepositRecords() {
  const { t } = useTranslation();
  const s = useAsync(listDeposits, 'deposits');
  // 发现待确认的充值时后端就推 deposit 事件（E2E-2），不用刷新页面就能看到"待确认"和确认进度
  useServerEvent((e) => {
    if (e.type === 'deposit' || e.type === 'account' || (e.type === 'notification' && e.item.kind === 'deposit')) s.reload();
  });
  const label = (d: DepositRecord) => (d.status === 'pending' ? t('deposit.status.pending', { n: d.confirmations, m: d.requiredConfirmations }) : t(`deposit.status.${d.status}`));
  return (
    <>
      <div className="card-head dep-records">
        <h2>{t('deposit.records')}</h2>
      </div>
      <AsyncView state={s} rows={2} isEmpty={(d) => d.length === 0} empty={<EmptyState compact title={t('deposit.recordsEmpty')} />}>
        {(list) => (
          <ul className="ledger">
            {list.slice(0, RECENT).map((d) => (
              <li key={d.id}>
                <span className={`lg-ic ${d.status === 'credited' ? 'in' : 'fee'}`} aria-hidden="true">
                  {t('ledger.icon.deposit')}
                </span>
                <div className="lg-t">
                  <b>
                    {label(d)}
                    <span className="num"> · {d.network}</span>
                  </b>
                  <small className="num">{formatDateTime(d.time)}</small>
                </div>
                <div className={`lg-amt num${d.status === 'credited' ? ' up' : ' muted'}`}>{formatAmount(d.amount)}</div>
              </li>
            ))}
          </ul>
        )}
      </AsyncView>
    </>
  );
}
