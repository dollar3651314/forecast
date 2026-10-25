/**
 * 统一的数据表格：加载中、空状态、出错（原因 + 重试）、分页、表格内部横向滚动（页面本身不出现横向滚动）。
 */
import { Empty, Grid, Table, type TableProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { ErrorState } from './ErrorState';

export interface DataTableProps<T> extends Omit<TableProps<T>, 'pagination'> {
  error?: unknown;
  onRetry?: () => void;
  /** 传了 total 就显示分页 */
  total?: number;
  page?: number;
  pageSize?: number;
  onPageChange?: (page: number, pageSize: number) => void;
  emptyText?: string;
  bare?: boolean;
}

export function DataTable<T extends object>({ error, onRetry, total, page, pageSize, onPageChange, emptyText, bare, columns, ...rest }: DataTableProps<T>) {
  const { t } = useTranslation();
  const screens = Grid.useBreakpoint();
  // 手机上不固定左右列，避免固定列挤占可视宽度
  const cols = screens.md === false ? columns?.map((c) => ({ ...c, fixed: undefined })) : columns;
  const body = error ? (
    <ErrorState error={error} onRetry={onRetry ?? (() => undefined)} compact />
  ) : (
    <Table<T>
      size="middle"
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText ?? t('common.empty')} /> }}
      pagination={
        total !== undefined
          ? {
              current: page,
              pageSize,
              total,
              onChange: onPageChange,
              showSizeChanger: true,
              pageSizeOptions: [8, 10, 20, 50],
              showTotal: (n) => t('common.totalWithSize', { count: n, size: pageSize }),
              size: 'small',
              responsive: true,
            }
          : false
      }
      columns={cols}
      {...rest}
    />
  );
  return bare ? body : <div className="card table-card">{body}</div>;
}
