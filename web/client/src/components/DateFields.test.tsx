import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import i18n from '../i18n';
import { validateBirthDate } from '../lib/validators';
import { DateFields } from './DateFields';

let last = '';

function Wrapper({ initial = '', showErrors = false }: { initial?: string; showErrors?: boolean }) {
  const [v, setV] = useState(initial);
  const errs = showErrors ? validateBirthDate(v, new Date(Date.UTC(2026, 9, 16))) : [];
  return (
    <>
      <DateFields
        label="出生日期"
        hint="例如 1990 年 3 月 27 日"
        autoCompletePrefix="bday"
        value={v}
        onChange={(x) => {
          last = x;
          setV(x);
        }}
        errors={errs}
      />
      <button type="button" onClick={() => setV('2001-02-03')}>
        外部改值
      </button>
      <output data-testid="out">{v}</output>
    </>
  );
}

const group = () => screen.getByRole('group', { name: '出生日期' });
const box = (name: string) => within(group()).getByLabelText(name);

beforeEach(() => {
  last = '';
});

afterEach(async () => {
  await act(() => i18n.changeLanguage('zh-CN'));
});

describe('DateFields 三框日期', () => {
  it('中文按 年 月 日 排列，都是数字键盘，带自动填充和读屏关联', () => {
    render(<Wrapper />);
    const inputs = within(group()).getAllByRole('textbox');
    expect(inputs.map((i) => i.getAttribute('data-part'))).toEqual(['y', 'm', 'd']);
    for (const i of inputs) {
      expect(i).toHaveAttribute('inputmode', 'numeric');
      expect(i).toHaveAttribute('pattern', '[0-9]*');
    }
    expect(box('年')).toHaveAttribute('autocomplete', 'bday-year');
    expect(box('日')).toHaveAttribute('autocomplete', 'bday-day');
    expect(box('年')).toHaveAccessibleDescription('例如 1990 年 3 月 27 日');
  });

  it('英文按 Day Month Year 排列', async () => {
    await act(() => i18n.changeLanguage('en'));
    render(<Wrapper />);
    expect(within(group()).getAllByRole('textbox').map((i) => i.getAttribute('data-part'))).toEqual(['d', 'm', 'y']);
    expect(box('Day')).toBeInTheDocument();
  });

  it('像手机上一样逐个输入数字：输满自动跳格，输出 YYYY-MM-DD', async () => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('年'));
    await u.keyboard('1988');
    expect(box('月')).toHaveFocus();
    await u.keyboard('5'); // 5 不可能再接一位，直接跳
    expect(box('日')).toHaveFocus();
    await u.keyboard('12');
    expect(last).toBe('1988-05-12');
    expect(box('月')).toHaveValue('5'); // 框里不强行补零，避免打断输入
  });

  it('月份输入 1 时等第二位（10–12），日输入 3 时等第二位', async () => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('年'));
    await u.keyboard('19901');
    expect(box('月')).toHaveFocus();
    await u.keyboard('1');
    expect(box('日')).toHaveFocus();
    await u.keyboard('3');
    expect(box('日')).toHaveFocus();
    expect(last).toBe('1990-11-03');
    await u.keyboard('0');
    expect(last).toBe('1990-11-30');
  });

  it('只接受数字，多出来的位数不进框', async () => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('年'));
    await u.keyboard('19a8x8');
    expect(box('年')).toHaveValue('1988');
    await u.click(box('年'));
    await u.keyboard('7');
    expect(box('年')).toHaveValue('1988');
  });

  it('电脑上连着打 1990-3-27：分隔符跳到下一格', async () => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('年'));
    await u.keyboard('1990-1-27');
    expect(last).toBe('1990-01-27');
  });

  it('退格删到空时回到上一格，继续退格删上一格的数字', async () => {
    const u = userEvent.setup();
    render(<Wrapper initial="1988-05-12" />);
    await u.click(box('日'));
    await u.keyboard('{Backspace}{Backspace}');
    expect(box('日')).toHaveValue('');
    await u.keyboard('{Backspace}');
    expect(box('月')).toHaveFocus();
    await u.keyboard('{Backspace}');
    expect(box('月')).toHaveValue('0');
    expect(last).toBe('1988-0-');
  });

  it('自动跳格后立刻按 Tab 不会再跳过一格', async () => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('年'));
    await u.keyboard('1988');
    expect(box('月')).toHaveFocus();
    await u.tab();
    expect(box('月')).toHaveFocus();
    await u.tab(); // 第二次 Tab 正常移动
    expect(box('日')).toHaveFocus();
  });

  it.each([
    ['1988-05-12', '1988-05-12'],
    ['19880512', '1988-05-12'],
    ['1988年5月2日', '1988-05-02'],
    ['12/05/1988', '1988-12-05'],
  ])('在任意一格粘贴 %s 自动拆开', async (text, want) => {
    const u = userEvent.setup();
    render(<Wrapper />);
    await u.click(box('月'));
    await u.paste(text);
    expect(last).toBe(want);
    expect(box('日')).toHaveFocus();
  });

  it('自动填充把整串写进一个框时也能拆开', () => {
    render(<Wrapper />);
    fireEvent.change(box('年'), { target: { value: '1990-03-27' } });
    expect(last).toBe('1990-03-27');
    expect(box('年')).toHaveValue('1990');
    expect(box('月')).toHaveValue('03');
  });

  it('外部改值（加载资料）时同步三个框；清空输出空串', async () => {
    const u = userEvent.setup();
    render(<Wrapper initial="1990-01-31" />);
    expect(box('日')).toHaveValue('31');
    await u.click(screen.getByRole('button', { name: '外部改值' }));
    expect(box('年')).toHaveValue('2001');
    expect(box('月')).toHaveValue('02');
    for (const k of ['年', '月', '日']) await u.clear(box(k));
    expect(last).toBe('');
  });

  it('2 月 30 日只把"日"标红，错误用 aria-describedby 关联', () => {
    render(<Wrapper initial="1990-02-30" showErrors />);
    expect(box('日')).toHaveAttribute('aria-invalid', 'true');
    expect(box('月')).toHaveAttribute('aria-invalid', 'false');
    expect(box('年')).toHaveAttribute('aria-invalid', 'false');
    expect(screen.getByText('日期不存在')).toBeInTheDocument();
    expect(box('日')).toHaveAccessibleDescription(/日期不存在/);
  });

  it('没填完只标空的框；未满 18 岁三个框都标红', () => {
    const { unmount } = render(<Wrapper initial="1988-5-" showErrors />);
    expect(screen.getByText('请把年、月、日填写完整')).toBeInTheDocument();
    expect(box('日')).toHaveAttribute('aria-invalid', 'true');
    expect(box('年')).toHaveAttribute('aria-invalid', 'false');
    unmount();
    render(<Wrapper initial="2010-01-01" showErrors />);
    expect(screen.getByText('需年满 18 周岁')).toBeInTheDocument();
    for (const k of ['年', '月', '日']) expect(box(k)).toHaveAttribute('aria-invalid', 'true');
  });
});
