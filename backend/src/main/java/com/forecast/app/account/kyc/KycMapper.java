package com.forecast.app.account.kyc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** KYC 申请的 SQL，见 resources/mapper/account/KycMapper.xml。 */
@Mapper
interface KycMapper {

  KycRow latest(@Param("userId") long userId);

  KycRow lockById(@Param("id") long id);

  /** 别的用户用同一证件、且审核中或已通过的申请数 */
  int countDocUsedByOthers(@Param("userId") long userId, @Param("docType") String docType, @Param("docNumber") String docNumber);

  int insert(KycInsert row);

  int updateKeys(@Param("id") long id, @Param("frontKey") String frontKey, @Param("backKey") String backKey, @Param("selfieKey") String selfieKey);

  int review(@Param("id") long id, @Param("status") String status, @Param("reviewerId") long reviewerId, @Param("reason") String reason);

  /** 插入时 MyBatis 回填 id；文件路径要用 id，所以先插入占位路径再更新 */
  final class KycInsert {
    Long id;
    final long userId;
    final String docType;
    final String docNumber;

    KycInsert(long userId, String docType, String docNumber) {
      this.userId = userId;
      this.docType = docType;
      this.docNumber = docNumber;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getUserId() { return userId; }
    public String getDocType() { return docType; }
    public String getDocNumber() { return docNumber; }
  }
}
