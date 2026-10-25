package com.forecast.app.common.task;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务表的 SQL，见 resources/mapper/common/TaskMapper.xml。 */
@Mapper
interface TaskMapper {

  int insert(@Param("taskType") String taskType, @Param("payload") String payload, @Param("delaySeconds") long delaySeconds, @Param("maxAttempts") int maxAttempts);

  long lastInsertId();

  /** 一批同类型的任务（多行 INSERT，forecast S5） */
  int insertAll(@Param("taskType") String taskType, @Param("payloads") java.util.List<String> payloads, @Param("delaySeconds") long delaySeconds,
      @Param("maxAttempts") int maxAttempts);

  List<Long> lockDue(@Param("taskType") String taskType, @Param("limit") int limit);

  int markRunning(@Param("ids") List<Long> ids, @Param("worker") String worker);

  int markSucceeded(@Param("id") long id);

  int markRetry(@Param("id") long id, @Param("delaySeconds") long delaySeconds, @Param("error") String error);

  int markFailed(@Param("id") long id, @Param("error") String error);

  int recoverStuck(@Param("timeoutSeconds") long timeoutSeconds);

  Task find(@Param("id") long id);

  List<Task> findByIds(@Param("ids") List<Long> ids);

  List<TaskItem> list(@Param("taskType") String taskType, @Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long count(@Param("taskType") String taskType, @Param("status") String status);

  int retry(@Param("id") long id);

  /** 积压：已经到期还没处理的任务数（指标 fc_tasks_backlog，E10） */
  long countDue();
}
