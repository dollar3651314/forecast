package com.forecast.app.common.storage;

/**
 * 对象存储：证件照片等文件放这里，数据库只存路径（key）。
 * 现在的实现是本地目录（{@link LocalObjectStorage}）；正式环境换成对象存储服务，在 S20 部署时定（需要 Amos 提供账号）。
 */
public interface ObjectStorage {

  void put(String key, byte[] content, String contentType);

  /** 文件不存在时返回 null */
  byte[] get(String key);
}
