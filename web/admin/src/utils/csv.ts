/** 报表导出（forecast S9）：在浏览器里把一张表生成 CSV 下载，不经过服务器。 */

/** 生成 CSV 文字：逗号、引号、换行按 RFC 4180 转义 */
export function toCsv(rows: Array<Array<string | number>>): string {
  const esc = (v: string | number) => {
    const s = String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return rows.map((r) => r.map(esc).join(',')).join('\n');
}

/** 生成 CSV 并下载（加 BOM，Excel 打开中文不乱码） */
export function downloadCsv(name: string, rows: Array<Array<string | number>>): void {
  const url = URL.createObjectURL(new Blob(['﻿' + toCsv(rows)], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
