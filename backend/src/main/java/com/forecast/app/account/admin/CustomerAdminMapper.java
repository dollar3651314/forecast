package com.forecast.app.account.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 后台查看客户和 KYC 的查询，以及后台编辑客户资料（v2 E1）（account_* 表）。只有 account 模块使用。 */
@Mapper
interface CustomerAdminMapper {

  class CustomerRow {
    long id;
    String email;
    String status;
    String frozenReason;
    LocalDateTime createdAt;
    LocalDateTime lastLoginAt;
    String firstName;
    String middleName;
    String lastName;
    LocalDate birthDate;
    String nationality;
    String kycStatus;
    String docType;
    String docNumber;
    LocalDateTime kycReviewedAt;
    String kycRejectReason;
    boolean staff;
  }

  class KycRow {
    long id;
    long userId;
    String email;
    String firstName;
    String middleName;
    String lastName;
    String nationality;
    LocalDate birthDate;
    String docType;
    String docNumber;
    String frontKey;
    String backKey;
    String selfieKey;
    String status;
    LocalDateTime submittedAt;
    Long reviewerId;
    LocalDateTime reviewedAt;
    String rejectReason;
  }

  /** 最近一次登录：最新的登录会话的 IP 和创建时间（v2 E6） */
  class LastLogin {
    String ip;
    LocalDateTime createdAt;
  }

  LastLogin lastLogin(@Param("userId") long userId);

  /** 写资料：没有资料行时补一行（注册时一定会写，这里只防万一） */
  void upsertProfile(@Param("userId") long userId, @Param("firstName") String firstName, @Param("middleName") String middleName,
      @Param("lastName") String lastName, @Param("birthDate") LocalDate birthDate, @Param("nationality") String nationality);

  List<CustomerRow> customers(@Param("keyword") String keyword, @Param("id") Long id, @Param("status") String status, @Param("kycStatus") String kycStatus,
      @Param("offset") int offset, @Param("limit") int limit);

  long countCustomers(@Param("keyword") String keyword, @Param("id") Long id, @Param("status") String status, @Param("kycStatus") String kycStatus);

  List<CustomerRow> byIds(@Param("ids") Collection<Long> ids);

  /** 锁住客户行，返回 is_staff；客户不存在时为空 */
  Boolean lockStaff(@Param("id") long id);

  int setStaff(@Param("id") long id, @Param("staff") boolean staff);

  void setFrozenReason(@Param("id") long id, @Param("reason") String reason);

  List<KycRow> kycs(@Param("status") String status, @Param("keyword") String keyword, @Param("id") Long id, @Param("offset") int offset,
      @Param("limit") int limit);

  long countKycs(@Param("status") String status, @Param("keyword") String keyword, @Param("id") Long id);

  KycRow kyc(@Param("id") long id);

  long pendingKyc();

  long registeredSince(@Param("since") LocalDateTime since);

  long activeSince(@Param("since") LocalDateTime since);
}
