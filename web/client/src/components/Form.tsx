/**
 * 表单基础组件：每个输入都有可见标签，错误一次显示全部（规则 C56），
 * 错误通过 aria-describedby 关联，读屏可读。不使用 datalist 等浏览器自带弹出控件（规则 C47）。
 */
import { useEffect, useId, useRef, useState, type InputHTMLAttributes, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from './Icon';
import { useBodyLock } from './useBodyLock';

export function FieldErrors({ id, errors }: { id: string; errors?: string[] }) {
  const { t } = useTranslation();
  if (!errors || errors.length === 0) return null;
  return (
    <ul className="field-errors" id={id}>
      {errors.map((k) => (
        <li key={k}>{t(k)}</li>
      ))}
    </ul>
  );
}

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange'> {
  label: ReactNode;
  value: string;
  onChange: (v: string) => void;
  errors?: string[];
  hint?: ReactNode;
  suffix?: ReactNode;
  /** 输入框右侧的按钮，例如"发送验证码" */
  addon?: ReactNode;
  labelExtra?: ReactNode;
  mono?: boolean;
}

export function TextField({ label, value, onChange, errors, hint, suffix, addon, labelExtra, mono, id, className, ...rest }: TextFieldProps) {
  const auto = useId();
  const inputId = id ?? auto;
  const errId = `${inputId}-err`;
  const hintId = `${inputId}-hint`;
  const invalid = !!errors?.length;
  const describedBy = [invalid ? errId : '', hint ? hintId : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`field${invalid ? ' invalid' : ''}${className ? ` ${className}` : ''}`}>
      <div className="field-label">
        <label htmlFor={inputId}>{label}</label>
        {labelExtra}
      </div>
      <div className="field-row">
        <div className="input-wrap">
          <input id={inputId} className={mono ? 'mono' : undefined} value={value} onChange={(e) => onChange(e.target.value)} aria-invalid={invalid} aria-describedby={describedBy} {...rest} />
          {suffix && <span className="input-suffix">{suffix}</span>}
        </div>
        {addon}
      </div>
      {hint && (
        <div className="field-hint" id={hintId}>
          {hint}
        </div>
      )}
      <FieldErrors id={errId} errors={errors} />
    </div>
  );
}

export function PasswordField(props: Omit<TextFieldProps, 'type' | 'suffix'>) {
  const { t } = useTranslation();
  const [show, setShow] = useState(false);
  return (
    <TextField
      {...props}
      type={show ? 'text' : 'password'}
      suffix={
        <button type="button" className="link-btn" onClick={() => setShow((s) => !s)} aria-pressed={show}>
          {show ? t('common.hide') : t('common.show')}
        </button>
      }
    />
  );
}

export interface Option<V extends string> {
  value: V;
  label: string;
  hint?: string;
}

/**
 * 自定义下拉选择：点击后在页面内弹出列表（手机为底部面板），支持搜索和键盘上下选择。
 */
export function Select<V extends string>(props: {
  label: ReactNode;
  value: V | '';
  options: Option<V>[];
  onChange: (v: V) => void;
  placeholder?: string;
  errors?: string[];
  searchable?: boolean;
  id?: string;
}) {
  const { t } = useTranslation();
  const auto = useId();
  const id = props.id ?? auto;
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  useBodyLock(open);
  const current = props.options.find((o) => o.value === props.value);
  const filtered = props.options.filter((o) => !q || o.label.toLowerCase().includes(q.toLowerCase()) || o.value.toLowerCase().includes(q.toLowerCase()));
  const invalid = !!props.errors?.length;

  useEffect(() => {
    if (open) {
      setActive(Math.max(0, filtered.findIndex((o) => o.value === props.value)));
    }
    // 只在打开时定位到当前值
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active, open]);

  const choose = (v: V) => {
    props.onChange(v);
    setOpen(false);
    setQ('');
    btnRef.current?.focus();
  };

  const onKey = (e: ReactKeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((a) => Math.min(filtered.length - 1, a + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === 'Enter' && filtered[active]) {
      e.preventDefault();
      choose(filtered[active].value);
    } else if (e.key === 'Escape') {
      setOpen(false);
      btnRef.current?.focus();
    }
  };

  return (
    <div className={`field${invalid ? ' invalid' : ''}`}>
      <div className="field-label">
        <label id={`${id}-label`} htmlFor={id}>
          {props.label}
        </label>
      </div>
      <button
        ref={btnRef}
        id={id}
        type="button"
        className="select-btn"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-invalid={invalid}
        aria-describedby={invalid ? `${id}-err` : undefined}
        onClick={() => setOpen(true)}
      >
        <span className={current ? '' : 'placeholder'}>{current?.label ?? props.placeholder ?? t('common.pleaseSelect')}</span>
        <Icon name="chevronDown" size={16} />
      </button>
      <FieldErrors id={`${id}-err`} errors={props.errors} />
      {open && (
        <div className="popover-root" onKeyDown={onKey}>
          <div className="modal-mask" onClick={() => setOpen(false)} />
          <div className="popover" role="dialog" aria-labelledby={`${id}-label`}>
            {props.searchable && (
              <div className="popover-search">
                <Icon name="search" size={14} />
                <input autoFocus value={q} onChange={(e) => { setQ(e.target.value); setActive(0); }} placeholder={t('common.search')} aria-label={t('common.search')} />
              </div>
            )}
            <ul ref={listRef} role="listbox" aria-labelledby={`${id}-label`} tabIndex={props.searchable ? -1 : 0} autoFocus={!props.searchable} aria-activedescendant={filtered[active] ? `${id}-opt-${active}` : undefined}>
              {filtered.length === 0 && <li className="popover-empty">{t('common.noMatch')}</li>}
              {filtered.map((o, i) => (
                <li
                  key={o.value}
                  id={`${id}-opt-${i}`}
                  data-index={i}
                  role="option"
                  aria-selected={o.value === props.value}
                  className={`${i === active ? 'active' : ''}${o.value === props.value ? ' selected' : ''}`}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => choose(o.value)}
                >
                  <span>{o.label}</span>
                  {o.hint && <small>{o.hint}</small>}
                  {o.value === props.value && <Icon name="check" size={14} />}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}

export function Switch({ checked, onChange, label, id, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; id?: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" id={id} aria-checked={checked} aria-label={label} className={`switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)} disabled={disabled}>
      <span />
    </button>
  );
}

/** 分段选择（市价/限价、网络选择等），用 radiogroup 语义，方向键可切换 */
export function Segmented<V extends string>({ value, options, onChange, label, className }: { value: V; options: Option<V>[]; onChange: (v: V) => void; label: string; className?: string }) {
  const onKey = (e: ReactKeyboardEvent, i: number) => {
    const n = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    if (!n) return;
    e.preventDefault();
    const next = options[(i + n + options.length) % options.length];
    onChange(next.value);
    (e.currentTarget.parentElement?.children[(i + n + options.length) % options.length] as HTMLElement | undefined)?.focus();
  };
  return (
    <div className={`seg${className ? ` ${className}` : ''}`} role="radiogroup" aria-label={label}>
      {options.map((o, i) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          tabIndex={o.value === value ? 0 : -1}
          className={o.value === value ? 'on' : ''}
          onClick={() => onChange(o.value)}
          onKeyDown={(e) => onKey(e, i)}
        >
          {o.label}
          {o.hint && <small>{o.hint}</small>}
        </button>
      ))}
    </div>
  );
}

/** 筛选标签组 */
export function Chips<V extends string>({ value, options, onChange, label }: { value: V; options: Option<V>[]; onChange: (v: V) => void; label: string }) {
  return (
    <div className="chips" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" className={`chip${o.value === value ? ' on' : ''}`} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
