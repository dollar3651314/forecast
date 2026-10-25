/**
 * v2 资金归集第一阶段：真实接口的请求路径、参数和字段转换（后端 admin/ops/SweepController、SweepOps）。
 * TRON 专用的字段后端不返回，这里补成"没有"；设置只提交第一阶段的项。
 */
import * as real from './api/real';
import { ERROR_KEYS } from './api/http';
import { fakeFetch } from './test/fakeFetch';

afterEach(() => vi.unstubAllGlobals());

describe('资金归集：真实接口', () => {
  it('概览、预览：TRON 字段补成没有，查不到币价时显示 —', async () => {
    const { calls } = fakeFetch({
      'GET /api/admin/sweep/overview': { body: [{ chain: 'ERC20', mode: 'contract', available: true, unavailable: null, pendingUsdt: '0', eligibleCount: 0, belowCount: 0, destination: '0x5afe', operatorBalance: null, gasAsset: 'ETH', lastSweepAt: null, activeBatch: null, checkedAt: null }] },
      'POST /api/admin/sweep/preview': {
        body: {
          chain: 'ERC20', mode: 'contract', destination: '0x5afe', threshold: '100', maxFeeRatioPct: '3', gasPrice: 'baseFee 1 gwei', gasAsset: 'ETH',
          candidates: [{ userId: 7, customerName: null, address: '0xabc', usdt: '200', deploy: true, estFee: '0.0003', estFeeUsd: null, skip: null }],
          totals: { count: 1, usdt: '200', fee: '0.0004', feeUsd: null, feePct: null, skipped: 0 },
          warnings: ['priceMissing'],
        },
      },
    });
    expect((await real.getSweepOverview())[0].available).toBe(true);
    const p = await real.previewSweep('ERC20');
    expect(calls[1]).toMatchObject({ method: 'POST', url: '/api/admin/sweep/preview', body: { chain: 'ERC20' } });
    expect(p.tronRent).toBeNull();
    expect(p.candidates[0]).toMatchObject({ customerName: '—', topup: '0', activate: false, rentTrx: '0', estFeeUsd: '—', deploy: true });
    expect(p.totals).toMatchObject({ feeUsd: '—', feePct: '—', topup: '0', rent: '0' });
    expect(p.warnings).toEqual(['priceMissing']);
  });

  it('执行、批次、重试', async () => {
    const { calls } = fakeFetch({
      'POST /api/admin/sweep/batches': { body: { no: '9001' } },
      'GET /api/admin/sweep/batches': { body: { items: [], total: 0, page: 1, pageSize: 20 } },
      'GET /api/admin/sweep/batches/9001': {
        body: {
          no: '9001', chain: 'ERC20', mode: 'contract', status: 'partial', trigger: 'manual', addressCount: 1, expectedUsdt: '200', sweptUsdt: '0', fee: '0.001',
          gasAsset: 'ETH', destination: '0x5afe', createdBy: 'amos', createdAt: '2026-10-20T00:00:00Z', finishedAt: null, expiresAt: null, txHash: '0xt', reason: '每周',
          failReason: '1 个地址没有归集成功',
          items: [{ itemNo: 1, userId: 7, customerName: null, address: '0xabc', amount: '200', balanceBefore: '200', deploy: true, status: 'failed', txHash: '0xt', failReason: '批量合约报告失败：x' }],
          events: [{ time: '2026-10-20T00:00:00Z', actor: 'amos', text: '手动执行归集' }],
        },
      },
      'POST /api/admin/sweep/batches/9001/retry': { body: { no: '9002' } },
    });
    expect(await real.runSweep('BSC', '每周')).toEqual({ no: '9001' });
    expect(calls[0].body).toEqual({ chain: 'BSC', reason: '每周' });
    await real.listSweepBatches({ page: 2, pageSize: 10, chain: 'ERC20' });
    expect(calls[1].url).toBe('/api/admin/sweep/batches?chain=ERC20&page=2&pageSize=10');
    const d = await real.getSweepBatch('9001');
    expect(d.items[0]).toMatchObject({ customerName: '—', topup: '0', rent: null, failReason: '批量合约报告失败：x' });
    expect(d).toMatchObject({ unsignedSha256: null, signedSha256: null, failReason: '1 个地址没有归集成功' });
    expect(await real.retrySweepBatch('9001', '再试')).toEqual({ no: '9002' });
    expect(calls[3].body).toEqual({ reason: '再试' });
  });

  it('设置：后端没有的下一阶段项补默认值；保存只提交第一阶段的项', async () => {
    const back = {
      thresholds: { ERC20: '100', BSC: '10', TRC20: '100' }, maxFeeRatioPct: '3', evmCaps: { perAddress: '5000', perBatch: '20000' }, operatorMin: { ERC20: '0', BSC: '0' },
      defaults: {}, evm: { configured: false, problem: null, schemeVer: 0, parent: null, factory: null, implementation: null, batcher: null, operator: null, operatorReady: false, operatorProblem: '没有配置发起账户', operatorBalances: {}, readAt: null },
      version: 3, updatedAt: null, updatedBy: null,
    };
    const { calls } = fakeFetch({ 'GET /api/admin/sweep/settings': { body: back }, 'PUT /api/admin/sweep/settings': { body: back } });
    const s = await real.getSweepSettings();
    expect(s.evm).toMatchObject({ configured: false, parent: '—', operatorReady: false, operatorProblem: '没有配置发起账户', operatorBalances: { ERC20: '—', BSC: '—' } });
    expect(s.tronTarget).toEqual({ address: null, pending: null });
    await real.updateSweepSettings({ ...s, reason: '调门槛', expectedVersion: 3 });
    expect(Object.keys(calls[1].body as object).sort()).toEqual(['evmCaps', 'expectedVersion', 'maxFeeRatioPct', 'operatorMin', 'reason', 'thresholds']);
  });

  it('后端错误码有翻译', () => {
    expect(ERROR_KEYS).toMatchObject({
      SWEEP_UNAVAILABLE: 'errors.sweepUnavailable',
      SWEEP_NOTHING: 'errors.sweepNothing',
      SWEEP_BATCH_ACTIVE: 'errors.sweepBatchActive',
      SWEEP_BATCH_STATE: 'errors.sweepBatchState',
    });
  });
});
