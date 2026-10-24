import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button, Form } from 'antd';
import i18n from '../i18n';
import { Providers } from '../Providers';
import { birthDateProblems } from '../utils/validators';
import { joinDate, parseDateText, partOrder, problemParts } from '../utils/dateParts';
import { DateFields } from './DateFields';

const TODAY = new Date(Date.UTC(2026, 9, 16));
let submitted: string | undefined;

function Harness({ initial = '' }: { initial?: string }) {
  const [form] = Form.useForm<{ birthDate: string }>();
  return (
    <Providers>
      <Form form={form} initialValues={{ birthDate: initial }} onFinish={(v) => (submitted = v.birthDate)}>
        <Form.Item
          name="birthDate"
          label="出生日期"
          rules={[{ validator: (_r, v: string) => (birthDateProblems(v ?? '', TODAY).length ? Promise.reject(new Error(birthDateProblems(v ?? '', TODAY).map((k) => i18n.t(k)).join('；'))) : Promise.resolve()) }]}
        >
          <DateFields groupLabel="出生日期" />
        </Form.Item>
        <Button htmlType="submit">提交</Button>
      </Form>
    </Providers>
  );
}

const group = () => screen.getByRole('group', { name: '出生日期' });
const box = (name: string) => within(group()).getByLabelText(name);

beforeEach(async () => {
  submitted = undefined;
  await act(() => i18n.changeLanguage('zh-CN'));
});

describe('后台 DateFields（antd 风格三框）', () => {
  it('纯函数和客户端一致：粘贴、合成、逐框找错', () => {
    expect(parseDateText('19880512', partOrder('zh-CN'))).toEqual({ y: '1988', m: '05', d: '12' });
    expect(parseDateText('27/3/1990', partOrder('en'))).toEqual({ y: '1990', m: '3', d: '27' });
    expect(joinDate({ y: '1988', m: '5', d: '2' })).toBe('1988-05-02');
    expect([...problemParts('1990-02-30')]).toEqual(['d']);
    expect(problemParts('2000-02-29').size).toBe(0);
  });

  it('出生日期校验：没填完、年份位数、2 月 30 日、闰年、未满 18 岁、晚于今天', () => {
    expect(birthDateProblems('1988-5-', TODAY)).toEqual(['validation.dateIncomplete']);
    expect(birthDateProblems('88-05-12', TODAY)).toEqual(['validation.yearFormat']);
    expect(birthDateProblems('1990-02-30', TODAY)).toEqual(['validation.dateInvalid']);
    expect(birthDateProblems('2000-02-29', TODAY)).toEqual([]);
    expect(birthDateProblems('2008-10-17', TODAY)).toEqual(['validation.ageUnder18']);
    expect(birthDateProblems('2027-01-01', TODAY)).toEqual(['validation.dateInvalid']);
  });

  it('原值拆到三个框；逐个输入自动跳格、退格回退；提交的是 YYYY-MM-DD', async () => {
    const u = userEvent.setup();
    render(<Harness initial="1990-05-12" />);
    expect(box('年')).toHaveValue('1990');
    expect(box('月')).toHaveValue('05');
    for (const k of ['年', '月', '日']) await u.clear(box(k));
    await u.click(box('年'));
    await u.keyboard('1988');
    expect(box('月')).toHaveFocus();
    await u.keyboard('7');
    expect(box('日')).toHaveFocus();
    await u.keyboard('{Backspace}');
    expect(box('月')).toHaveFocus();
    await u.click(box('日'));
    await u.keyboard('9');
    await u.click(screen.getByRole('button', { name: '提交' }));
    await waitFor(() => expect(submitted).toBe('1988-07-09'));
  });

  it('粘贴整串自动拆开', async () => {
    const u = userEvent.setup();
    render(<Harness />);
    await u.click(box('日'));
    await u.paste('1988年5月12日');
    expect(box('年')).toHaveValue('1988');
    expect(box('月')).toHaveValue('5');
    expect(box('日')).toHaveValue('12');
  });

  it('2 月 30 日只把"日"标红，错误和三个框关联', async () => {
    render(<Harness initial="1990-02-30" />);
    fireEvent.click(screen.getByRole('button', { name: '提交' }));
    expect(await screen.findByText('不是有效日期（1900 年以后、不晚于今天）')).toBeInTheDocument();
    await waitFor(() => expect(box('日')).toHaveAttribute('aria-invalid', 'true'));
    expect(box('年')).toHaveAttribute('aria-invalid', 'false');
    // 红框也只在"日"上（antd 默认会把同一个 Form.Item 里的输入框都标红）
    expect(box('日')).toHaveClass('ant-input-status-error');
    expect(box('年')).not.toHaveClass('ant-input-status-error');
    expect(box('月')).not.toHaveClass('ant-input-status-error');
    expect(box('日').getAttribute('aria-describedby')).toBeTruthy();
    expect(submitted).toBeUndefined();
  });

  it('英文界面按 Day Month Year 排列', async () => {
    await act(() => i18n.changeLanguage('en'));
    render(<Harness />);
    expect(within(group()).getAllByRole('textbox').map((i) => i.getAttribute('data-part'))).toEqual(['d', 'm', 'y']);
  });
});
