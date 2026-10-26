package com.forecast.app.common.job;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 定时任务租约的 SQL（表 {@code common_job_lease}），见 resources/mapper/common/JobLeaseMapper.xml。时间一律用数据库时间，多实例之间不受各自时钟影响。 */
@Mapper
interface JobLeaseMapper {

  /** 没有这一行时建一行（已过期的租约） */
  int ensure(@Param("name") String name);

  /** 租约过期或本来就是自己的：拿到（续上）租约，返回 1；否则 0 */
  int acquire(@Param("name") String name, @Param("owner") String owner, @Param("ttlMillis") long ttlMillis);

  /** 只有自己持有时才续约 */
  int renew(@Param("name") String name, @Param("owner") String owner, @Param("ttlMillis") long ttlMillis);

  /** 只有自己持有时才释放（把到期时间改成早已过去的时间，别的实例立刻能拿到） */
  int release(@Param("name") String name, @Param("owner") String owner);
}
