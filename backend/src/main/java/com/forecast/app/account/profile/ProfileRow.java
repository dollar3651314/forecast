package com.forecast.app.account.profile;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 个人资料（account_profile + 用户表的邮箱和注册时间）。 */
record ProfileRow(long userId, String email, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality, LocalDateTime registeredAt) {}
