package com.forecast.app.market.api;

import com.forecast.app.common.error.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台的市场管理（forecast S3，需求说明书第 4.1、6.2 节）。只有 admin 模块调用；权限点检查和审计在 admin 模块（{@code admin/ops/MarketOps}）。
 * 市场一律用对外编号 {@code publicNo}（雪花 ID，接口里是字符串）。时间是 ISO-8601 的 UTC 字符串（例如 {@code 2026-11-01T14:55:00Z}），费率是百分比字符串（{@code "5.25"} = 5.25%）。
 * <p>字段错误一次列出全部（C56，{@link ValidationException}）；状态不对的操作返回 {@code ILLEGAL_TRANSITION}（409）或 {@code MARKET_STATE_CHANGED}（409，别人刚改过）。
 */
public interface MarketAdminApi {

  /** 一个选项的输入：选项文字和"在什么情况下赢"，都是三种语言 */
  record OptionInput(I18nText label, I18nText winCondition) {}

  /**
   * 新建、编辑草稿的输入（第 4.1 节的模板）。保存草稿时只检查：品类、选项 2～10 个、四个时间都有且先后正确（AC-11）、单客户上限不超过品类默认值；
   * 其余必填项（三语题目、规则、选项文字、裁决方式和来源、平台裁决的争议窗口）到"提交上架"和"上架"时才检查。
   * 费率不在这里：新建时按品类默认值带出，之后只能"改费率"（需求第 4.1 节）。
   *
   * @param sourceRef 自动裁决用的结构化参数（S6、S8 用），可以为空
   * @param userCap 为空时用品类默认值
   * @param disputeWindowMinutes 为空时平台裁决用品类默认值
   */
  record DraftInput(String category, I18nText title, I18nText rules, String resolutionMode, String sourcePrimary, String sourceBackup,
      Map<String, Object> sourceRef, String openAt, String closeAt, String eventStartAt, String eventEndAt, String userCap,
      Integer disputeWindowMinutes, String coverPath, List<OptionInput> options, Boolean todaySlate) {

    /** S13 以前的 15 个字段（不带"列入今日场次"，按不列入） */
    public DraftInput(String category, I18nText title, I18nText rules, String resolutionMode, String sourcePrimary, String sourceBackup,
        Map<String, Object> sourceRef, String openAt, String closeAt, String eventStartAt, String eventEndAt, String userCap,
        Integer disputeWindowMinutes, String coverPath, List<OptionInput> options) {
      this(category, title, rules, resolutionMode, sourcePrimary, sourceBackup, sourceRef, openAt, closeAt, eventStartAt, eventEndAt, userCap,
          disputeWindowMinutes, coverPath, options, null);
    }
  }

  record OptionView(String optionNo, int seq, I18nText label, I18nText winCondition) {}

  /**
   * 列表的一行。S9 加：resolutionMode（auto / platform，草稿可以为空）、origin（manual 手动 / autogen 加密自动生成 / sports 从赛程生成）、
   * pool（奖池合计，2 位小数）、bettors（下注人数）——后台列表要显示（需求第 6.2 节"奖池、下注人数"）。S13 加 todaySlate（列入今日场次，MF-3）。
   */
  record Summary(String publicNo, String category, I18nText title, String status, boolean submitted, String openAt, String closeAt,
      String eventStartAt, String eventEndAt, String feeRate, String createdAt, String updatedAt, String resolutionMode, String origin, String pool,
      int bettors, boolean todaySlate) {}

  /** 各状态的市场数（小写状态 → 数量，没删除的）和待上架（已提交上架的草稿）数，后台列表的分组标签和仪表盘用（S9） */
  record Counts(Map<String, Long> byStatus, long review) {}

  record StatusLogView(String from, String to, String actor, String reason, String time) {}

  record FeeChangeView(String oldRate, String newRate, String reason, long changedBy, String time, boolean notified) {}

  record ClarificationView(I18nText text, long createdBy, String time) {}

  /**
   * 市场详情。{@code missing}：现在提交上架 / 上架会缺哪些必填项（第 4.1 节，空表示齐全），后台按它显示检查清单。
   * S13 加 todaySlate（列入今日场次）、origin（manual / autogen / sports，和列表一致）。
   */
  record Detail(String publicNo, String category, I18nText title, I18nText rules, String status, boolean submitted, String settlePhase,
      String resolutionMode, String sourcePrimary, String sourceBackup, Map<String, Object> sourceRef, String openAt, String closeAt,
      String eventStartAt, String eventEndAt, String feeRate, String userCap, Integer disputeWindowMinutes, String coverPath,
      String cancelReason, String voidReason, Long createdBy, Long listedBy, int version, String createdAt, String updatedAt,
      List<OptionView> options, List<ValidationException.Field> missing, List<StatusLogView> statusLog, List<FeeChangeView> feeChanges,
      List<ClarificationView> clarifications, boolean todaySlate, String origin) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /** 改费率的结果：前后的费率（百分比）、这次通知了几个客户（#22） */
  record FeeChange(String oldRate, String newRate, int notified, Detail market) {}

  /**
   * 列表：状态（小写；S9 起可以逗号分隔多个，例如 {@code listed,paused}）、品类、是否已提交上架、是否列入今日场次（S13）、关键字（题目或编号）筛选；
   * 草稿删除后不列出
   */
  Page<Summary> list(String status, String category, Boolean submitted, Boolean todaySlate, String keyword, int page, int pageSize);

  /** S13 以前的写法（不按今日场次筛选） */
  default Page<Summary> list(String status, String category, Boolean submitted, String keyword, int page, int pageSize) {
    return list(status, category, submitted, null, keyword, page, pageSize);
  }

  /** 同时开放的市场数和上限（S13，MF-3，需求第 4.2 节）：limit 0 = 不限；上架前后台按它提示"当前已开放 X 个，上限 Y 个" */
  record OpenLimit(long open, int limit) {

    /** 再上架一个会不会超过上限 */
    public boolean exceededByOneMore() {
      return limit > 0 && open + 1 > limit;
    }
  }

  OpenLimit openLimit();

  /** 仪表盘的"场次"（S13，SQ-21）：同时开放的市场数和上限、今天（UTC）列入今日场次的市场数 */
  record SlateStats(long open, int limit, long today) {}

  SlateStats slateStats();

  /** 各状态的数量（S9） */
  Counts counts();

  Optional<Detail> detail(long publicNo);

  /** 新建草稿（market:edit） */
  Detail createDraft(DraftInput in, long adminId);

  /** 编辑草稿（market:edit）：只有草稿能改，选项整体替换 */
  Detail updateDraft(long publicNo, DraftInput in, long adminId);

  /** 删除草稿（market:edit）：只有草稿能删 */
  void deleteDraft(long publicNo);

  /** 提交上架（market:edit）：只加"已提交"标记，状态仍是草稿（RQ-17）；必填项不齐时一次列出全部 */
  Detail submit(long publicNo);

  /** 退回草稿（market:publish）：去掉"已提交"标记 */
  Detail returnDraft(long publicNo);

  /**
   * 上架（market:publish）：DRAFT → LISTED。要求勾选"已检查以上各项"（{@code checked}）、必填项齐全、截止时间还没到。
   * S13（MF-3）：{@code todaySlate} 不为空时同时写"列入今日场次"；上架后会超过"同时开放的市场数上限"时，没勾选"仍要上架"（{@code overLimit}）
   * 一律拒绝（字段 overLimit：required），勾选了照常上架（审计里记下当时的数量和上限）。
   */
  Detail publish(long publicNo, boolean checked, Boolean todaySlate, boolean overLimit, long adminId);

  /** 测试和内部调用的简写：不改今日场次标记、不受同时开放上限阻止（后台接口一律走上面的完整写法） */
  default Detail publish(long publicNo, boolean checked, long adminId) {
    return publish(publicNo, checked, null, true, adminId);
  }

  /**
   * 改"列入今日场次"（market:publish，S13 MF-3）：草稿、已上架、已暂停的市场可以改（上架后临时加进今日场次或拿掉）；其他状态 409 ILLEGAL_TRANSITION。
   */
  Detail setTodaySlate(long publicNo, boolean todaySlate, long adminId);

  /** 暂停（market:pause）：LISTED → PAUSED */
  Detail pause(long publicNo, long adminId, String reason);

  /** 恢复（market:pause）：PAUSED → LISTED；截止时间已过的不能恢复（由截止任务进入"截止下注"） */
  Detail resume(long publicNo, long adminId, String reason);

  /** 取消（market:cancel）：截止前 LISTED / PAUSED → CANCELLED，全额退款（S5 经 {@link MarketListener}） */
  Detail cancel(long publicNo, long adminId, String reason);

  /** 发布补充说明（market:clarify）：上架后、截止前；三种语言都要有 */
  Detail clarify(long publicNo, I18nText text, long adminId);

  /** 提前截止（需求第 3.2 节第 4 条）：只能提前、不能推后，必须晚于开放时间；新截止时间之后收到的下注由 S4、S5 退回 */
  Detail advanceClose(long publicNo, String closeAt, long adminId, String reason);

  /**
   * 改本市场的费率（fee:edit，需求第 3.8 节）：0～20%，最多 2 位小数；结算开始前（草稿到争议中）都能改；写修改记录；
   * 上架后改的，给这个市场有下注的客户发 #22。
   */
  FeeChange changeFee(long publicNo, String feeRatePercent, long adminId, String reason);
}
