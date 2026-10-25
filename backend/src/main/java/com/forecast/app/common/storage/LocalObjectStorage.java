package com.forecast.app.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本地目录实现（目录由 FC_STORAGE_DIR 指定）。key 只能是程序生成的路径，拒绝 ".." 之类的写法。 */
@Component
class LocalObjectStorage implements ObjectStorage {

  private final Path root;

  LocalObjectStorage(@Value("${forecast.storage.local-dir}") String dir) {
    this.root = Path.of(dir).toAbsolutePath().normalize();
  }

  private Path resolve(String key) {
    if (key == null || !key.matches("[a-z0-9/_.-]+") || key.contains("..")) throw new IllegalArgumentException("非法的存储路径：" + key);
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException("非法的存储路径：" + key);
    return p;
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    Path p = resolve(key);
    try {
      Files.createDirectories(p.getParent());
      Files.write(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public byte[] get(String key) {
    Path p = resolve(key);
    try {
      return Files.exists(p) ? Files.readAllBytes(p) : null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
