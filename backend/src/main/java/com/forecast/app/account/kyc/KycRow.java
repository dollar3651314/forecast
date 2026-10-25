package com.forecast.app.account.kyc;

import java.time.LocalDateTime;

/** account_kyc_application 的一行。 */
record KycRow(long id, long userId, String docType, String docNumber, String frontKey, String backKey, String selfieKey, String status,
    String rejectReason, LocalDateTime submittedAt, LocalDateTime reviewedAt) {}
