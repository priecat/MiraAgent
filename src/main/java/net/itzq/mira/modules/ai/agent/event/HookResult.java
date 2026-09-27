package net.itzq.mira.modules.ai.agent.event;

/**
 * {@link EventHook#onBeforeToolCall} 的决策结果：应用层对单个工具调用的处置指令。
 *
 * <p>三种语义（内核据此分流，不感知任何具体工具）：
 * <ul>
 *   <li><b>{@code null}</b>（未返回）：放行，工具本体照常执行；</li>
 *   <li><b>{@link #respond(String)}</b>：拦截并"代答"——工具本体不执行，
 *       hook 提供的文本直接作为工具结果（CallToolEnd 正常发出、结果正常入历史）；</li>
 *   <li><b>{@link #suspend()}</b>：拦截且<b>结果未定</b>——内核不产生任何 tool result，
 *       history 停在 {@code assistant.tool_calls}（合法断点），对话循环立即中断，
 *       等待外部把结果经应用层追加进上下文后调 {@code chatStreamResume()} 续行。
 *       典型场景：向用户提问、等待人工审批、等待外部系统回调。</li>
 * </ul>
 *
 * <p>这是内核的通用扩展点：挂起/代答的判定标准（哪个工具、什么条件）完全由
 * 应用层的 hook 实现决定。后续若需要"改写参数"、"重试"、"路由到其他执行器"，
 * 在本对象上增加字段与工厂方法即可，内核只需按 blocked/result 分流。
 */
public class HookResult {

    private final boolean blocked;
    private final String result;

    private HookResult(boolean blocked, String result) {
        this.blocked = blocked;
        this.result = result;
    }

    /** 拦截并代答：工具本体不执行，result 作为工具结果走正常收尾路径 */
    public static HookResult respond(String result) {
        return new HookResult(true, result);
    }

    /** 拦截并挂起：结果未定，对话循环保留断点中断，等待外部恢复 */
    public static HookResult suspend() {
        return new HookResult(true, null);
    }

    /** 是否拦截（true = 不执行工具本体） */
    public boolean isBlocked() {
        return blocked;
    }

    /** 拦截时的结果文本；null 表示挂起（结果未定） */
    public String getResult() {
        return result;
    }
}
