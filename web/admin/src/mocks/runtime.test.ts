/** 模拟层场景切换：正常 / 空数据 / 接口很慢 / 接口出错 */
import { ApiError } from '../api/types';
import { listCustomers, getDashboard, getCurrentAdmin } from '../api';
import { getScenario, getScenarioVersion, setLatency, setScenario, subscribeScenario } from './runtime';

afterEach(() => setScenario('normal'));

describe('原型场景', () => {
  it('正常数据：超过一页', async () => {
    const page = await listCustomers({ page: 1, pageSize: 10 });
    expect(page.total).toBeGreaterThan(10);
    expect(page.items).toHaveLength(10);
  });

  it('空数据：列表为空、统计为 0', async () => {
    setScenario('empty');
    const page = await listCustomers({ page: 1, pageSize: 10 });
    expect(page.total).toBe(0);
    expect(page.items).toEqual([]);
    const d = await getDashboard();
    expect(d.pendingKyc).toBe(0);
    expect(d.todayDeposit).toBe('0.00');
  });

  it('接口出错：抛出带原因的错误', async () => {
    setScenario('error');
    await expect(listCustomers({ page: 1, pageSize: 10 })).rejects.toBeInstanceOf(ApiError);
    await expect(listCustomers({ page: 1, pageSize: 10 })).rejects.toMatchObject({ reasonKey: 'errors.serverUnavailable' });
  });

  it('原型基础功能不受场景影响', async () => {
    setScenario('error');
    // 复制源用"调余额限额"演示，forecast S1 删除了调余额；当前管理员同样不受场景影响
    await expect(getCurrentAdmin(1)).resolves.toMatchObject({ admin: { username: 'amos' } });
  });

  it('接口很慢：等待更久', async () => {
    setLatency({ min: 0, max: 0, slow: 60 });
    setScenario('slow');
    const start = Date.now();
    await listCustomers({ page: 1, pageSize: 10 });
    expect(Date.now() - start).toBeGreaterThanOrEqual(50);
    setLatency({ min: 0, max: 0, slow: 0 });
  });

  it('切换场景会通知订阅者并增加版本号', () => {
    const fn = vi.fn();
    const off = subscribeScenario(fn);
    const v = getScenarioVersion();
    setScenario('empty');
    expect(fn).toHaveBeenCalledTimes(1);
    expect(getScenarioVersion()).toBe(v + 1);
    expect(getScenario()).toBe('empty');
    off();
  });
});
