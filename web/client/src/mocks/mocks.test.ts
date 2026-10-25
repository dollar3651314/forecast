import { ApiError } from '../api/errors';
import { getAccount } from '../api/account';
import { listLedger, getWithdrawInfo } from '../api/wallet';
import { getProtoState, getScenarioVersion, setProtoState } from '../api/prototype';
import { resetDb } from './db';

// 复制源这里还测品种、下单拒绝、市价单成交（差价合约），forecast S1 删除；下注的模拟在原型阶段（M1）加
describe('模拟层场景切换', () => {
  afterEach(() => {
    setProtoState({ scenario: 'normal' });
    resetDb();
  });

  it('正常数据：余额 = 可用 + 下注中（S2）；流水只有充值和提现，超过一页', async () => {
    const a = await getAccount();
    expect(a).toMatchObject({ balance: '129656.79', available: '128456.79', betLocked: '1200.00' });
    const ledger = await listLedger({ type: 'all', range: 'all', page: 1, pageSize: 20 });
    expect(ledger.total).toBeGreaterThan(20);
    expect(new Set(ledger.items.map((x) => x.type))).toEqual(new Set(['deposit', 'withdraw', 'withdrawRefund']));
  });

  it('空数据场景：余额为 0、流水为空', async () => {
    setProtoState({ scenario: 'empty' });
    expect((await getAccount()).balance).toBe('0.00');
    expect((await listLedger({ type: 'all', range: 'all', page: 1, pageSize: 20 })).total).toBe(0);
    const info = await getWithdrawInfo();
    expect(info.available).toBe('0.00');
  });

  it('接口出错场景：抛出带原因的错误', async () => {
    setProtoState({ scenario: 'error' });
    await expect(getAccount()).rejects.toBeInstanceOf(ApiError);
    await expect(getAccount()).rejects.toMatchObject({ reason: 'server' });
  });

  it('切换场景时版本号递增，页面据此重新加载', () => {
    const v = getScenarioVersion();
    setProtoState({ scenario: 'slow' });
    expect(getScenarioVersion()).toBe(v + 1);
    expect(getProtoState().scenario).toBe('slow');
  });
});
