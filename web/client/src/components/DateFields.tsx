/**
 * "已知日期"输入（出生日期等）：年 / 月 / 日三个数字框（英文界面 日 / 月 / 年）。调研和取舍见 `技术方案/v2-日期输入.md`。
 * - 每个框都是数字键盘（inputMode="numeric" + pattern），手机上不需要输入"-"；
 * - 一格填完自动跳到下一格；在下一格立刻按 Tab 不会再跳过一格（自动跳过之后吞掉一次 Tab）；
 * - 空格里按退格回到上一格；输入 - / . 空格 时跳到下一格（电脑上习惯连着打 1990-03-27 的人）；
 * - 整串粘贴或自动填充（1988-05-12、19880512、12/05/1988、1988年5月12日）自动拆到三个框；
 * - 对外 value 仍是 `YYYY-MM-DD` 字符串（没填完时是 `年-月-日` 原样，见 `lib/dateParts.ts`），后端接口不变；
 * - fieldset + legend 作为整体标签，每个框有可见小标签；错误和提示用 aria-describedby 关联，出错的框单独标红。
 */
import { useEffect, useId, useRef, useState, type ClipboardEvent, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { FieldErrors } from './Form';
import { PART_MAX, joinDate, parseDateText, partComplete, partOrder, problemParts, splitDate, toAsciiDigits, type DatePart, type DateParts } from '../lib/dateParts';

const PART_LABEL: Record<DatePart, string> = { y: 'dateFields.year', m: 'dateFields.month', d: 'dateFields.day' };
const AUTOCOMPLETE: Record<DatePart, string> = { y: 'year', m: 'month', d: 'day' };

export interface DateFieldsProps {
  label: ReactNode;
  value: string;
  onChange: (v: string) => void;
  /** 校验错误（翻译键）；有错误时按值找出具体哪一格标红，找不到具体格时三格都标红 */
  errors?: string[];
  hint?: ReactNode;
  /** 浏览器自动填充用的前缀：出生日期传 'bday'（bday-year / bday-month / bday-day） */
  autoCompletePrefix?: 'bday';
  id?: string;
  className?: string;
}

export function DateFields({ label, value, onChange, errors, hint, autoCompletePrefix, id, className }: DateFieldsProps) {
  const { t, i18n } = useTranslation();
  const order = partOrder(i18n.language);
  const auto = useId();
  const base = id ?? auto;
  const errId = `${base}-err`;
  const hintId = `${base}-hint`;
  const refs = useRef<Record<DatePart, HTMLInputElement | null>>({ y: null, m: null, d: null });
  const [parts, setParts] = useState<DateParts>(() => splitDate(value));
  const lastEmitted = useRef(value);
  // 自动跳过来的那一格：紧接着按 Tab 时不再往后跳
  const autoAdvanced = useRef<DatePart | null>(null);

  // 外部改了值（加载资料、重置表单）时同步三个框；自己发出去的值不回写，避免"5"被立刻改成"05"
  useEffect(() => {
    if (value !== lastEmitted.current) {
      lastEmitted.current = value;
      setParts(splitDate(value));
    }
  }, [value]);

  const invalid = !!errors?.length;
  const flagged = invalid ? problemParts(joinDate(parts)) : new Set<DatePart>();
  const allFlagged = invalid && flagged.size === 0;
  const describedBy = [hint ? hintId : '', invalid ? errId : ''].filter(Boolean).join(' ') || undefined;

  const emit = (p: DateParts) => {
    setParts(p);
    const v = joinDate(p);
    lastEmitted.current = v;
    onChange(v);
  };

  const focusPart = (k: DatePart | undefined) => {
    if (!k) return;
    const el = refs.current[k];
    if (!el) return;
    el.focus();
    const n = el.value.length;
    try {
      el.setSelectionRange(n, n);
    } catch {
      /* 个别浏览器对 inputmode 框不支持，忽略 */
    }
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
    // 自动填充或没有触发 paste 事件的粘贴：整串日期一次拆开
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
      const prev = prevOf(k);
      if (prev) {
        e.preventDefault();
        focusPart(prev);
      }
    } else if (e.key === 'Tab' && !e.shiftKey && wasAuto && el.value === '') {
      // 用户填完上一格习惯性按 Tab：已经替他跳过来了，这次 Tab 不再跳
      e.preventDefault();
    } else if (e.key === 'ArrowLeft' && atStart && prevOf(k)) {
      e.preventDefault();
      focusPart(prevOf(k));
    } else if (e.key === 'ArrowRight' && atEnd && nextOf(k)) {
      e.preventDefault();
      focusPart(nextOf(k));
    } else if (['-', '/', '.', ' ', '年', '月'].includes(e.key)) {
      // 分隔符不进框；当前格有内容时跳到下一格
      e.preventDefault();
      if (el.value) focusPart(nextOf(k));
    }
  };

  return (
    <fieldset className={`field date-fields${invalid ? ' has-error' : ''}${className ? ` ${className}` : ''}`} aria-describedby={describedBy}>
      <legend className="field-label">{label}</legend>
      {hint && (
        <div className="field-hint date-hint" id={hintId}>
          {hint}
        </div>
      )}
      <div className="date-row">
        {order.map((k) => {
          const bad = allFlagged || flagged.has(k);
          const inputId = `${base}-${k}`;
          return (
            <div key={k} className={`date-part date-part-${k}`}>
              <label htmlFor={inputId}>{t(PART_LABEL[k])}</label>
              <div className={`input-wrap${bad ? ' invalid' : ''}`}>
                <input
                  ref={(el) => {
                    refs.current[k] = el;
                  }}
                  id={inputId}
                  className="mono"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  autoComplete={autoCompletePrefix ? `${autoCompletePrefix}-${AUTOCOMPLETE[k]}` : 'off'}
                  enterKeyHint={nextOf(k) ? 'next' : 'done'}
                  value={parts[k]}
                  onChange={(e) => onPartChange(k, e.target.value)}
                  onPaste={onPaste}
                  onKeyDown={(e) => onKeyDown(k, e)}
                  aria-invalid={bad}
                  aria-describedby={describedBy}
                  data-part={k}
                />
              </div>
            </div>
          );
        })}
      </div>
      <FieldErrors id={errId} errors={errors} />
    </fieldset>
  );
}
