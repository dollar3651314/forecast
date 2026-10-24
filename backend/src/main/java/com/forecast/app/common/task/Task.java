package com.forecast.app.common.task;

import java.time.LocalDateTime;

/** common_task 的一行。 */
public record Task(long id, String taskType, String payload, TaskStatus status, int attempts, int maxAttempts, LocalDateTime nextRunAt, String lastError) {}
