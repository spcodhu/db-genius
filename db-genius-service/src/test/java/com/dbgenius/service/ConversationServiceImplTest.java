package com.dbgenius.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dbgenius.mapper.MessageMapper;
import com.dbgenius.model.entity.Message;
import com.dbgenius.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证消息持久化扩展：
 * 1. saveMessage 新重载正确写入 reasoning_content / tool_calls；
 * 2. 旧签名（5 处既有调用方）保持字段为 null；
 * 3. getRecentMessages 上下文隔离：只取 user/summary，排除 step/tool 过程消息。
 */
class ConversationServiceImplTest {

    @BeforeAll
    static void initTableInfoCache() {
        // 单测无 Spring 环境，LambdaQueryWrapper.getSqlSegment() 需要 MyBatis-Plus 的
        // TableInfo 静态缓存（生产由 mapper 注册时初始化）
        TableInfoHelper.initTableInfo(
                new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class);
    }

    @Test
    void saveMessageShouldPersistReasoningAndToolCalls() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(messageMapper);

        service.saveMessage(1L, "assistant", "我先确认表结构。", 2, "step",
                "需要先确认表结构", "[{\"id\":\"call_1\",\"type\":\"function\",\"name\":\"echo\",\"arguments\":\"{}\"}]");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        Message saved = captor.getValue();
        assertThat(saved.getConversationId()).isEqualTo(1L);
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getContent()).isEqualTo("我先确认表结构。");
        assertThat(saved.getStep()).isEqualTo(2);
        assertThat(saved.getType()).isEqualTo("step");
        assertThat(saved.getReasoningContent()).isEqualTo("需要先确认表结构");
        assertThat(saved.getToolCalls()).contains("call_1");
    }

    @Test
    void legacySaveMessageShouldLeaveNewFieldsNull() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(messageMapper);

        service.saveMessage(1L, "assistant", "最终回答", -1, "summary");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        Message saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("summary");
        assertThat(saved.getReasoningContent()).isNull();
        assertThat(saved.getToolCalls()).isNull();
    }

    @Test
    void getRecentMessagesShouldFilterToUserAndSummaryOnly() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(messageMapper);

        Message user = message(1L, "user", "帮我查用户数", null, "user");
        Message summary = message(1L, "assistant", "共 42 行", -1, "summary");
        Message step = message(1L, "assistant", "我先确认表结构", 1, "step");
        Message tool = message(1L, "tool", "[{\"name\":\"echo\"}]", 1, "tool");
        when(messageMapper.selectList(any())).thenReturn(List.of(user, summary, step, tool));

        List<Message> result = service.getRecentMessages(1L, 10);

        ArgumentCaptor<LambdaQueryWrapper<Message>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("conversation_id =");
        // 上下文隔离：SQL 必须带 type 过滤（排除 step/tool）
        assertThat(sqlSegment).contains("type IN (");
        assertThat(sqlSegment).contains("type IS NULL");
    }

    private Message message(Long conversationId, String role, String content, Integer step, String type) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStep(step);
        message.setType(type);
        return message;
    }
}
