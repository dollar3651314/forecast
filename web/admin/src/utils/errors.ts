import type { TFunction } from 'i18next';
import { ApiError } from '../api/types';

/**
 * 把接口错误转成给人看的具体原因（不显示错误代码）。
 * 后端返回字段错误（VALIDATION_FAILED 的 fields）时，逐个说明"哪个字段、什么问题"；不认识的字段和问题按原名显示。
 */
export function errorText(t: TFunction, err: unknown): string {
  if (!(err instanceof ApiError)) return t('errors.unknown');
  // forecast S15：业务错误码（市场、裁决、内容中心）按 errors.fc.<code> 显示，翻译里可以用后端的说明 {{detail}}
  if (err.reasonKey === 'errors.unknown' && err.code) {
    const fc = t(`errors.fc.${err.code}`, { ...(err.params ?? {}), defaultValue: '' });
    if (fc) return fc;
  }
  const base = t(err.reasonKey, err.params ?? {});
  if (!err.fields?.length || err.reasonKey !== 'errors.validationFailed') return base;
  const details = err.fields.map((f) =>
    t('errors.fieldProblem', {
      // forecast S9：嵌套字段（title.zhTW、options[0].label）去掉序号、点换成下划线再查翻译（i18next 的键用点分层级）
      field: t(`apiField.${f.field.replace(/\[\d+\]/g, '').replace(/\./g, '_')}`, { defaultValue: f.field }),
      problem: t(`apiFieldCode.${f.code}`, { defaultValue: f.code }),
    }),
  );
  return `${base}${details.join('；')}`;
}
