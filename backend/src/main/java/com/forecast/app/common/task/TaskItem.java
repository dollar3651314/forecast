package com.forecast.app.common.task;

import java.time.LocalDateTime;

/** 后台任务列表的一行（不含任务内容，内容里可能有收件人等信息）。bizRef 是任务内容里的 recordId（通知记录号），没有时为空。 */
public record TaskItem(long id, String taskType, TaskStatus status, int attempts, int maxAttempts, LocalDateTime nextRunAt, String lastError,
    String bizRef, LocalDateTime createdAt, LocalDateTime updatedAt) {}
