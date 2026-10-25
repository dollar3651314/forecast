import type { ReactNode } from 'react';

/** 页面标题行：标题 + 灰色说明 + 右侧操作（与效果图 M6/M7 一致） */
export function PageHeader({ title, sub, extra }: { title: ReactNode; sub?: ReactNode; extra?: ReactNode }) {
  return (
    <div className="page-header">
      <div className="page-header-text">
        <h1>{title}</h1>
        {sub ? <span className="page-sub">{sub}</span> : null}
      </div>
      {extra ? <div className="page-header-extra">{extra}</div> : null}
    </div>
  );
}
