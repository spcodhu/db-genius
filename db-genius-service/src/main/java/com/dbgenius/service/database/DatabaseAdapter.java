package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import com.dbgenius.model.metadata.SchemaMetadata;

/**
 * 数据库适配器 SPI（策略模式 Strategy + 适配器模式 Adapter）。
 *
 * <p><b>设计说明：</b>本接口是多数据库支持的核心抽象。每种数据库类型对应一个实现
 * （Spring Bean），把「连接测试、Schema 元数据抽取、只读判断、配置校验」等
 * 因数据库而异的行为收敛到统一契约之后，上层（{@code DbConfigServiceImpl}、
 * {@code SqlExecuteTool}、{@code DbCompareTool}）只面向本接口编程，
 * 通过 {@code DatabaseAdapterRegistry} 按 {@link DbType} 动态选择策略。</p>
 *
 * <p><b>类层次（模板方法模式）：</b></p>
 * <pre>
 * DatabaseAdapter（策略接口）
 *   ├── AbstractJdbcAdapter（JDBC 系模板：MySQL / PostgreSQL / SQLite）
 *   └── MongoDbAdapter（非 JDBC，独立实现）
 * </pre>
 *
 * <p><b>扩展指南（开闭原则）：</b>新增数据库（包括向量数据库）时：
 * ① 在 {@link DbType} 追加枚举值；② 新建实现类（JDBC 系继承
 * {@code AbstractJdbcAdapter} 只需实现 URL 模板等少量钩子，非 JDBC 直接实现本接口）；
 * ③ 标注 {@code @Component} 即被注册表自动收集，无需修改任何既有代码。</p>
 */
public interface DatabaseAdapter {

    /**
     * 本适配器支持的数据库类型（注册表以此作为策略键）。
     *
     * @return 数据库类型枚举
     */
    DbType getType();

    /**
     * 按数据库类型对配置请求做条件校验。
     *
     * <p>不同类型的必填项不同：MySQL/PostgreSQL 需要 host/port/账密，
     * SQLite 只需要 dbName（文件路径），MongoDB 账密可空。
     * 因此 DTO 层不做固定校验，统一委托给本方法。</p>
     *
     * @param request 配置请求
     * @throws com.dbgenius.common.exception.BusinessException 校验不通过时抛出
     */
    void validateRequest(DbConfigRequest request);

    /**
     * 测试目标库连通性。
     *
     * @param config            数据库配置
     * @param decryptedPassword 已解密的明文密码（可为 null，如 SQLite）
     * @return 连接可用返回 true，任何异常均捕获并返回 false
     */
    boolean testConnection(DbConfig config, String decryptedPassword);

    /**
     * 抽取数据库 Schema 元数据，收敛为中性模型 {@link SchemaMetadata}。
     *
     * <p>这是文档生成与库间结构对比的统一数据源。实现方应尽量保证部分失败不中断
     * （把错误记入 {@link SchemaMetadata#errorMessage}）。</p>
     *
     * @param config            数据库配置
     * @param decryptedPassword 已解密的明文密码（可为 null）
     * @return 中性 Schema 元数据
     */
    SchemaMetadata extractSchema(DbConfig config, String decryptedPassword);

    /**
     * 判断语句是否为只读（方言感知）。
     *
     * <p>SQL 系判断 SELECT/SHOW/DESC/EXPLAIN 等前缀；MongoDB 判断 find/count 等
     * 查询操作。用于试用版只读限制等场景。破坏性命令（DROP/TRUNCATE）不在此判断，
     * 由 {@code SqlSafetyGuard} 统一硬性拦截。</p>
     *
     * @param statement 待判断的语句 / 命令
     * @return 只读返回 true
     */
    boolean isReadOnlyStatement(String statement);
}
