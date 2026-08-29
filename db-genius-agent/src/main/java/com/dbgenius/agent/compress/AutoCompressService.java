package com.dbgenius.agent.compress;

import com.dbgenius.model.entity.Conversation;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.UserModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 自动压缩钩子：下一轮会话开始前（IntentRouter 加载历史之后、意图分类之前）检查
 * 上下文占用，达到阈值即触发压缩链。
 *
 * <p>实际压缩策略由 {@code db-genius.context.auto-compress.strategy} 决定（默认
 * {@link SummaryContextCompressor}，可配置回退为 {@link NoopContextCompressor}），本类只负责
 * "是否需要压缩"的判断，默认关闭（db-genius.context.auto-compress.enabled=false）。
 * 选择"下一轮开始前"而非轮末异步：确定性强、无流式中途竞态、压缩后的历史对本轮立即生效。
 */
@Slf4j
@Service
public class AutoCompressService {

    private final ContextCompressService compressService;
    private final ConversationService conversationService;
    private final UserModelConfigService userModelConfigService;

    @Value("${db-genius.context.auto-compress.enabled:false}")
    private boolean enabled;

    /** 与前端提示阈值语义一致：占用达到窗口 80% 触发 */
    @Value("${db-genius.context.auto-compress.threshold:0.8}")
    private double threshold;

    public AutoCompressService(ContextCompressService compressService,
                               ConversationService conversationService,
                               UserModelConfigService userModelConfigService) {
        this.compressService = compressService;
        this.conversationService = conversationService;
        this.userModelConfigService = userModelConfigService;
    }

    /**
     * 占用达到阈值时压缩；任何异常静默降级，不影响正常会话流程。
     *
     * @param locale 本轮请求的语言环境（本方法跑在异步线程，ThreadLocal 已失效，必须显式传入）
     */
    public void compressIfNeeded(Long conversationId, Long userId, Locale locale) {
        if (!enabled || conversationId == null) {
            return;
        }
        try {
            Integer contextWindow = userModelConfigService.getActiveConfig(userId).getContextWindow();
            if (contextWindow == null) {
                return;
            }
            Conversation conversation = conversationService.getById(conversationId);
            Integer contextTokens = conversation != null ? conversation.getContextTokens() : null;
            if (contextTokens == null || contextTokens < threshold * contextWindow) {
                return;
            }
            log.info("[AutoCompress] conversation {} context {}/{} >= {}, compressing",
                    conversationId, contextTokens, contextWindow, threshold);
            compressService.compress(userId, conversationId, null, locale);
        } catch (Exception e) {
            log.warn("[AutoCompress] skipped for conversation {}: {}", conversationId, e.getMessage());
        }
    }
}
