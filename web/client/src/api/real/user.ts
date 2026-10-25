/** 个人资料、KYC、通知偏好的真实接口：`/api/me/*`（`ProfileController`、`KycController`、`NotificationController`） */
import { get, post, put, utc } from '../http';
import type { KycInfo, KycSubmit, NotifyPrefs, Profile, ProfileUpdate } from '../types';

/** 资料和 KYC 的时间是不带时区的 UTC 时间，补上 Z */
const profileView = (p: Profile): Profile => ({ ...p, middleName: p.middleName ?? '', registeredAt: utc(p.registeredAt) ?? '' });
const kycView = (k: KycInfo): KycInfo => ({ ...k, submittedAt: utc(k.submittedAt), reviewedAt: utc(k.reviewedAt) });

export const getProfile = async (): Promise<Profile> => profileView(await get<Profile>('/api/me/profile'));

export const updateProfile = async (p: ProfileUpdate): Promise<Profile> => profileView(await put<Profile>('/api/me/profile', p));

/** 驳回原因是审核员填写的原文，后端不分语言 */
export const getKyc = async (): Promise<KycInfo> => kycView(await get<KycInfo>('/api/me/kyc'));

export async function submitKyc(s: KycSubmit): Promise<KycInfo> {
  const form = new FormData();
  form.append('docType', s.docType);
  form.append('docNumber', s.docNumber);
  form.append('front', s.front);
  form.append('back', s.back);
  form.append('selfie', s.selfie);
  return kycView(await post<KycInfo>('/api/me/kyc', undefined, { form }));
}

export const getNotifyPrefs = (): Promise<NotifyPrefs> => get<NotifyPrefs>('/api/me/notify-prefs');

export const updateNotifyPrefs = (p: NotifyPrefs): Promise<NotifyPrefs> => put<NotifyPrefs>('/api/me/notify-prefs', { push: p.push, email: p.email });
