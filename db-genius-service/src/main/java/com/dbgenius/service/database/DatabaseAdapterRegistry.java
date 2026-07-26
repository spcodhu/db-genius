package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库适配器注册表（注册表模式 Registry + 简单工厂）。
 *
 * <p><b>设计说明：</b>所有 {@link DatabaseAdapter} 实现均为 Spring Bean，
 * 本注册表在构造期收集并按 {@link DbType} 建立不可变索引，调用方按类型取策略，
 * 彻底消除散落各处的 if-else / switch 类型分派。新增数据库类型时注册表零改动。</p>
 */
@Component
public class DatabaseAdapterRegistry {

    /** 类型 → 策略 的不可变索引（EnumMap 保证按枚举序高效查找） */
    private final Map<DbType, DatabaseAdapter> adapters;

    /**
     * 构造期由 Spring 注入全部适配器 Bean 并建索引。
     *
     * @param adapterList 容器中所有 {@link DatabaseAdapter} 实现
     */
    public DatabaseAdapterRegistry(List<DatabaseAdapter> adapterList) {
        Map<DbType, DatabaseAdapter> map = new EnumMap<>(DbType.class);
        for (DatabaseAdapter adapter : adapterList) {
            // 同一类型重复注册属于配置错误，启动期即暴露（快速失败）
            DatabaseAdapter prev = map.put(adapter.getType(), adapter);
            if (prev != null) {
                throw new IllegalStateException("Duplicate DatabaseAdapter for type: " + adapter.getType());
            }
        }
        this.adapters = Map.copyOf(map);
    }

    /**
     * 按类型编码获取适配器。
     *
     * @param dbTypeCode 类型编码（如 "mysql"），null/空白按历史兼容规则回退为 mysql
     * @return 对应适配器
     * @throws BusinessException 类型不受支持或适配器缺失时抛出
     */
    public DatabaseAdapter getAdapter(String dbTypeCode) {
        return getAdapter(DbType.fromCode(dbTypeCode));
    }

    /**
     * 按类型枚举获取适配器。
     *
     * @param type 数据库类型
     * @return 对应适配器
     * @throws BusinessException 该类型未注册适配器时抛出
     */
    public DatabaseAdapter getAdapter(DbType type) {
        DatabaseAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new BusinessException(400, "No adapter registered for database type: " + type.getCode());
        }
        return adapter;
    }
}
