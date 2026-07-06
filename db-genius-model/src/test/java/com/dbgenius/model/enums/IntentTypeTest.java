package com.dbgenius.model.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fromCode_shouldMatchCode() {
        assertEquals(IntentType.SQL_QUERY, IntentType.fromCode("sql_query"));
        assertEquals(IntentType.SIMPLE_CHAT, IntentType.fromCode("simple_chat"));
        assertEquals(IntentType.WORKFLOW, IntentType.fromCode("workflow"));
        assertEquals(IntentType.DB_COMPARE, IntentType.fromCode("db_compare"));
    }

    @Test
    void fromCode_shouldMatchNameCaseInsensitive() {
        assertEquals(IntentType.SQL_QUERY, IntentType.fromCode("SQL_QUERY"));
        assertEquals(IntentType.SIMPLE_CHAT, IntentType.fromCode("Simple_Chat"));
    }

    @Test
    void jackson_shouldSerializeToCode() throws Exception {
        String json = objectMapper.writeValueAsString(IntentType.SQL_QUERY);
        assertEquals("\"sql_query\"", json);
    }

    @Test
    void jackson_shouldDeserializeFromCode() throws Exception {
        IntentType type = objectMapper.readValue("\"sql_query\"", IntentType.class);
        assertEquals(IntentType.SQL_QUERY, type);
    }

    @Test
    void jackson_shouldDeserializeFromName() throws Exception {
        IntentType type = objectMapper.readValue("\"SQL_QUERY\"", IntentType.class);
        assertEquals(IntentType.SQL_QUERY, type);
    }
}
