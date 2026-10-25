package com.forecast.app.resolve.platform;

import java.time.LocalDateTime;

/** 平台裁决各表的一行（MyBatis 按字段名映射，forecast S7）。时间都是 UTC。只在 resolve 模块里用。 */
public final class PlatformRows {

  private PlatformRows() {}

  /** resolve_case */
  public static class Case {
    public long marketId;
    public String origin;
    public String stage;
    public Long proposalId;
    public LocalDateTime proposedAt;
    public LocalDateTime proposeDueAt;
    public LocalDateTime confirmDueAt;
    public String holdKind;
    public LocalDateTime holdUntil;
    public Long resultOptionId;
    public LocalDateTime actualStartAt;
    public String resolvedBy;
    public Long confirmRecordId;
    public LocalDateTime confirmedAt;
    public LocalDateTime disputeDeadlineAt;
    public LocalDateTime disputeDueAt;
    public String decision;
    public Long decisionRecordId;
    public LocalDateTime settleRequestedAt;
    public LocalDateTime proposeAlertedAt;
    public LocalDateTime confirmAlertedAt;
    public LocalDateTime disputeAlertedAt;
    public int version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
  }

  /** resolve_record（只增不改；S6 的列 + S7 加的 ref_id、actual_start_at、evidence_urls、attachments） */
  public static class Record {
    public Long id;
    public long marketId;
    public String kind;
    public Long optionId;
    public Long refId;
    public LocalDateTime actualStartAt;
    /** JSON 数组 */
    public String evidenceUrls;
    /** JSON 数组（截图的对外编号） */
    public String attachments;
    /** JSON（S7：basis 依据的规则、outcome result / void、hold 等） */
    public String detail;
    public String note;
    public String actor;
    public LocalDateTime createdAt;
  }

  /** resolve_dispute */
  public static class Dispute {
    public Long id;
    public long publicNo;
    public long marketId;
    public long userId;
    public Long claimedOptionId;
    public String reason;
    public String link;
    public String status;
    public String decisionNote;
    public Long handledBy;
    public LocalDateTime handledAt;
    public LocalDateTime receivedAt;
    public LocalDateTime createdAt;
  }

  /** resolve_attachment（只增不改） */
  public static class Attachment {
    public Long id;
    public long publicNo;
    public long marketId;
    public String storageKey;
    public String contentType;
    public int sizeBytes;
    public String sha256;
    public long uploadedBy;
    public LocalDateTime createdAt;
  }
}
