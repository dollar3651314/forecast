/**
 * v2 资金归集（技术方案/v2-半自动归集.md v1.2，路线 (b)）：页面路径、链、归集方式、手续费币、权限点、地址格式校验。
 * - 以太坊（ERC20）、BSC：合约转发地址（BitGo ForwarderV4）+ 服务器批量归集，钱只能进 Safe 多签冷钱包（parent，写在合约里，后台只读）；
 * - TRON（TRC20）：普通地址 + Amos 离线签名（方案附录 B）；广播前给每个充值地址租能量（方案第 4.3 节），冷钱包地址在后台配置、24 小时后生效。
 *
 * 第一阶段（2026-10-20，Amos："上限保留，现在就可以开始开发"）已实现以太坊、BSC 的合约和后端：真实接口模式下这两条链接后端
 * （/api/admin/sweep/**，权限点 sweep:view / sweep:operate / sweep:config）；TRON 和自动归集是下一阶段，真实接口模式下显示"下一阶段"
 * （SWEEP_NEXT_PHASE）。原型（模拟数据）模式照旧全部显示，方便看下一阶段的样子。
 *
 * Fund sweeping. Phase 1 (ERC20/BSC contracts + backend) is live; TRON and auto-sweep are the next phase in real mode.
 */
import { IS_MOCK } from '../api/mode';
import type { SweepChain, SweepMode } from '../api/types';

/** 归集界面是否显示（第一阶段上线后两种模式都显示；留着当总开关） */
export const SWEEP_UI = true;

/** 真实接口模式下 TRON 归集、自动归集等还没上线的部分显示"下一阶段"；原型模式下全部显示 */
export const SWEEP_NEXT_PHASE = !IS_MOCK;

/** 这条链的归集在当前模式下能不能用 */
export function sweepChainLive(chain: SweepChain): boolean {
  return !(SWEEP_NEXT_PHASE && chain === 'TRC20');
}

export const SWEEP_BASE = '/funds/reconciliation/sweep';

export const SWEEP_CHAINS: SweepChain[] = ['ERC20', 'BSC', 'TRC20'];

/** 每条链的归集方式（路线 (b)） */
export const SWEEP_MODE: Record<SweepChain, SweepMode> = { ERC20: 'contract', BSC: 'contract', TRC20: 'offline' };

/** 每条链的手续费币 */
export const GAS_ASSET: Record<SweepChain, string> = { ERC20: 'ETH', BSC: 'BNB', TRC20: 'TRX' };

/**
 * 归集门槛和手续费占比上限的默认值（Amos 10-20 审核 SW-6："归集门槛可以让我在后台改，默认值使用你的建议"）。
 * 后台归集设置页可改（高危、原因必填、审计），页面上显示默认值方便对照。
 */
export const SWEEP_DEFAULTS = {
  thresholds: { ERC20: '100', BSC: '10', TRC20: '100' } as Record<SweepChain, string>,
  maxFeeRatioPct: '3',
};

/** 权限点（方案 SW-12，前后端清单一致）：查看、执行 / 重试（高危）、改设置（高危） */
export const SWEEP_PERM = { view: 'sweep:view', operate: 'sweep:operate', config: 'sweep:config' } as const;

const TRON_RE = /^T[1-9A-HJ-NP-Za-km-z]{33}$/;

/** TRON 冷钱包地址格式：T 开头的 34 位 Base58（正式版再做校验和） */
export function isTronAddress(address: string): boolean {
  return TRON_RE.test(address.trim());
}

/** 门槛、上限等数值：非负、最多 6 位小数 */
export function isSweepNumber(v: string | undefined): boolean {
  return /^\d+(\.\d{1,6})?$/.test((v ?? '').trim());
}
