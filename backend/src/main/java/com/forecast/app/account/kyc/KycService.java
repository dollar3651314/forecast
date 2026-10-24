package com.forecast.app.account.kyc;

import com.forecast.app.account.api.KycStatus;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.storage.ObjectStorage;
import com.forecast.app.notification.api.NotifyApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC：提交、查询、审核。规则见 技术方案/v1-数据库设计.md 第 7 节。
 * 和现有系统的不同：服务端校验图片大小和格式（现有系统只在前端限制）；同一证件不能被两个账户同时使用。
 */
@Service
class KycService {

  static final int MAX_BYTES = 2 * 1024 * 1024;

  /** 一张图：字段名、内容、原文件类型由文件头判断，不信任浏览器报的类型 */
  record Image(String field, byte[] bytes) {}

  enum DocType { ID_CARD, PASSPORT }

  private final KycMapper mapper;
  private final ObjectStorage storage;
  private final NotifyApi notify;

  KycService(KycMapper mapper, ObjectStorage storage, NotifyApi notify) {
    this.mapper = mapper;
    this.storage = storage;
    this.notify = notify;
  }

  KycRow latest(long userId) {
    return mapper.latest(userId);
  }

  KycStatus status(long userId) {
    KycRow r = mapper.latest(userId);
    return r == null ? KycStatus.NONE : KycStatus.valueOf(r.status());
  }

  /** JPG 以 FF D8 FF 开头，PNG 以 89 50 4E 47 0D 0A 1A 0A 开头；其他一律拒绝 */
  static String imageExt(byte[] b) {
    if (b == null) return null;
    if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "jpg";
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    if (b.length >= 8) {
      for (int i = 0; i < 8; i++) if (b[i] != png[i]) return null;
      return "png";
    }
    return null;
  }

  @Transactional
  void submit(long userId, String docTypeRaw, String docNumberRaw, List<Image> images) {
    // 所有字段的问题一次报出（C56）
    List<String> errors = new ArrayList<>();
    DocType docType = parseDocType(docTypeRaw);
    if (docType == null) errors.add("docType:DOC_TYPE_INVALID");
    String docNumber = docNumberRaw == null ? "" : docNumberRaw.trim().toUpperCase();
    if (docNumber.isEmpty()) errors.add("docNumber:REQUIRED");
    else if (docNumber.length() > 64 || !docNumber.matches("[A-Z0-9-]+")) errors.add("docNumber:DOC_NUMBER_INVALID");
    Map<String, String> ext = new LinkedHashMap<>();
    for (Image img : images) {
      if (img.bytes() == null || img.bytes().length == 0) errors.add(img.field() + ":REQUIRED");
      else if (img.bytes().length > MAX_BYTES) errors.add(img.field() + ":FILE_TOO_LARGE");
      else if (imageExt(img.bytes()) == null) errors.add(img.field() + ":FILE_TYPE_INVALID");
      else ext.put(img.field(), imageExt(img.bytes()));
    }
    if (!errors.isEmpty()) throw new KycValidationException(errors);

    KycRow last = mapper.latest(userId);
    if (last != null && "PENDING".equals(last.status())) throw new BizException("KYC_PENDING", "已提交，正在审核");
    if (last != null && "APPROVED".equals(last.status())) throw new BizException("KYC_APPROVED", "已通过认证");
    if (mapper.countDocUsedByOthers(userId, docType.name(), docNumber) > 0) throw new BizException("DOC_NUMBER_USED", "该证件已被其他账户使用");

    KycMapper.KycInsert row = new KycMapper.KycInsert(userId, docType.name(), docNumber);
    mapper.insert(row);
    Map<String, String> keys = new LinkedHashMap<>();
    for (Image img : images) {
      String key = "kyc/" + userId + "/" + row.id + "-" + img.field().toLowerCase() + "." + ext.get(img.field());
      storage.put(key, img.bytes(), "jpg".equals(ext.get(img.field())) ? "image/jpeg" : "image/png");
      keys.put(img.field(), key);
    }
    mapper.updateKeys(row.id, keys.get("front"), keys.get("back"), keys.get("selfie"));
  }

  @Transactional
  void approve(long applicationId, long reviewerId) {
    review(applicationId, reviewerId, "APPROVED", null);
  }

  @Transactional
  void reject(long applicationId, long reviewerId, String reason) {
    if (reason == null || reason.isBlank()) throw new BizException("REJECT_REASON_REQUIRED", "驳回必须填写原因");
    if (reason.length() > 500) throw new BizException("REJECT_REASON_TOO_LONG", "驳回原因最多 500 字");
    review(applicationId, reviewerId, "REJECTED", reason.trim());
  }

  private void review(long id, long reviewerId, String status, String reason) {
    KycRow r = mapper.lockById(id);
    if (r == null) throw new BizException("KYC_NOT_FOUND", "申请不存在");
    if (!"PENDING".equals(r.status())) throw new BizException("KYC_NOT_PENDING", "只能审核待审核的申请");
    mapper.review(id, status, reviewerId, reason);
    Map<String, String> params = new LinkedHashMap<>();
    if (reason != null) params.put("reason", reason);
    // 站内信、推送、邮件（AC-05），和审核同一事务。
    // 2026-10-21（Amos 体验建议第 11 条）：通过和驳回分成两个模板（迁移 V30），通过的整段文案在模板里、运营可改，
    // 不再由 {result} 变量带出"现在可以提现"。旧模板 KYC_RESULT 只留给已经发出的站内信按原样显示。
    notify.toUser(r.userId(), "APPROVED".equals(status) ? "KYC_APPROVED" : "KYC_REJECTED", params);
  }

  private static DocType parseDocType(String raw) {
    if (raw == null) return null;
    return switch (raw) {
      case "idCard", "ID_CARD" -> DocType.ID_CARD;
      case "passport", "PASSPORT" -> DocType.PASSPORT;
      default -> null;
    };
  }

  /** 字段校验失败：errors 的每一项是"字段:错误码" */
  static final class KycValidationException extends RuntimeException {
    final List<String> errors;

    KycValidationException(List<String> errors) {
      super("KYC 字段不合法");
      this.errors = errors;
    }
  }
}
