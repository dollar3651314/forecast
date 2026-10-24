import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { act } from 'react';
import { ApiError } from '../../api/errors';
import { setProtoState } from '../../api/prototype';
import { resetDb } from '../../mocks/db';
import { renderPage } from '../../test/renderApp';
import Withdraw from './Withdraw';

// 其余接口用原型模拟数据，只把提交换成"后端返回字段错误"
const submitWithdraw = vi.fn();
vi.mock('../../api/wallet', async (orig) => ({ ...(await orig<typeof import('../../api/wallet')>()), submitWithdraw: (...a: unknown[]) => submitWithdraw(...a) }));

afterEach(() => {
  submitWithdraw.mockReset();
  act(() => setProtoState({ scenario: 'normal' }));
  resetDb();
});

async function fillAndSubmit(amount: string) {
  renderPage(<Withdraw />, '/assets/withdraw');
  const input = await screen.findByLabelText('提现金额');
  fireEvent.change(input, { target: { value: amount } });
  fireEvent.click(screen.getByRole('button', { name: '提交提现' }));
  fireEvent.click(await screen.findByRole('button', { name: '确认提交' }));
  return input;
}

describe('提现：后端返回的字段错误', () => {
  it('显示在对应输入框下面，改了这个字段就消失', async () => {
    submitWithdraw.mockRejectedValue(new ApiError('validationFailed', { amount: ['validation.amountDigits'] }, 400));
    const input = await fillAndSubmit('100');
    const field = input.closest('.field') as HTMLElement;
    expect(await within(field).findByText('金额最多 2 位小数')).toBeInTheDocument();
    expect(screen.getByText('请修改以上标红的内容后再提交')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '101' } });
    await waitFor(() => expect(within(field).queryByText('金额最多 2 位小数')).not.toBeInTheDocument());
  });

  it('地址的字段错误显示在地址选择下面', async () => {
    submitWithdraw.mockRejectedValue(new ApiError('validationFailed', { whitelistId: ['validation.selectAddress'] }, 400));
    await fillAndSubmit('100');
    expect(await screen.findByText('请选择白名单地址')).toBeInTheDocument();
  });

  it('不是字段错误的（例如超过可提金额）仍然显示在表单下方', async () => {
    submitWithdraw.mockRejectedValue(new ApiError('withdrawInsufficient', undefined, 400));
    await fillAndSubmit('100');
    expect(await screen.findByText('超过可提金额')).toBeInTheDocument();
    expect(screen.getByLabelText('提现金额').closest('.field')).not.toHaveClass('invalid');
  });
});
