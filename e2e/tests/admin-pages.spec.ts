/**
 * 后台页面的端到端（forecast S9）：真实后端 + 真实接口模式的后台，在页面上走关键操作：
 * 菜单（和后端菜单表一致）→ 待上架：检查清单勾选后上架 → 裁决台：提议（结算预览守恒）→ 确认（高危）→ 公示 →
 * 客户提争议 → 公示与争议页：维持原结果（高危）→ 直接结算 → 参数设置：改品类默认费率（高危，前 → 后）并改回。
 * 客户和下注走接口（客户端页面另有用例）；两种形态都跑。
 */
import { expect, request, test, type Page } from "@playwright/test";
import { ADMIN_URL, DEPLOYED, STUB_URL } from "./targets";
import { step } from "./support";
import { adminLogin, cjk } from "./admin";
import { adminSession, bet, clientSession, fund, json, t } from "./api";

type Detail = {
  status: string;
  feeRate: string;
  resolution: {
    resultOptionNo: string | null;
    dispute: { decision: string } | null;
  };
};

/** 高危确认弹窗：原因 + 勾选 → 确认执行，等接口返回成功 */
async function highRisk(
  page: Page,
  reason: string,
  urlPart: string,
): Promise<void> {
  const dialog = page.locator(".ant-modal").filter({ hasText: "操作原因" });
  await expect(dialog).toBeVisible();
  await expect(
    page.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]'),
  ).toHaveCount(0);
  await dialog.getByLabel("操作原因").fill(reason);
  await dialog.getByText("我已核对操作对象").click();
  const done = page.waitForResponse(
    (r) =>
      r.url().includes(urlPart) &&
      ["POST", "PUT"].includes(r.request().method()),
    { timeout: 15_000 },
  );
  await dialog.getByRole("button", { name: cjk("确认执行") }).click();
  const res = await done;
  expect(res.ok(), `${urlPart} 返回 ${res.status()}：${await res.text()}`).toBe(
    true,
  );
  await expect(dialog).toBeHidden();
}

test("后台页面：待上架 → 裁决台提议、确认 → 争议处理 → 参数设置", async ({
  browser,
}) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({
    baseURL: ADMIN_URL,
    ignoreHTTPSErrors: DEPLOYED,
    viewport: { width: 1440, height: 900 },
    locale: "zh-CN",
    serviceWorkers: "block",
  });
  const page = await ctx.newPage();
  try {
    await step("超管登录后台；菜单是后端菜单表的那一套", async () => {
      await adminLogin(page);
      const nav = page.locator(".ant-layout-sider");
      for (const name of ["市场管理", "裁决", "结算", "报表", "参数设置"])
        await expect(
          nav.getByText(name, { exact: true }).first(),
        ).toBeVisible();
    });
    let admin = await step("接口：超管会话", () => adminSession(anon));
    const a = await step(
      "注册客户 A",
      () => clientSession(anon, "apa"),
      90_000,
    );
    const b = await step(
      "注册客户 B",
      () => clientSession(anon, "apb"),
      90_000,
    );
    await step("两人各充值 100", async () => {
      await fund(anon, a.api, "100");
      await fund(anon, b.api, "100");
    });
    const title = `端到端后台页面 ${Date.now()}`;
    let body: Record<string, unknown> = {};
    const no = await step("运营建草稿并提交上架（接口）", async () => {
      const now = new Date(
        (
          await json<{ now: string }>(
            await anon.get("/api/e2e/clock"),
            "读时钟",
          )
        ).now,
      );
      const at = (ms: number) => new Date(now.getTime() + ms).toISOString();
      body = {
        category: "MACRO",
        title: t(title),
        rules: t("按官方公布为准；无法判定的作废退款"),
        resolutionMode: "PLATFORM",
        sourcePrimary: "example.org",
        sourceBackup: "example.net",
        openAt: at(-60_000),
        closeAt: at(120_000),
        eventStartAt: at(120_000),
        eventEndAt: at(180_000),
        disputeWindowMinutes: 2,
        options: [
          { label: t("是"), winCondition: t("公布为是") },
          { label: t("否"), winCondition: t("公布为否") },
        ],
      };
      const d = await json<{ publicNo: string }>(
        await admin.post("/api/admin/markets", { data: body }),
        "建草稿",
      );
      await json(
        await admin.post(`/api/admin/markets/${d.publicNo}/submit`),
        "提交上架",
      );
      return d.publicNo;
    });

    await step("待上架页：检查清单勾选三项 → 检查后上架", async () => {
      await page.goto("/admin/markets/review");
      const row = page.locator("tr").filter({ hasText: `${title}（简）` });
      await expect(row).toBeVisible();
      await row.getByRole("button", { name: /检查上架/ }).click();
      await expect(page).toHaveURL(new RegExp(`/admin/markets/${no}/edit`));
      const publish = page.getByRole("button", { name: /检查后上架/ });
      await expect(publish).toBeDisabled();
      for (const c of [
        "选项覆盖所有可能",
        "延期、取消、腰斩",
        '不属于"不做"的题目',
      ])
        await page.getByText(c).click();
      const done = page.waitForResponse(
        (r) =>
          r.url().includes(`/markets/${no}/publish`) &&
          r.request().method() === "POST",
      );
      await publish.click();
      expect((await done).ok()).toBe(true);
      expect(
        (await json<Detail>(await a.api.get(`/api/markets/${no}`), "详情"))
          .status,
      ).toBe("listed");
    });

    const opts = await json<{ options: { optionNo: string }[] }>(
      await a.api.get(`/api/markets/${no}`),
      "详情",
    );
    const [yes, nope] = opts.options.map((o) => o.optionNo);
    await step('A 押"是" 60、B 押"否" 40；拨到事件结束 → 待裁决', async () => {
      await bet(a.api, no, yes, "60");
      await bet(b.api, no, nope, "40");
      await json(
        await anon.post("/api/e2e/clock/advance", {
          data: { millis: 120_000 },
        }),
        "拨到截止",
      );
      await json(await anon.post("/api/e2e/markets/tick"), "截止任务");
      await json(
        await anon.post("/api/e2e/clock/advance", { data: { millis: 60_000 } }),
        "拨到事件结束",
      );
      await json(await anon.post("/api/e2e/markets/tick"), "事件结束任务");
      expect(
        (await json<Detail>(await a.api.get(`/api/markets/${no}`), "详情"))
          .status,
      ).toBe("pending_resolution");
    });

    await step(
      '裁决台：选"是"，结算预览守恒；填依据和说明 → 提交提议',
      async () => {
        await page.goto(`/admin/resolve?market=${no}`);
        const form = page.getByTestId("proposal-form");
        await expect(form).toBeVisible();
        await form.getByText("是（简）", { exact: true }).click();
        const preview = page.getByTestId("settle-preview");
        // T = 100，"是"赢：G = 60 × 40 ÷ 60 = 40，抽成 2，派彩 98
        await expect(preview).toContainText("98");
        await expect(preview).toContainText("守恒");
        await form
          .locator("textarea")
          .first()
          .fill("https://example.org/official");
        await form.locator("textarea").last().fill("官方公布：是");
        const done = page.waitForResponse(
          (r) =>
            r.url().includes(`/resolve/cases/${no}/proposals`) &&
            r.request().method() === "POST",
        );
        await form.getByRole("button", { name: /提交提议/ }).click();
        expect((await done).ok()).toBe(true);
        await expect(page.getByTestId("proposal")).toBeVisible();
      },
    );

    await step("确认结果（高危：原因必填、勾选）→ 公示中", async () => {
      await page.getByRole("button", { name: cjk("确认结果") }).click();
      await highRisk(page, "核对官网", "/confirm");
      await expect
        .poll(
          async () =>
            (await json<Detail>(await b.api.get(`/api/markets/${no}`), "详情"))
              .status,
          { message: "等公示" },
        )
        .toBe("publicizing");
    });

    await step(
      "B 提争议（接口）→ 公示与争议页：维持原结果（高危）→ 直接结算",
      async () => {
        await json(
          await b.api.post(`/api/markets/${no}/disputes`, {
            data: { claimedOptionNo: nope, reason: '我觉得是"否"' },
          }),
          "提争议",
        );
        await page.goto(`/admin/resolve/disputes?market=${no}`);
        await expect(page.getByText('我觉得是"否"')).toBeVisible();
        await page.getByText(/维持原结果「是（简）」/).click();
        const submit = page.getByRole("button", { name: /提交结论/ });
        await expect(submit).toBeDisabled();
        await page.getByLabel(/结论和理由/).fill('核对官网，结果为"是"');
        await submit.click();
        await highRisk(page, "争议不成立", "/disputes/decide");
        admin = await adminSession(anon);
        const o = await json<{ settlePhase: string }>(
          await admin.get(`/api/admin/markets/${no}/settlement`),
          "结算情况",
        );
        if (o.settlePhase === "paying")
          await json(
            await anon.post("/api/e2e/settle/tick"),
            "派彩（分批模式）",
          );
        await expect
          .poll(
            async () =>
              (
                await json<Detail>(
                  await a.api.get(`/api/markets/${no}`),
                  "详情",
                )
              ).status,
            { message: "等结算" },
          )
          .toBe("settled");
        const d = await json<Detail>(
          await a.api.get(`/api/markets/${no}`),
          "详情",
        );
        expect(d.resolution.resultOptionNo).toBe(yes);
        expect(d.resolution.dispute?.decision).toBe("upheld");
        await expect
          .poll(
            async () =>
              (
                await json<{ available: string }>(
                  await a.api.get("/api/ledger/account"),
                  "账户",
                )
              ).available,
            { message: "等派彩" },
          )
          .toBe("138");
      },
    );

    await step(
      "参数设置：时事默认费率 5% → 6%（高危，前 → 后），新草稿用 6%；再改回 5%",
      async () => {
        await page.goto("/admin/params");
        const edit = page.getByRole("button", { name: "编辑 时事" }).first();
        for (const [to, back] of [
          ["6", false],
          ["5", true],
        ] as const) {
          await edit.click();
          const dialog = page
            .locator(".ant-modal")
            .filter({ hasText: "操作原因" });
          await expect(dialog).toContainText(back ? "现在是 6%" : "现在是 5%");
          await dialog.getByLabel("新的值").fill(to);
          await highRisk(
            page,
            back ? "端到端测试：改回" : "端到端测试",
            "/params/",
          );
          if (!back) {
            const draft = await json<{ publicNo: string; feeRate: string }>(
              await admin.post("/api/admin/markets", {
                data: { ...body, title: t("费率检查") },
              }),
              "建草稿",
            );
            expect(draft.feeRate).toBe("6");
            await json(
              await admin.delete(`/api/admin/markets/${draft.publicNo}`),
              "删草稿",
            );
          }
        }
      },
    );
    await admin.dispose();
    await a.api.dispose();
    await b.api.dispose();
  } finally {
    await ctx.close();
    await anon.post("/api/e2e/clock/reset");
    await anon.dispose();
  }
});
