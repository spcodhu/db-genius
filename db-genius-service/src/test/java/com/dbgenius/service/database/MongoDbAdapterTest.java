package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDbAdapter 单元测试：校验正反例、各 operation 的只读判断。
 * executeCommand / testConnection 需要真实 MongoDB，不在单测范围内。
 */
class MongoDbAdapterTest {

    private final MongoDbAdapter adapter = new MongoDbAdapter();

    private DbConfigRequest validRequest() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setHost("localhost");
        request.setPort(27017);
        request.setDbName("testdb");
        return request;
    }

    @Test
    void getType为MONGODB() {
        assertEquals(DbType.MONGODB, adapter.getType());
    }

    @Test
    void 校验通过_账密可空() {
        // MongoDB 可无认证部署，username/password 不填也应通过
        assertDoesNotThrow(() -> adapter.validateRequest(validRequest()));
    }

    @Test
    void 校验失败_缺host抛400() {
        DbConfigRequest request = validRequest();
        request.setHost(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 校验失败_缺port抛400() {
        DbConfigRequest request = validRequest();
        request.setPort(null);
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 校验失败_缺dbName抛400() {
        DbConfigRequest request = validRequest();
        request.setDbName("  ");
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 只读判断_find_count_distinct() {
        assertTrue(adapter.isReadOnlyStatement("{\"collection\":\"c\",\"operation\":\"find\"}"));
        assertTrue(adapter.isReadOnlyStatement("{\"collection\":\"c\",\"operation\":\"count\",\"filter\":{}}"));
        assertTrue(adapter.isReadOnlyStatement("{\"collection\":\"c\",\"operation\":\"distinct\",\"field\":\"name\"}"));
    }

    @Test
    void 只读判断_aggregate按管道内容区分() {
        // 纯查询管道为只读
        assertTrue(adapter.isReadOnlyStatement(
                "{\"collection\":\"c\",\"operation\":\"aggregate\",\"pipeline\":[{\"$match\":{\"a\":1}}]}"));
        // 含 $out / $merge 的管道是写操作，非只读
        assertFalse(adapter.isReadOnlyStatement(
                "{\"collection\":\"c\",\"operation\":\"aggregate\",\"pipeline\":[{\"$out\":\"other\"}]}"));
        assertFalse(adapter.isReadOnlyStatement(
                "{\"collection\":\"c\",\"operation\":\"aggregate\",\"pipeline\":[{\"$merge\":{\"into\":\"other\"}}]}"));
    }

    @Test
    void 只读判断_写操作与非法输入() {
        assertFalse(adapter.isReadOnlyStatement("{\"collection\":\"c\",\"operation\":\"insert\"}"));
        assertFalse(adapter.isReadOnlyStatement("{\"collection\":\"c\",\"operation\":\"update\"}"));
        // 缺 operation、非法 JSON、null/空白：一律按非只读处理（宁严勿宽）
        assertFalse(adapter.isReadOnlyStatement("{\"collection\":\"c\"}"));
        assertFalse(adapter.isReadOnlyStatement("not a json"));
        assertFalse(adapter.isReadOnlyStatement(null));
        assertFalse(adapter.isReadOnlyStatement("  "));
    }
}
