# 安全相关的响应头（S20）。测试环境 HSTS 只设 1 天，生产再加长。
add_header Strict-Transport-Security "max-age=86400" always;
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
