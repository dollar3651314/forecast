/**
 * 三种语言（简体、英文、繁体）并排的输入框（市场题目、选项、补充说明、规则文本模板共用）；空的标红。
 * multiline 时用多行输入（规则全文、补充说明）。
 */
import { Input } from 'antd';
import type { I18nText } from '../api/fcTypes';

export const LANGS: Array<{ k: keyof I18nText; label: string }> = [
  { k: 'zhCN', label: '简体' },
  { k: 'en', label: 'EN' },
  { k: 'zhTW', label: '繁體' },
];

export const EMPTY_TEXT: I18nText = { zhCN: '', en: '', zhTW: '' };

export function I18nInputs({
  value,
  onChange,
  placeholder,
  multiline,
  rows = 4,
  testId,
}: {
  value: I18nText;
  onChange: (v: I18nText) => void;
  placeholder?: string;
  multiline?: boolean;
  rows?: number;
  testId?: string;
}) {
  return (
    <div className={multiline ? 'i18n-inputs i18n-multi' : 'i18n-inputs'} data-testid={testId}>
      {LANGS.map((l) =>
        multiline ? (
          <label key={l.k} className="i18n-ml">
            <span className="i18n-tag">{l.label}</span>
            <Input.TextArea rows={rows} value={value[l.k]} status={value[l.k].trim() ? undefined : 'error'} placeholder={placeholder} aria-label={`${placeholder ?? ''} ${l.label}`} onChange={(e) => onChange({ ...value, [l.k]: e.target.value })} />
          </label>
        ) : (
          <Input
            key={l.k}
            addonBefore={l.label}
            value={value[l.k]}
            status={value[l.k].trim() ? undefined : 'error'}
            placeholder={placeholder}
            aria-label={`${placeholder ?? ''} ${l.label}`}
            onChange={(e) => onChange({ ...value, [l.k]: e.target.value })}
          />
        ),
      )}
    </div>
  );
}

/** 三种语言只读显示 */
export function I18nView({ value }: { value: I18nText }) {
  return (
    <div className="i18n-view">
      {LANGS.map((l) => (
        <div key={l.k}>
          <span className="i18n-tag">{l.label}</span>
          <span className="i18n-text">{value[l.k] || '—'}</span>
        </div>
      ))}
    </div>
  );
}
