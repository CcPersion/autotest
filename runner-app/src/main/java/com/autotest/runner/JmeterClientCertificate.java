package com.autotest.runner;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 本次运行使用的 PKCS12 客户端证书。
 *
 * <p>证书文件只存在于当前运行目录，密码只在内存中短暂存在，并且不会
 * 参与 JMX 或 Runner 日志序列化。</p>
 */
public final class JmeterClientCertificate {

    private final Path file;
    private final String password;

    public JmeterClientCertificate(Path file, String password) {
        this.file = Objects.requireNonNull(file, "证书文件不能为空").toAbsolutePath().normalize();
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("PKCS12 证书密码不能为空");
        }
        this.password = password;
    }

    public Path file() {
        return file;
    }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return "JmeterClientCertificate[file=" + file + ", password=<masked>]";
    }
}
