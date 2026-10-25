/**
 * 单笔最小提现必须大于每条链的提现手续费（需求 RQ-16、第 3.11 节、AC-61；S2）：模拟层和后端 SystemService 同一口径，
 * 被拒时一次列出全部不满足的链，提示文字说清是哪条链。
 * S3 起最小金额在"参数设置"改（betting_params:edit，PUT /api/admin/params/betting/withdraw.minAmount），系统配置页不再有这一项；手续费仍在系统配置页。
 */
import i18n from './i18n';
import { listParams, listSystemConfigs, switchIdentity, updateParam, updateSystemConfig } from './api';
import { ApiError } from './api/types';
import { errorText } from './utils/errors';

afterEach(async () => {
  await switchIdentity('super');
  await updateParam('betting', 'withdraw.minAmount', '100', '测试复原');
  await updateSystemConfig('withdraw.fee.ERC20', '5', '测试复原');
});

async function rejection(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    return e as ApiError;
  }
  throw new Error('应当被拒绝');
}

describe('单笔最小提现和手续费', () => {
  it('默认 100，在参数设置里，不在系统配置页', async () => {
    expect((await listParams('betting')).find((c) => c.key === 'withdraw.minAmount')?.value).toBe('100');
    expect((await listSystemConfigs()).find((c) => c.key === 'withdraw.minAmount')).toBeUndefined();
  });

  it('最小金额不大于某条链的手续费时拒绝，并指出是哪条链', async () => {
    const e = await rejection(updateParam('betting', 'withdraw.minAmount', '1', '测试'));
    expect(e.fields).toEqual([
      { field: 'ERC20', code: 'minNotAboveFee' },
      { field: 'TRC20', code: 'minNotAboveFee' },
    ]);
    await i18n.changeLanguage('zh-CN');
    expect(errorText(i18n.t, e)).toBe('提交的内容有误：ERC20：单笔最小提现金额必须大于这条链的提现手续费；TRC20：单笔最小提现金额必须大于这条链的提现手续费');
    await updateParam('betting', 'withdraw.minAmount', '5.01', '测试');
    expect((await listParams('betting')).find((c) => c.key === 'withdraw.minAmount')?.value).toBe('5.01');
  });

  it('手续费改到不小于最小金额时拒绝', async () => {
    const e = await rejection(updateSystemConfig('withdraw.fee.ERC20', '100', '测试'));
    expect(e.fields).toEqual([{ field: 'ERC20', code: 'feeNotBelowMin' }]);
    await updateSystemConfig('withdraw.fee.ERC20', '99.99', '测试');
  });

  it('别人刚改过（expected 不是当前值）返回 409，不覆盖', async () => {
    const e = await rejection(updateParam('betting', 'withdraw.minAmount', '150', '测试', '120'));
    expect(e.status).toBe(409);
    expect((await listParams('betting')).find((c) => c.key === 'withdraw.minAmount')?.value).toBe('100');
  });
});

describe('参数设置的权限点（需求第 2.2 节）', () => {
  it('费率组要 fee:edit、其他参数要 betting_params:edit；默认值和后端迁移 V10 一致', async () => {
    const fee = await listParams('fee');
    expect(fee.map((p) => `${p.key}=${p.value}`)).toEqual([
      'market.fee.FOOTBALL=5',
      'market.fee.NBA=5',
      'market.fee.CRYPTO_15M=5',
      'market.fee.CRYPTO_1H=5',
      'market.fee.MACRO=5',
    ]);
    await switchIdentity('ops');
    await expect(listParams('fee')).rejects.toBeInstanceOf(ApiError);
    await expect(updateParam('betting', 'withdraw.minAmount', '120', '测试')).rejects.toBeInstanceOf(ApiError);
  });
});
