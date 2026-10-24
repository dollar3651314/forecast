/** 高危确认弹窗（规则 C46）：必填原因 + 勾选确认，一次显示全部错误；失败时弹窗保持打开并显示原因 */
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { HighRiskModal } from './HighRiskModal';
import { renderAs } from '../test/render';
import { ApiError } from '../api/types';

function setup(onConfirm = vi.fn().mockResolvedValue(undefined)) {
  const onCancel = vi.fn();
  renderAs(<HighRiskModal open title="冻结客户" description="影响说明" onCancel={onCancel} onConfirm={onConfirm} />);
  return { onConfirm, onCancel };
}

describe('HighRiskModal', () => {
  it('不填原因、不勾选时一次显示两条错误，不执行操作', async () => {
    const { onConfirm } = setup();
    fireEvent.click(screen.getByRole('button', { name: '确认执行' }));
    expect(await screen.findByText('请填写操作原因')).toBeInTheDocument();
    expect(await screen.findByText('请勾选确认')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('只填空格也算没填', async () => {
    const { onConfirm } = setup();
    fireEvent.change(screen.getByLabelText('操作原因'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认执行' }));
    expect(await screen.findByText('请填写操作原因')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('填写原因并勾选后执行，原因去掉首尾空格', async () => {
    const { onConfirm } = setup();
    fireEvent.change(screen.getByLabelText('操作原因'), { target: { value: '  客户申请冻结  ' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认执行' }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({ reason: '客户申请冻结' }));
  });

  it('接口失败时显示具体原因', async () => {
    setup(vi.fn().mockRejectedValue(new ApiError('errors.forbidden')));
    fireEvent.change(screen.getByLabelText('操作原因'), { target: { value: '测试' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认执行' }));
    expect(await screen.findByText('当前账号没有这个操作的权限。')).toBeInTheDocument();
  });
});
