package com.autotest.platform.auth;

import java.text.Normalizer;
import java.util.Locale;

/** 将用户输入转换为数据库和限流使用的唯一用户名形式。 */
public final class UsernameNormalizer {

    private UsernameNormalizer() {
    }

    public static String normalize(String username) {
        if (username == null) {
            return "";
        }
        return Normalizer.normalize(username, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
