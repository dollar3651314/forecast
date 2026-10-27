/**
 * forecast S12c 测试环境反馈：加密自动生成的场次填 "1:00"，页面显示"北京 09:00"，保存却报"场次格式不正确"。
 * 修复后：输入宽松（1:00、1:0、1、01:00、两边空格都是 01:00），失焦时规范成 HH:mm；规则和后端 AutogenService.parseSlot 是同一套；
 * 有问题的行当场标红、写明"第 n 行：错在哪"；保存时弹窗里的错误也说清是哪一行。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import i18n from './i18n';
import { renderAs } from './test/render';
import { ApiError } from './api/types';
import { listAutogens } from './api';
import { resetFcMocks } from './mocks/fc';
import { AutogenPage } from './pages/markets/AutogenPage';
import { beijing, beijingNextDay, normalizeSlot, normalizeSlotTime, slotProblems } from './utils/market';
import { errorText } from './utils/errors';

beforeEach(() => resetFcMocks());

describe('场次时刻的宽松读入（和后端 AutogenWindowsTest 同一组用例）', () => {
  it('各种写法都规范成 HH:mm', () => {
    for (const s of ['1:00', '1:0', '01:00', '1', '01', ' 1:00 ', '\t01:00　', '1：00', '1 : 00', ' 1:00']) {
      expect(normalizeSlotTime(s), s).toEqual({ time: '01:00' });
    }
    expect(normalizeSlotTime('0')).toEqual({ time: '00:00' });
    expect(normalizeSlotTime('23:59')).toEqual({ time: '23:59' });
    expect(normalizeSlotTime('9:5')).toEqual({ time: '09:05' });
    expect(normalizeSlot(' 1:00@7,6')).toBe('01:00@6,7');
    expect(normalizeSlot('1@6')).toBe('01:00@6');
  });

  it('不合法的写法给出和后端一样的错误码', () => {
    expect(normalizeSlotTime('').problem).toBe('required');
    expect(normalizeSlotTime('   ').problem).toBe('required');
    for (const s of ['abc', '1:', ':00', '1:000', '100', '01-00', '1.00', '1:00pm', '-1:00', '1 00', '１:00']) expect(normalizeSlotTime(s).problem, s).toBe('timeFormat');
    for (const s of ['24:00', '25:00', '12:60', '99:99']) expect(normalizeSlotTime(s).problem, s).toBe('outOfRange');
    for (const s of ['13:00@', '13:00@8', '13:00@0', '13:00@6,6', '13:00@6,', '13:00@a']) expect(slotProblems([s], '1H')[0], s).toBe('invalidDays');
  });

  it('逐行检查：1 小时只能整点、15 分钟要是 15 的倍数、不能重复（和后端 CryptoAutogenTest 同一组）', () => {
    expect(slotProblems(['13:00', '1:30', 'abc', '24:00', '01', '', '13:00@9'], '1H')).toEqual([null, 'hourOnly', 'timeFormat', 'outOfRange', null, 'required', 'invalidDays']);
    expect(slotProblems(['1:00', '01:00@6'], '1H')).toEqual([null, 'duplicate']);
    expect(slotProblems(['13:07', '13:15', '13:15', '1:45'], '15M')).toEqual(['quarterOnly', null, 'duplicate', null]);
  });

  it('北京时间和后端用同一套解析：后端认的才显示时间，后端不认的显示 —', () => {
    expect(beijing('1:00')).toBe('09:00');
    expect(beijing('1')).toBe('09:00');
    expect(beijing(' 16:30 ')).toBe('00:30');
    expect(beijingNextDay('16')).toBe(true);
    expect(beijingNextDay('9:00')).toBe(false);
    for (const s of ['abc', '25:00', '1:', '']) expect(beijing(s), s).toBe('—');
  });

  it('保存时的错误写明第几行、错在哪', () => {
    const t = i18n.getFixedT('zh-CN');
    const err = new ApiError('errors.validationFailed', undefined, {
      status: 400,
      code: 'VALIDATION_FAILED',
      fields: [
        { field: 'slots[1]', code: 'hourOnly' },
        { field: 'slots[2]', code: 'timeFormat' },
      ],
    });
    expect(errorText(t, err)).toBe('提交的内容有误：场次第 2 行：1 小时的场次只能是整点（例如 01:00）；场次第 3 行：看不懂这个时间（写成 HH:mm，例如 01:00；也可以只写小时，例如 1）');
    const en = i18n.getFixedT('en');
    expect(errorText(en, err)).toContain('Slots row 2: must be on the hour for 1-hour markets (e.g. 01:00)');
  });
});

describe('场次编辑（AutogenPage）', () => {
  async function openEthEdit() {
    renderAs(<AutogenPage />, { path: '/markets/autogen' });
    await screen.findByText('13:00 UTC · 北京 21:00 · 每天');
    // 第二张卡片 ETHUSDT 15 分钟（关闭中，可以改）：改成 1 小时、按场次
    fireEvent.click(screen.getAllByRole('button', { name: /编辑/ })[1]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByText('1 小时'));
    fireEvent.click(within(dialog).getByText('按场次（每天固定时刻）'));
    return dialog;
  }

  function slotInputs(dialog: HTMLElement): HTMLInputElement[] {
    return within(within(dialog).getByTestId('slot-editor')).getAllByLabelText('时刻（UTC）') as HTMLInputElement[];
  }

  it('填 1:00：显示北京 09:00，失焦后变成 01:00；不整点的行标红并写明第几行；改对以后保存成功', async () => {
    const dialog = await openEthEdit();
    const editor = within(dialog).getByTestId('slot-editor');
    fireEvent.click(within(editor).getByText('加一个时刻'));
    fireEvent.click(within(editor).getByText('加一个时刻'));
    let inputs = slotInputs(dialog);
    expect(inputs).toHaveLength(2);
    // 第 2 行和第 1 行都是默认的 13:00：重复，当场标红
    expect(within(editor).getByText('第 2 行：和前面的场次重复了')).toBeInTheDocument();
    fireEvent.change(inputs[1], { target: { value: '1:00' } });
    expect(within(editor).getByText('09:00', { exact: false })).toBeInTheDocument();
    fireEvent.blur(inputs[1]);
    inputs = slotInputs(dialog);
    expect(inputs[1].value).toBe('01:00');
    expect(within(editor).queryByRole('alert')).toBeNull();
    // 再加一行 1:30：1 小时只能整点
    fireEvent.click(within(editor).getByText('加一个时刻'));
    inputs = slotInputs(dialog);
    fireEvent.change(inputs[2], { target: { value: '1:30' } });
    fireEvent.blur(inputs[2]);
    expect(slotInputs(dialog)[2].value).toBe('01:30');
    expect(within(editor).getByRole('alert')).toHaveTextContent('第 3 行：1 小时的场次只能是整点（例如 01:00）');
    expect(slotInputs(dialog)[2].closest('.ant-input-affix-wrapper')!.className).toContain('ant-input-status-error');
    // 带着错误点"确认执行"：不提交，弹窗里写明第几行
    fireEvent.change(within(dialog).getByLabelText('操作原因'), { target: { value: '加 01:00 UTC 一场' } });
    fireEvent.click(within(dialog).getByText(/我已核对操作对象/).closest('label')!.querySelector('input')!);
    fireEvent.click(within(dialog).getByText('确认执行').closest('button')!);
    expect(await within(dialog).findByText('提交的内容有误：场次第 3 行：1 小时的场次只能是整点（例如 01:00）')).toBeInTheDocument();
    expect((await listAutogens())[1].period).toBe('15M');
    // 删掉第 3 行，第 2 行改回没失焦的 "1:00"（保存时也会规范化）再保存
    fireEvent.click(within(editor).getAllByRole('button', { name: '删除' })[2]);
    fireEvent.change(slotInputs(dialog)[1], { target: { value: ' 1:00 ' } });
    fireEvent.click(within(dialog).getByText('确认执行').closest('button')!);
    await waitFor(async () => expect((await listAutogens())[1].slots).toEqual(['01:00', '13:00']));
    expect((await listAutogens())[1].period).toBe('1H');
  });
});
