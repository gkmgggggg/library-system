package com.example.library.common;

/** CLI 与 Bootstrap 共用的校验；错误信息永远不包含凭据内容。 */
public final class AdminCredentials {
    private AdminCredentials() {}

    public static void validateUsername(String username) {
        if (username == null || !username.matches("^[A-Za-z0-9_]{3,32}$")) {
            throw new IllegalArgumentException("用户名必须为 3～32 位英文字母、数字或下划线");
        }
    }

    public static void validate(String username, String password) {
        validateUsername(username);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空或全为空白，请输入 5～128 个字符");
        }
        if (password.length() < 5 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度不符合要求，请输入 5～128 个字符");
        }
    }

    /** 不启动 Spring 或连接数据库；凭据仅通过当前进程环境读取。 */
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("username")) {
                validateUsername(System.getenv("ADMIN_USERNAME"));
            } else {
                validate(System.getenv("ADMIN_USERNAME"), System.getenv("ADMIN_PASSWORD"));
            }
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
