package com.dbgenius.common.util;

import com.dbgenius.common.exception.BusinessException;

import java.util.regex.Pattern;

/**
 * SQL / 数据库命令安全守卫（门面模式 + 安全红线统一拦截点）。
 *
 * <p><b>设计说明：</b>所有「AI 工具执行数据库语句」的入口（{@code SqlExecuteTool}、
 * MongoDB 命令执行等）在执行前必须经过本守卫校验。破坏性命令在此被<strong>硬性拒绝</strong>，
 * 属于系统级安全红线：<b>即使用户在对话中明确要求，也绝不放行</b>。
 * 与提示词中的安全条款（最高优先级）共同构成「提示词约束 + 代码强制」的双层防护，
 * 即使大模型被提示注入绕过，代码层依然兜底。</p>
 *
 * <p>被拦截的命令：DROP（含 DROP TABLE / DROP DATABASE / ALTER TABLE ... DROP COLUMN 等
 * 一切 DROP 变体）、TRUNCATE；MongoDB 侧的 drop / dropDatabase 操作同样拦截。</p>
 */
public final class SqlSafetyGuard {

    /**
     * 破坏性关键字黑名单（词边界匹配，忽略大小写）。
     * 匹配前会先剥离注释与字符串字面量，避免误伤 'drop' 之类的普通文本。
     */
    private static final Pattern DESTRUCTIVE_PATTERN =
            Pattern.compile("\\b(DROP|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

    /** MongoDB 破坏性操作符黑名单（drop 集合 / dropDatabase 库） */
    private static final Pattern MONGO_DESTRUCTIVE_PATTERN =
            Pattern.compile("\\b(drop|dropDatabase)\\b", Pattern.CASE_INSENSITIVE);

    private SqlSafetyGuard() {
        // 工具类禁止实例化
    }

    /**
     * 判断 SQL 是否包含破坏性命令。
     *
     * @param sql 待检测 SQL
     * @return 包含 DROP / TRUNCATE 等破坏性命令时返回 true
     */
    public static boolean isDestructive(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        // 先剥离注释与字符串字面量，再在「干净的」语句上做词边界匹配
        return DESTRUCTIVE_PATTERN.matcher(stripCommentsAndLiterals(sql)).find();
    }

    /**
     * 判断 MongoDB 命令（JSON 文本或命令名）是否为破坏性操作。
     *
     * @param command MongoDB 命令内容
     * @return 包含 drop / dropDatabase 时返回 true
     */
    public static boolean isDestructiveMongoCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return MONGO_DESTRUCTIVE_PATTERN.matcher(stripCommentsAndLiterals(command)).find();
    }

    /**
     * 安全断言：SQL 含破坏性命令时直接抛出业务异常（安全红线，不可绕过）。
     *
     * @param sql 待执行 SQL
     * @throws BusinessException 命中破坏性命令时抛出（HTTP 403 语义）
     */
    public static void assertSafe(String sql) {
        if (isDestructive(sql)) {
            throw new BusinessException(403,
                    "安全红线：DROP / TRUNCATE 等破坏性命令被系统禁止执行，即使用户明确要求也不允许。");
        }
    }

    /**
     * MongoDB 命令安全断言。
     *
     * @param command 待执行 MongoDB 命令
     * @throws BusinessException 命中破坏性操作时抛出（HTTP 403 语义）
     */
    public static void assertMongoCommandSafe(String command) {
        if (isDestructiveMongoCommand(command)) {
            throw new BusinessException(403,
                    "安全红线：drop / dropDatabase 等破坏性操作被系统禁止执行，即使用户明确要求也不允许。");
        }
    }

    /**
     * 剥离 SQL 中的注释与字符串字面量，降低误报与绕过风险。
     *
     * <p>处理三类内容：-- 行注释、&#47;* *&#47; 块注释、单/双引号字符串（均替换为空格）。
     * 不追求完整 SQL 语法解析，仅服务于关键字扫描，实现上保持简单可靠。</p>
     */
    static String stripCommentsAndLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // 行注释：跳过到行尾
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释：跳过到闭合标记
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            // 字符串字面量：跳过到配对的引号（兼容 '' 转义）
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        // 连续两个相同引号视为转义，继续跳过
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                // 用一个空格占位，防止字面量两端的词粘连（如 'a'DROP'b'）
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
