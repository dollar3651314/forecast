package com.forecast.app.resolve.api;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.I18nText;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 平台裁决（forecast S7；需求说明书第 3.2、3.6、4.4 节，AC-30～AC-36）：裁决台（提议、确认、退回、直接确认、作废、E6 / E7 等待）、
 * 公示和争议（客户提交、有权限的人处理）。权限点、审计、高危确认在 admin 模块（{@code admin/ops/ResolveOps}），这里只管业务。
 * <p>编号一律字符串，时间 ISO-8601 UTC。RQ-17：每一步只要求一个有权限的人，不检查"提议人和确认人是不是同一个人"。
 */
public interface ResolveDeskApi {

  // ---------------------------------------------------------------------
  // 输入
  // ---------------------------------------------------------------------

  /**
   * 提议（或直接确认）的内容。
   *
   * @param optionNo 结果（选项编号）；提议作废时为空
   * @param voidMarket true = 提议作废（E5 比赛取消、E6 / E7 超时、E10 / E11 无法判定等），确认的人还要有 {@code resolve:void}
   * @param basis 依据的规则（{@link #RESULT_BASES} / {@link #VOID_BASES}），结果为空时按 NORMAL
   * @param actualStartAt 实际开赛时间（E14，可空）：早于截止时间时，之后收到的下注在结算时全额退回
   * @param evidenceUrls 依据链接（一手官方页面）：1～5 个，http(s)
   * @param attachmentNos 存档截图（先上传，这个市场的）：0～5 个
   * @param note 理由、说明（必填，≤ 1,000 字；公开在市场详情）
   */
  record ProposalInput(String optionNo, Boolean voidMarket, String basis, String actualStartAt, List<String> evidenceUrls, List<String> attachmentNos,
      String note) {}

  /** 结果提议能用的依据：NORMAL 按规则正常判定 / E6_COMPLETED 延期后 48 小时内完赛 / E7_OFFICIAL 腰斩、官方宣布比分有效 / E11_USUAL 规则有歧义，按通常含义 */
  List<String> RESULT_BASES = List.of("NORMAL", "E6_COMPLETED", "E7_OFFICIAL", "E11_USUAL");

  /**
   * 作废提议能用的依据：E5_CANCELLED 比赛取消 / E6_TIMEOUT 延期超过 48 小时 / E7_NO_RESULT 腰斩后宣布重赛或 48 小时内没有宣布 /
   * E10_UNDETERMINED 数据源无法确定 / E11_UNDETERMINABLE 规则歧义无法判定 / OTHER 其他（理由里写清楚）
   */
  List<String> VOID_BASES = List.of("E5_CANCELLED", "E6_TIMEOUT", "E7_NO_RESULT", "E10_UNDETERMINED", "E11_UNDETERMINABLE", "OTHER");

  /**
   * 客户提交的争议。
   *
   * @param claimedOptionNo 认为应该是哪个结果（选项编号），或 {@code "void"}（认为无法判定、应该作废）；不能和公示的结果相同
   * @param reason 理由（必填，≤ 1,000 字）
   * @param link 可附一个链接（可空，http(s)，≤ 500）
   */
  record DisputeInput(String claimedOptionNo, String reason, String link) {}

  /**
   * 争议处理。
   *
   * @param decision uphold 维持 / overturn 改判 / void 无法判定（作废，全额退款）
   * @param optionNo 改判后的结果（只在 overturn 时，不能和原结果相同）
   * @param note 结论和理由（必填，≤ 1,000 字；公开在市场详情，不显示争议人）
   */
  record DecisionInput(String decision, String optionNo, String note) {}

  // ---------------------------------------------------------------------
  // 视图
  // ---------------------------------------------------------------------

  record OptionView(String optionNo, int seq, I18nText label) {}

  /**
   * 一条提议。
   *
   * @param outcome result / void
   * @param confirmOverdue 超过确认时限（裁决台标红）
   */
  record Proposal(String proposalId, String outcome, String optionNo, String basis, String actualStartAt, List<String> evidenceUrls, List<String> attachmentNos,
      String note, String proposedBy, String proposedAt, String confirmDueAt, boolean confirmOverdue) {}

  /**
   * 裁决台列表的一行。
   *
   * @param origin platform 平台裁决 / auto_failed 自动裁决失败转人工 / no_auto 自动裁决的品类但还没有自动裁决
   * @param stage open 待裁决 / publicizing 公示中 / disputed 争议中 / closed 已结束
   * @param hold postponed（E6 延期）/ abandoned（E7 腰斩）/ 空
   * @param proposeOverdue 超过提议时限还没有提议（标红）
   * @param disputeOverdue 超过争议处理时限还没有结论（标红）
   * @param overdue 三种超时有任意一种
   */
  record CaseItem(String marketNo, String category, I18nText title, String marketStatus, String origin, String stage, String hold, String holdUntil,
      String proposeDueAt, boolean proposeOverdue, Proposal proposal, String resultOptionNo, String disputeDeadlineAt, int openDisputes, String disputeDueAt,
      boolean disputeOverdue, boolean overdue, String eventEndAt, String updatedAt) {}

  /** 一条裁决记录（只增不改） */
  record Record(String id, String kind, String optionNo, String refId, String basis, String actualStartAt, List<String> evidenceUrls,
      List<String> attachmentNos, String note, String actor, String time, Object detail) {}

  /** 一个争议（后台看得到争议人编号；客户端看不到） */
  record Dispute(String disputeNo, String marketNo, long userId, String claimedOptionNo, boolean claimVoid, String reason, String link, String status,
      String decisionNote, Long handledBy, String handledAt, String receivedAt) {}

  record Attachment(String attachmentNo, String contentType, int size, String sha256, long uploadedBy, String createdAt) {}

  /**
   * 一个市场的裁决详情（裁决台、公示与争议页）。
   *
   * @param decision 争议结论 uphold / overturn / void，没有为空
   */
  record CaseDetail(CaseItem summary, List<OptionView> options, String actualStartAt, String resolvedBy, String confirmedAt, String decision,
      List<Record> records, List<Dispute> disputes, List<Attachment> attachments) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  record File(byte[] content, String contentType) {}

  /** 客户提交争议的回执 */
  record DisputeReceipt(String disputeNo, String marketNo, String claimedOptionNo, boolean claimVoid, String status, String receivedAt) {}

  // ---------------------------------------------------------------------
  // 裁决台
  // ---------------------------------------------------------------------

  /**
   * 列表。tab：awaiting_proposal 待提议 / awaiting_confirm 待确认 / auto_failed 自动裁决失败 / on_hold 延期或腰斩等待中 / overdue 超时 /
   * publicizing 公示中 / disputed 争议中；不传或 all = 没结束的全部。超时的先列。
   */
  Page<CaseItem> cases(String tab, int page, int pageSize);

  /** 一个市场的裁决详情；市场不存在或从来没有进过裁决台为空 */
  Optional<CaseDetail> detail(long marketNo);

  /** 上传一张存档截图（PNG / JPG，≤ 2 MB，按文件头判断类型）：市场要在待裁决 */
  Attachment upload(long marketNo, byte[] content, long adminId);

  Optional<File> attachment(long attachmentNo);

  /** 只校验、不改数据：提议内容的字段错误（后台把它和原因、勾选的错误一次列出，C56）。市场不存在抛 NOT_FOUND */
  List<ValidationException.Field> checkProposal(long marketNo, ProposalInput in);

  /** 提议（{@code resolve:propose}）：市场在待裁决、没有在等待（E6 / E7）、没有别的待确认提议；提议后邮件提醒确认的人 */
  CaseDetail propose(long marketNo, ProposalInput in, long adminId);

  /**
   * 确认待确认的提议（{@code resolve:confirm}）：结果提议 → 公示中（争议窗口开始，通知 #10）；作废提议 → 已作废（全额退款），要 canVoid。
   * proposalId 必须是当前待确认的那一条（不是时 409 PROPOSAL_CHANGED）。提议人自己有确认权限时可以确认自己的提议（AC-30）。
   */
  CaseDetail confirm(long marketNo, String proposalId, long adminId, boolean canVoid);

  /** 退回提议（{@code resolve:confirm}，理由必填）：市场留在待裁决，可以重新提议 */
  CaseDetail returnProposal(long marketNo, String proposalId, String reason, long adminId);

  /** 不等提议，自己填结果直接确认（{@code resolve:confirm}；作废还要 canVoid）：同一个事务里写提议和确认 */
  CaseDetail decide(long marketNo, ProposalInput in, long adminId, boolean canVoid);

  /** E6 延期（postponed：等到原定开赛后 48 小时）/ E7 腰斩（abandoned：等标记后 48 小时）：等待期间不能提议；到时间还没解除 → 系统作废 */
  CaseDetail hold(long marketNo, String kind, String reason, long adminId);

  /** 解除等待（已完赛 / 官方已宣布比分），之后可以提议 */
  CaseDetail release(long marketNo, String reason, long adminId);

  /**
   * 人工作废（{@code resolve:void}，E5 比赛取消等）：待裁决、公示中、争议中且结算还没开始 → 已作废，全额退款；
   * 有待处理的争议一并结束（结论 = 作废，通知 #15）。
   */
  void voidMarket(long marketNo, String reason, long adminId);

  // ---------------------------------------------------------------------
  // 公示与争议
  // ---------------------------------------------------------------------

  /** 争议列表（status：open 待处理 / handled 已处理；不传为全部），新的在前 */
  Page<Dispute> disputes(String status, int page, int pageSize);

  /**
   * 处理争议（{@code dispute:handle}）：这个市场全部待处理的争议一起给结论。维持 / 改判 → 直接结算（不再公示第二轮，E12）；无法判定 → 作废。
   * 争议人收到 #15；改判时所有下注人收到 #16。
   */
  CaseDetail decideDisputes(long marketNo, DecisionInput in, long adminId);

  /**
   * 客户提交争议（客户端 {@code POST /api/markets/{publicNo}/disputes}）：市场在公示中（或已有人提交、争议中），收到时间 &lt; 争议截止时间，
   * 在这个市场有有效持仓，每人每市场 1 次。第一个争议让市场进入争议中，邮件提醒处理的人。
   */
  DisputeReceipt dispute(long marketNo, long userId, DisputeInput in, Instant receivedAt);
}
