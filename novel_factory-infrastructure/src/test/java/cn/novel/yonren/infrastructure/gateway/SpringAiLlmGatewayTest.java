package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.ChatGptGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.LlmRuntimeConfig;
import cn.novel.yonren.domain.novel.service.job.LlmBudgetFuse;
import cn.novel.yonren.domain.novel.service.job.LlmInteractionEvent;
import cn.novel.yonren.domain.novel.service.job.LlmInteractionTracker;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 网关测试：模型客户端缓存键必须区分 buildChatModel 固化的全部参数——
 * temperature 曾不在键内，同 model+maxTokens 不同温度的场景复用同一客户端，
 * 先构建者的温度对后者生效（summary 0.2 vs chapter-content 0.5 碰撞）
 */
class SpringAiLlmGatewayTest {

    @Test
    void complete_routesChatGptModeToResponsesGateway() {
        LlmRuntimeConfig runtime = mock(LlmRuntimeConfig.class);
        LlmBudgetFuse budget = mock(LlmBudgetFuse.class);
        ChatGptGateway chatGpt = mock(ChatGptGateway.class);
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(api());
        StoryVO.Module.ChatModel model = chatModel("gpt-6.1-sol", 16384L, 0.2);
        module.setChatModel(model);
        LlmCall call = LlmCall.builder().systemPrompt("system").userPrompt("user").build();
        when(runtime.usesChatGptAuth()).thenReturn(true);
        when(runtime.applyApi(module)).thenReturn(module);
        when(runtime.applyModel(null, model)).thenReturn(model);
        when(chatGpt.complete("gpt-6.1-sol", "system", "user"))
                .thenReturn(new ChatGptGateway.Completion("{\"ok\":true}", 10, 5, 15));
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(1, 20, 600, runtime, budget, chatGpt,
                new LlmInteractionTracker(), 1, 0, 0, mock(ClientHttpRequestFactory.class));

        assertEquals("{\"ok\":true}", gateway.complete(module, call));
        verify(chatGpt).complete("gpt-6.1-sol", "system", "user");
    }

    @Test
    void complete_retriesOverloadAndPublishesLiveTimeline() {
        LlmRuntimeConfig runtime = mock(LlmRuntimeConfig.class);
        LlmBudgetFuse budget = mock(LlmBudgetFuse.class);
        ChatGptGateway chatGpt = mock(ChatGptGateway.class);
        LlmInteractionTracker tracker = new LlmInteractionTracker();
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(api());
        StoryVO.Module.ChatModel model = chatModel("gpt-6.1-sol", 16384L, 0.2);
        module.setChatModel(model);
        LlmCall call = LlmCall.builder()
                .systemPrompt("system-secret-free")
                .userPrompt("plan chapter")
                .label("chapter-plan")
                .scene(ModelScene.CHAPTER_PLAN)
                .build();
        when(runtime.usesChatGptAuth()).thenReturn(true);
        when(runtime.applyApi(module)).thenReturn(module);
        when(runtime.applyModel(ModelScene.CHAPTER_PLAN, model)).thenReturn(model);
        when(chatGpt.complete("gpt-6.1-sol", "system-secret-free", "plan chapter"))
                .thenThrow(new IllegalStateException("server_is_overloaded"))
                .thenThrow(new IllegalStateException("server_is_overloaded"))
                .thenReturn(new ChatGptGateway.Completion("{\"ok\":true}", 10, 5, 15));
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(1, 20, 600, runtime, budget, chatGpt,
                tracker, 3, 0, 0, mock(ClientHttpRequestFactory.class));

        MDC.put("trace-id", "job-live");
        try {
            assertEquals("{\"ok\":true}", gateway.complete(module, call));
        } finally {
            MDC.remove("trace-id");
        }

        verify(chatGpt, times(3)).complete("gpt-6.1-sol", "system-secret-free", "plan chapter");
        List<LlmInteractionEvent> events = tracker.snapshot("job-live");
        assertEquals(List.of("REQUESTED", "RETRY_WAIT", "REQUESTED", "RETRY_WAIT", "REQUESTED", "SUCCEEDED"),
                events.stream().map(LlmInteractionEvent::type).toList());
        assertEquals("CHAPTER_PLAN", events.get(0).scene());
        assertEquals(3, events.get(4).attempt());
    }

    @Test
    void finalOverloadOpensSharedCooldownForFollowingCalls() {
        LlmRuntimeConfig runtime = mock(LlmRuntimeConfig.class);
        LlmBudgetFuse budget = mock(LlmBudgetFuse.class);
        ChatGptGateway chatGpt = mock(ChatGptGateway.class);
        LlmInteractionTracker tracker = new LlmInteractionTracker();
        StoryVO.Module module = new StoryVO.Module();
        module.setAiApi(api());
        StoryVO.Module.ChatModel model = chatModel("gpt-6.1-sol", 16384L, 0.2);
        module.setChatModel(model);
        LlmCall call = LlmCall.builder().userPrompt("plan chapter").scene(ModelScene.CHAPTER_PLAN).build();
        when(runtime.usesChatGptAuth()).thenReturn(true);
        when(runtime.applyApi(module)).thenReturn(module);
        when(runtime.applyModel(ModelScene.CHAPTER_PLAN, model)).thenReturn(model);
        when(chatGpt.complete("gpt-6.1-sol", null, "plan chapter"))
                .thenThrow(new IllegalStateException("server_is_overloaded"))
                .thenReturn(new ChatGptGateway.Completion("ok", 1, 1, 2));
        SpringAiLlmGateway gateway = new SpringAiLlmGateway(1, 20, 600, runtime, budget, chatGpt,
                tracker, 1, 500, 500, mock(ClientHttpRequestFactory.class)) {
            @Override
            void sleepBeforeChatGptRetry(long delayMs) {
                // Keep the regression test fast; the cooldown event itself is the observable contract.
            }
        };

        MDC.put("trace-id", "job-overloaded");
        try {
            assertThrows(IllegalStateException.class, () -> gateway.complete(module, call));
        } finally {
            MDC.remove("trace-id");
        }
        MDC.put("trace-id", "job-following");
        try {
            assertEquals("ok", gateway.complete(module, call));
        } finally {
            MDC.remove("trace-id");
        }

        assertEquals("RETRY_WAIT", tracker.snapshot("job-following").get(0).type());
        assertTrue(tracker.snapshot("job-following").get(0).message().contains("共享冷却"));
    }

    @Test
    void cacheKeyDistinguishesAllBuildParameters() {
        StoryVO.Module.AiApi api = api();

        StoryVO.Module.ChatModel summary = chatModel("qwen3.8-flash", 16384L, 0.2);
        StoryVO.Module.ChatModel content = chatModel("qwen3.8-flash", 16384L, 0.5);
        StoryVO.Module.ChatModel sameAsSummary = chatModel("qwen3.8-flash", 16384L, 0.2);

        // 同 model+maxTokens 不同温度：必须视为不同客户端
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, content));
        // 完全一致的参数：复用同一缓存客户端
        assertEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, sameAsSummary));
        // 模型名/输出上限不同：区分
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, chatModel("deepseek-v4-pro", 16384L, 0.2)));
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(api, chatModel("qwen3.8-flash", 2048L, 0.2)));

        // ZDR 会改变请求头行为，必须区分缓存客户端，避免开关切换后复用旧拦截器
        StoryVO.Module.AiApi zdrApi = api();
        zdrApi.setZeroDataRetention(true);
        assertNotEquals(SpringAiLlmGateway.chatModelCacheKey(api, summary),
                SpringAiLlmGateway.chatModelCacheKey(zdrApi, summary));
    }

    private StoryVO.Module.AiApi api() {
        StoryVO.Module.AiApi api = new StoryVO.Module.AiApi();
        api.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        api.setApiKey("sk-test");
        return api;
    }

    private StoryVO.Module.ChatModel chatModel(String model, Long maxTokens, Double temperature) {
        StoryVO.Module.ChatModel chat = new StoryVO.Module.ChatModel();
        chat.setModel(model);
        chat.setMaxTokens(maxTokens);
        chat.setTemperature(temperature);
        chat.setEnableThinking(false);
        return chat;
    }
}
