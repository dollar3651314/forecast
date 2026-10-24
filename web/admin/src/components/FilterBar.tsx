import type { ReactNode } from 'react';
import { Button } from 'antd';
import { useTranslation } from 'react-i18next';

/** 筛选条：表单项自动换行，右侧"重置 / 查询" */
export function FilterBar({ children, onSearch, onReset }: { children: ReactNode; onSearch: () => void; onReset: () => void }) {
  const { t } = useTranslation();
  return (
    <form
      className="card filter-bar"
      role="search"
      onSubmit={(e) => {
        e.preventDefault();
        onSearch();
      }}
    >
      <div className="filter-fields">{children}</div>
      <div className="filter-actions">
        <Button onClick={onReset}>{t('common.reset')}</Button>
        <Button type="primary" htmlType="submit">
          {t('common.search')}
        </Button>
      </div>
    </form>
  );
}

/** 筛选项：标签 + 控件 */
export function FilterItem({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return (
    <div className="filter-item">
      <label htmlFor={htmlFor}>{label}</label>
      {children}
    </div>
  );
}
