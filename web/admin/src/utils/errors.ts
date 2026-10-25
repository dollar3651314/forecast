import type { TFunction } from 'i18next';
import { ApiError } from '../api/types';

/**
 * 把接口错误转成给人看的具体原因（不显示错误代码）。
 * 后端返回字段错误（VALIDATION_FAILED 的 fields）时，逐个说明"哪个字段、什么问题"；不认识的字段和问题按原名显示。
 */
export function errorText(t: TFunction, err: unknown): string {
  if (!(err instanceof ApiError)) return t('errors.unknown');
  const base = t(err.reasonKey, err.params ?? {});
  if (!err.fields?.length || err.reasonKey !== 'errors.validationFailed') return base;
  const details = err.fields.map((f) =>
    t('errors.fieldProblem', {
      field: t(`apiField.${f.field}`, { defaultValue: f.field }),
      problem: t(`apiFieldCode.${f.code}`, { defaultValue: f.code }),
    }),
  );
  return `${base}${details.join('；')}`;
}
