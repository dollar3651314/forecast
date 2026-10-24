/** 个人资料、身份认证、通知偏好。real：/api/me/*；mock：原型模拟层（见 mode.ts）。 */
import * as mock from '../mocks/user';
import { IS_MOCK } from './mode';
import * as real from './real/user';
import type { KycInfo, KycSubmit, Lang, NotifyPrefs, Profile, ProfileUpdate } from './types';

export const getProfile = (): Promise<Profile> => (IS_MOCK ? mock.getProfile() : real.getProfile());
export const updateProfile = (p: ProfileUpdate): Promise<Profile> => (IS_MOCK ? mock.updateProfile(p) : real.updateProfile(p));
/** lang 只影响原型里驳回原因的语言；真实环境的驳回原因是审核员填写的原文 */
export const getKyc = (lang: Lang): Promise<KycInfo> => (IS_MOCK ? mock.getKyc(lang) : real.getKyc());
export const submitKyc = (s: KycSubmit): Promise<KycInfo> => (IS_MOCK ? mock.submitKyc(s) : real.submitKyc(s));
export const getNotifyPrefs = (): Promise<NotifyPrefs> => (IS_MOCK ? mock.getNotifyPrefs() : real.getNotifyPrefs());
export const updateNotifyPrefs = (p: NotifyPrefs): Promise<NotifyPrefs> => (IS_MOCK ? mock.updateNotifyPrefs(p) : real.updateNotifyPrefs(p));
