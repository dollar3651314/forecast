/** 各类状态 → 标签颜色 */
import type { PillTone } from '../components/Pill';

export const withdrawalTone: Record<string, PillTone> = {
  cooling: 'info', pending: 'pend', delayed: 'delay', broadcasting: 'info', completed: 'ok', rejected: 'danger', cancelled: 'muted', failed: 'danger',
};
export const depositTone: Record<string, PillTone> = { confirming: 'pend', credited: 'ok', ignored: 'muted', reversed: 'danger' };
export const kycTone: Record<string, PillTone> = { none: 'muted', pending: 'pend', approved: 'ok', rejected: 'danger' };
export const customerTone: Record<string, PillTone> = { active: 'ok', frozen: 'danger' };
export const taskTone: Record<string, PillTone> = { pending: 'pend', processing: 'info', success: 'ok', failed: 'danger' };
export const reconTone: Record<string, PillTone> = { open: 'pend', resolved: 'ok' };
export const notifyTone: Record<string, PillTone> = { pending: 'pend', sent: 'ok', failed: 'danger', skipped: 'muted' };
