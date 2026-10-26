package com.forecast.app.account.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 后台查看客户和 KYC（S16b）。只读；冻结、审核走 {@link AccountApi}。 */
public interface CustomerAdminApi {

  /** 一页结果 */
  record Page<T>(List<T> items, long total) {}

  /**
   * 客户摘要。
   *
   * @param status ACTIVE / FROZEN
   * @param kycStatus NONE / PENDING / APPROVED / REJECTED
   * @param staff 员工账户（forecast S10：后台登记，不能下注）
   */
  record Customer(long id, String email, String name, String nationality, String status, String kycStatus, LocalDateTime registeredAt,
      LocalDateTime lastLoginAt, boolean staff) {}

  /**
   * 客户详情：资料、最近一次 KYC、最近一次登录的 IP（v2 E6，取最近一个登录会话的 IP；从没登录过为空）。
   */
  record CustomerDetail(Customer customer, String firstName, String middleName, String lastName, LocalDate birthDate, String frozenReason,
      String docType, String docNumber, LocalDateTime kycReviewedAt, String kycRejectReason, String lastLoginIp, LocalDateTime lastLoginIpAt) {}

  /** 后台编辑客户资料（v2 E1）：只有这 5 项；邮箱、状态不在这里改。birthDate 是 YYYY-MM-DD */
  record ProfileEdit(String firstName, String middleName, String lastName, String birthDate, String nationality) {}

  /** KYC 申请；status PENDING / APPROVED / REJECTED，docType ID_CARD / PASSPORT */
  record KycApplication(long id, long userId, String name, String email, String nationality, LocalDate birthDate, String docType, String docNumber,
      String status, LocalDateTime submittedAt, Long reviewerId, LocalDateTime reviewedAt, String rejectReason) {}

  /** 证件照片 */
  record Image(byte[] content, String contentType) {}

  /** keyword 匹配邮箱、姓名、客户号；status、kycStatus 为空表示不筛选 */
  Page<Customer> customers(String keyword, String status, String kycStatus, int offset, int limit);

  Optional<CustomerDetail> customer(long id);

  /** 按客户号批量取摘要（后台列表显示客户名） */
  Map<Long, Customer> customers(Collection<Long> ids);

  /** 按邮箱、姓名、客户号找客户的客户号，最多 limit 个（后台各列表按客户搜索时用） */
  List<Long> search(String keyword, int limit);

  /**
   * 登记 / 取消登记员工账户（forecast S10，需求第 3.3、8.1 节）：只改标记，审计和 E15 的退回由调用方（后台）做。
   *
   * @return 改之前是不是员工；客户不存在时抛 {@code NOT_FOUND}
   */
  boolean setStaff(long userId, boolean staff);

  /** 冻结（填原因）或解冻；冻结后该客户所有登录立即失效（AC-04） */
  void setFrozen(long userId, boolean frozen, String reason);

  /**
   * 后台编辑客户资料（v2 E1）。校验规则和客户端注册一致（{@code web/client/src/lib/validators.ts} 的 validateProfile），
   * 字段错误一次全部返回；KYC 已通过的客户也允许改（由后台标出，和证件可能不一致）。
   *
   * @return 改之前的资料
   */
  ProfileEdit updateProfile(long userId, ProfileEdit edit);

  Page<KycApplication> kycApplications(String status, String keyword, int offset, int limit);

  Optional<KycApplication> kycApplication(long id);

  /** side：front / back / selfie */
  Optional<Image> kycImage(long applicationId, String side);

  long pendingKyc();

  /** 仪表盘（S16c）：从 since 起注册的客户数 */
  long registeredSince(java.time.LocalDateTime since);

  /** 仪表盘：从 since 起有活动的客户数（登录会话在这段时间里有请求） */
  long activeSince(java.time.LocalDateTime since);
}
