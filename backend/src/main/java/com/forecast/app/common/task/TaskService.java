package com.forecast.app.common.task;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务表（00-架构方案.md 3.3 ③）：出金广播、发邮件、发推送等可靠的异步工作都写成任务。
 * <ul>
 *   <li>入队可以和业务写入放在同一个事务里，业务提交了任务才存在。</li>
 *   <li>领取用 {@code FOR UPDATE SKIP LOCKED}，同一个任务不会被同时领取。</li>
 *   <li>失败按 2、4、8……秒退避重试（最长 1 小时），次数用完标为失败，后台可以看到并手动重试。</li>
 *   <li>处理中超时没有结果的任务会被回收成待处理（修复 fxsystem Outbox 卡在"发送中"无人回收的问题）。</li>
 * </ul>
 * 具体的任务执行器在用到的步骤里加（S13 出金、S15 通知）。
 */
@Service
public class TaskService {

  static final long MAX_BACKOFF_SECONDS = 3600;

  private final TaskMapper mapper;

  TaskService(TaskMapper mapper) {
    this.mapper = mapper;
  }

  /** 入队；payload 是 JSON 字符串。返回任务 ID。 */
  @Transactional
  public long enqueue(String taskType, String payload, long delaySeconds, int maxAttempts) {
    mapper.insert(taskType, payload, delaySeconds, maxAttempts);
    return mapper.lastInsertId();
  }

  /** 一批同类型的任务（forecast S5：批量通知），和逐个 {@link #enqueue} 一样，只是一条语句写一段 */
  public void enqueueAll(String taskType, java.util.List<String> payloads, long delaySeconds, int maxAttempts) {
    for (int i = 0; i < payloads.size(); i += 500) mapper.insertAll(taskType, payloads.subList(i, Math.min(payloads.size(), i + 500)), delaySeconds, maxAttempts);
  }

  /** 领取最多 limit 个到期的待处理任务，标为处理中。 */
  @Transactional
  public List<Task> claim(String taskType, String worker, int limit) {
    List<Long> ids = mapper.lockDue(taskType, limit);
    if (ids.isEmpty()) return List.of();
    mapper.markRunning(ids, worker);
    return mapper.findByIds(ids);
  }

  @Transactional
  public void succeed(long id) {
    mapper.markSucceeded(id);
  }

  /** 处理失败：还有次数就退避后重试，否则标为失败。 */
  @Transactional
  public void fail(long id, String error) {
    Task t = mapper.find(id);
    if (t == null) return;
    String msg = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
    if (t.attempts() >= t.maxAttempts()) {
      mapper.markFailed(id, msg);
    } else {
      mapper.markRetry(id, backoffSeconds(t.attempts()), msg);
    }
  }

  /** 把处理中超过 timeoutSeconds 还没有结果的任务回收成待处理，返回回收数量。 */
  @Transactional
  public int recoverStuck(long timeoutSeconds) {
    return mapper.recoverStuck(timeoutSeconds);
  }

  /** 后台任务列表（S16b）；taskType、status 为空表示不筛选 */
  @Transactional(readOnly = true)
  public List<TaskItem> list(String taskType, TaskStatus status, int offset, int limit) {
    return mapper.list(taskType, status == null ? null : status.name(), offset, limit);
  }

  @Transactional(readOnly = true)
  public long count(String taskType, TaskStatus status) {
    return mapper.count(taskType, status == null ? null : status.name());
  }

  /** 后台手动重试：只有失败的能重试，次数清零后重新排队。返回是否重试了 */
  @Transactional
  public boolean retry(long id) {
    return mapper.retry(id) == 1;
  }

  @Transactional(readOnly = true)
  public Task find(long id) {
    return mapper.find(id);
  }

  /** 第 n 次失败后等待 2^n 秒，最长 1 小时 */
  static long backoffSeconds(int attempts) {
    return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 12));
  }
}
