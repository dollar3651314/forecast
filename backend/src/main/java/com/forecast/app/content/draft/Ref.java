package com.forecast.app.content.draft;

/**
 * "引用的数据"的一条（forecast S15，自动化需求第 4.3 节第 1 条）：每条草稿都附这个清单，数字核对按它（{@code check/NumberCheck}）。
 *
 * @param key 程序里的名字（例如 option.1.pct）
 * @param label 中文说明（后台显示、中文提示用）
 * @param labelEn 英文说明（英文提示用）
 * @param value 数值（原样的文字：金额 2 位小数、概率 1 位小数、时间 "2026-10-27 19:55 UTC"）
 * @param source 来源（"本站奖池"、"Binance BTC/USDT 1 小时 K 线"、"平台规则"……）
 * @param at 取数时间（ISO）
 */
public record Ref(String key, String label, String labelEn, String value, String source, String at) {}
