package net.itzq.mira.modules.ai.agent.event;

import net.itzq.mira.modules.ai.agent.event.type.*;

/**
 *  EventListener
 *
 *  @author tangzq
 */
public interface EventHook {

    default void onEvent(GeneralEvent event) {}

    default void onChatInput(ChatInputEvent event) {}

    default void onStepBegin(StepBeginEvent event) {}

    default void onStepEnd(StepEndEvent event) {}

    default void onChatEnd(ChatEndEvent event) {}

    default void onCallToolBegin(CallToolBeginEvent event) {}

    default void onCallToolEnd(CallToolEndEvent event) {}

    default void onError(ErrorEvent event) {}

    /**
     * 工具调用前 hook：内核唯一的工具行为决策点。
     *
     * <p>调用时机：CallToolBegin 事件发出之后、工具本体执行之前（每个工具调用各调一次）。
     * 返回 {@code null} 放行；返回 {@link HookResult#respond(String)} 拦截并代答；
     * 返回 {@link HookResult#suspend()} 拦截并挂起——对话循环保留断点中断，
     * 由应用层在外部条件就绪后（如用户作答）把结果作为 tool result 追加进上下文，
     * 再调 {@code chatStreamResume()} 续行。
     *
     * <p>内核不感知任何具体工具，挂起/代答的判定标准完全由应用层实现定义。
     * hook 抛出异常时按放行处理（记 warn），不影响对话主流程。
     */
    default HookResult onBeforeToolCall(CallToolBeginEvent event) { return null; }

}
