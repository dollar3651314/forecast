/**
 * "从 Cloudflare 导入"弹窗里的文件选择（forecast S19，P15）：选 Cloudflare 上下载的 export.json，在浏览器里读出来、检查格式，
 * 保存前就把问题标在这一项下面（C108 第 4 条）：不是 JSON、不是 Cloudflare 的导出格式、一个都没有。
 * 文件只在浏览器里读，提交给后端（POST /api/admin/waitlist/import）；不上传到别处。
 */
import { useState } from 'react';
import { Alert, Form } from 'antd';
import { useTranslation } from 'react-i18next';
import { CF_IMPORT_FORMAT, type CfImportFile } from '../../api/fcTypes';

/** 读出来的结果：ok 时有 file；否则 error 是翻译键 */
export type PickedFile = { ok: true; name: string; file: CfImportFile } | { ok: false; name: string; error: string };

export function parseCfFile(name: string, text: string): PickedFile {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    return { ok: false, name, error: 'wl.import.notJson' };
  }
  const f = data as Partial<CfImportFile> | null;
  if (!f || typeof f !== 'object' || f.format !== CF_IMPORT_FORMAT || !Array.isArray(f.items)) return { ok: false, name, error: 'wl.import.wrongFormat' };
  if (f.items.length === 0) return { ok: false, name, error: 'wl.import.empty' };
  return { ok: true, name, file: f as CfImportFile };
}

/** 用 FileReader 读成文字（各浏览器都支持） */
function readText(f: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result ?? ''));
    r.onerror = () => reject(r.error);
    r.readAsText(f, 'utf-8');
  });
}

function Picker({ value, onChange }: { value?: PickedFile; onChange?: (v: PickedFile | undefined) => void }) {
  const { t } = useTranslation();
  const [reading, setReading] = useState(false);
  const counts = value?.ok
    ? { n: value.file.items.length, c: value.file.items.filter((i) => i.status === 'confirmed').length, u: value.file.items.filter((i) => i.status === 'unsubscribed').length }
    : null;
  return (
    <div>
      <input
        type="file"
        accept=".json,application/json"
        aria-label={t('wl.import.file')}
        data-testid="wl-import-file"
        onChange={async (e) => {
          const f = e.target.files?.[0];
          if (!f) return onChange?.(undefined);
          setReading(true);
          try {
            onChange?.(parseCfFile(f.name, await readText(f)));
          } catch {
            onChange?.({ ok: false, name: f.name, error: 'wl.import.notJson' });
          } finally {
            setReading(false);
          }
        }}
      />
      {reading ? <div className="muted">{t('common.loading')}</div> : null}
      {counts ? (
        <div className="muted" data-testid="wl-import-summary" style={{ marginTop: 6 }}>
          {t('wl.import.summary', { n: counts.n, c: counts.c, u: counts.u })}
        </div>
      ) : null}
      {value?.ok && value.file.next ? <Alert type="warning" showIcon style={{ marginTop: 8 }} message={t('wl.import.hasNext')} data-testid="wl-import-next" /> : null}
    </div>
  );
}

export function CfImportField() {
  const { t } = useTranslation();
  return (
    <Form.Item
      name="file"
      label={t('wl.import.file')}
      extra={t('wl.import.fileHint')}
      rules={[
        {
          validator: (_r, v: PickedFile | undefined) => {
            if (!v) return Promise.reject(new Error(t('wl.import.fileRequired')));
            return v.ok ? Promise.resolve() : Promise.reject(new Error(t(v.error)));
          },
        },
      ]}
    >
      <Picker />
    </Form.Item>
  );
}
