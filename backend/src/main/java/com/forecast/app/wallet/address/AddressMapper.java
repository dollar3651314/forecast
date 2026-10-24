package com.forecast.app.wallet.address;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 充值地址和合约地址参数的 SQL，见 resources/mapper/wallet/AddressMapper.xml。 */
@Mapper
interface AddressMapper {

  /**
   * 一个充值地址。scheme：XPUB（xpub 派生）/ FORWARDER（合约转发地址，v2 归集）；status：ACTIVE（客户端显示、扫块）/ RETIRED（停用，扫块到停用后 N 天）。
   */
  record AddressRow(long id, long userId, String family, String scheme, int schemeVer, int addressIndex, String address, String salt, String status,
      LocalDateTime retiredAt, LocalDateTime deployedAt) {}

  record SchemeRow(int ver, String factory, String implementation, String parent, String feeAddress) {
    ForwarderScheme toScheme() {
      return new ForwarderScheme(ver, factory, implementation, parent, feeAddress);
    }
  }

  List<AddressRow> byUser(@Param("userId") long userId);

  /** 这个用户这个体系正在用的地址（加行锁） */
  AddressRow lockActive(@Param("userId") long userId, @Param("family") String family);

  /** 插入一个正在用的地址；这个体系已经有正在用的、或者同一套参数的地址已经有了时不插（返回 0） */
  int insertActive(@Param("userId") long userId, @Param("family") String family, @Param("scheme") String scheme, @Param("schemeVer") int schemeVer,
      @Param("index") int index, @Param("address") String address, @Param("key") String key, @Param("salt") String salt);

  /** 把以前停用的同一套参数的地址重新启用（参数换回去时） */
  int reactivate(@Param("userId") long userId, @Param("family") String family, @Param("scheme") String scheme, @Param("schemeVer") int schemeVer);

  int retire(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 收款地址属于谁：正在用的，或停用时间不早于 retiredSince 的 */
  Long userOf(@Param("family") String family, @Param("key") String key, @Param("retiredSince") LocalDateTime retiredSince);

  /** 这个地址是不是我们的（不管停用多久） */
  AddressRow ownerOf(@Param("family") String family, @Param("key") String key);

  /** 需要换成新一套合约地址的用户（这个体系正在用的不是这一套），按用户 ID 分页 */
  List<Long> usersToMigrate(@Param("family") String family, @Param("schemeVer") int schemeVer, @Param("afterUser") long afterUser, @Param("limit") int limit);

  int markDeployed(@Param("id") long id, @Param("now") LocalDateTime now);

  int insertSchemeIgnore(@Param("family") String family, @Param("factory") String factory, @Param("implementation") String implementation,
      @Param("parent") String parent, @Param("feeAddress") String feeAddress, @Param("key") String key);

  SchemeRow schemeByKey(@Param("key") String key);

  SchemeRow schemeByVer(@Param("ver") int ver);

  List<SchemeRow> schemes();
}
