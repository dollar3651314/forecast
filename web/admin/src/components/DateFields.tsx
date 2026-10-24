/**
 * 后台的"已知日期"输入（编辑客户资料的出生日期等）：年 / 月 / 日三个 antd 数字框（英文界面 日 / 月 / 年）。
 * 行为和客户端 `web/client/src/components/DateFields.tsx` 一致（自动跳格、退格回退、整串粘贴、逐框标红），
 * 纯函数在 `utils/dateParts.ts`。调研和取舍见 `技术方案/v2-日期输入.md`。
 *
 * 用法：放在 `Form.Item` 里，表单值仍是 `YYYY-MM-DD` 字符串（没填完时是 `年-月-日` 原样，由校验规则报错）：
 *   <Form.Item name="birthDate" label="出生日期" rules={...}><DateFields groupLabel="出生日期" /></Form.Item>
 * 选日历上的某一天（休市日、筛选）不用它，用 antd DatePicker。
 */
import { useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Form, Input, type InputRef } from 'antd';
import { useTranslation } from 'react-i18next';
import { PART_MAX, joinDate, parseDateText, partComplete, partOrder, problemParts, splitDate, toAsciiDigits, type DatePart, type DateParts } from '../utils/dateParts';

const PART_LABEL: Record<DatePart, string> = { y: 'dateFields.year', m: 'dateFields.month', d: 'dateFields.day' };
const AUTOCOMPLETE: Record<DatePart, string> = { y: 'year', m: 'month', d: 'day' };

export interface DateFieldsProps {
  /** Form.Item 注入 */
  value?: string;
  /** Form.Item 注入 */
  onChange?: (v: string) => void;
  /** Form.Item 注入：放在第一个框上，点击表单标签时聚焦第一个框 */
  id?: string;
  /** 整组的读屏名称（和 Form.Item 的 label 一样） */
  groupLabel: string;
  /** 浏览器自动填充前缀；后台替别人填，默认关闭 */
  autoCompletePrefix?: 'bday';
  disabled?: boolean;
  /** Form.Item 注入：出错时指向错误提示（`<id>_help`），三个框都带上，读屏能读到错误 */
  'aria-describedby'?: string;
}

export function DateFields({ value = '', onChange, id, groupLabel, autoCompletePrefix, disabled, 'aria-describedby': describedBy }: DateFieldsProps) {
  const { t, i18n } = useTranslation();
  const order = partOrder(i18n.language);
  const { status } = Form.Item.useStatus();
  const refs = useRef<Record<DatePart, InputRef | null>>({ y: null, m: null, d: null });
  const [parts, setParts] = useState<DateParts>(() => splitDate(value));
  const lastEmitted = useRef(value);
  const autoAdvanced = useRef<DatePart | null>(null);

  useEffect(() => {
    if (value !== lastEmitted.current) {
      lastEmitted.current = value;
      setParts(splitDate(value));
    }
  }, [value]);

  const invalid = status === 'error';
  const flagged = invalid ? problemParts(joinDate(parts)) : new Set<DatePart>();
  const allFlagged = invalid && flagged.size === 0;

  const emit = (p: DateParts) => {
    setParts(p);
    const v = joinDate(p);
    lastEmitted.current = v;
    onChange?.(v);
  };
  const focusPart = (k: DatePart | undefined) => {
    if (!k) return;
    refs.current[k]?.focus({ cursor: 'end' });
  };
  const nextOf = (k: DatePart) => order[order.indexOf(k) + 1];
  const prevOf = (k: DatePart) => order[order.indexOf(k) - 1];
  const fillAll = (p: DateParts) => {
    autoAdvanced.current = null;
    emit(p);
    focusPart(order[order.length - 1]);
  };

  const onPartChange = (k: DatePart, raw: string) => {
    const text = toAsciiDigits(raw);
    const digits = text.replace(/\D/g, '');
    if (digits.length > PART_MAX[k] || (/\D/.test(text) && digits.length >= 6)) {
      const parsed = parseDateText(text, order);
      if (parsed) {
        fillAll(parsed);
        return;
      }
    }
    const v = digits.slice(0, PART_MAX[k]);
    const grew = v.length > parts[k].length;
    emit({ ...parts, [k]: v });
    const next = nextOf(k);
    if (grew && next && partComplete(k, v)) {
      autoAdvanced.current = next;
      focusPart(next);
    }
  };

  const onPaste = (e: ClipboardEvent<HTMLInputElement>) => {
    const parsed = parseDateText(e.clipboardData.getData('text'), order);
    if (parsed) {
      e.preventDefault();
      fillAll(parsed);
    }
  };

  const onKeyDown = (k: DatePart, e: ReactKeyboardEvent<HTMLInputElement>) => {
    const el = e.currentTarget;
    const wasAuto = autoAdvanced.current === k;
    autoAdvanced.current = null;
    const atStart = el.selectionStart === 0 && el.selectionEnd === 0;
    const atEnd = el.selectionStart === el.value.length;
    if (e.key === 'Backspace' && el.value === '') {
      if (prevOf(k)) {
        e.preventDefault();
        focusPart(prevOf(k));
      }
    } else if (e.key === 'Tab' && !e.shiftKey && wasAuto && el.value === '') {
      e.preventDefault();
    } else if (e.key === 'ArrowLeft' && atStart && prevOf(k)) {
      e.preventDefault();
      focusPart(prevOf(k));
    } else if (e.key === 'ArrowRight' && atEnd && nextOf(k)) {
      e.preventDefault();
      focusPart(nextOf(k));
    } else if (['-', '/', '.', ' ', '年', '月'].includes(e.key)) {
      e.preventDefault();
      if (el.value) focusPart(nextOf(k));
    }
  };

  return (
    <div className="date-fields" role="group" aria-label={groupLabel}>
      {order.map((k, i) => {
        const bad = allFlagged || flagged.has(k);
        return (
          <label key={k} className={`date-part date-part-${k}`}>
            <span className="date-part-label">{t(PART_LABEL[k])}</span>
            {/* 外层 Form.Item 出错时 antd 会把三个框都标红；每个框套一层 noStyle 的 Form.Item 自己决定标不标（只标有问题的那一格） */}
            <Form.Item noStyle validateStatus={bad ? 'error' : ''}>
              <Input
                ref={(el) => {
                  refs.current[k] = el;
                }}
                id={i === 0 ? id : id ? `${id}_${k}` : undefined}
                className="num"
                inputMode="numeric"
                pattern="[0-9]*"
                autoComplete={autoCompletePrefix ? `${autoCompletePrefix}-${AUTOCOMPLETE[k]}` : 'off'}
                value={parts[k]}
                aria-invalid={bad}
                aria-describedby={describedBy}
                disabled={disabled}
                onChange={(e) => onPartChange(k, e.target.value)}
                onPaste={onPaste}
                onKeyDown={(e) => onKeyDown(k, e)}
                data-part={k}
              />
            </Form.Item>
          </label>
        );
      })}
    </div>
  );
}
