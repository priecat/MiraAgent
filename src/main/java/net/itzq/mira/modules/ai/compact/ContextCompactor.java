package net.itzq.mira.modules.ai.compact;

import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.openai.chat.entity.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器（编排运行时协议 · 会话压缩策略，纯内存、消息形态无关）。
 *
 * <p>设计目标：把"怎么压缩"从"压缩结果怎么落库"里彻底剥离——
 * 输入一份上下文消息列表，输出压缩后的列表与过程元数据；
 * 聊天侧（fork 新会话 + DB 原子重写）与节点侧（内存折叠注入）只是两种不同的
 * <b>结果消费者</b>，共享同一份边界与摘要策略。后续调整压缩策略只改本类。
 *
 * <h3>策略</h3>
 * <ul>
 *   <li><b>保护区边界</b>：从尾部按 user 轮回退，轮数 + token 双预算约束；
 *       最后一轮始终保护。与 {@code SessionCompactService.protectedBoundary} 同语义
 *       （该实现已迁移至此，宿主不再各留一份）。</li>
 *   <li><b>摘要生成</b>：{@link Summarizer} 非空时先走 LLM（返回 null/空/抛异常 → 降级）；
 *       降级与"未提供摘要器"时使用内建<b>机械摘要</b>（逐轮 user/assistant 截断拼接）。</li>
 *   <li><b>摘要并入</b>：以 {@code system-reminder(data-role=compact-summary)} 块 +
 *       {@code user_query} 包裹原提问，<b>并入保护区首轮 user 行</b>——
 *       不产生独立摘要行（连续两条 user 违反对话规范），多模态首轮降为纯文本
 *       （与宿主聊天侧既有行为一致）。</li>
 * </ul>
 *
 * <p>宿主的 LLM 摘要器（经 {@code MiraAgent} 同步调用，fail-soft 降级）由宿主提供——
 * 内核不知道宿主引擎是否就绪，这正是端口与策略分离的意义。
 */
public final class ContextCompactor {

    private ContextCompactor() {
    }

    /** 摘要生成器：输入被折叠的旧消息，返回摘要正文；返回 null/空 = 调用方降级机械摘要 */
    public interface Summarizer {
        String summarize(List<ChatMessage> archived);
    }

    /** token 估算器：null 时使用内建字符级估算（CJK×1 + 其他÷4 + 常数） */
    public interface TokenEstimator {
        long estimate(ChatMessage m);
    }

    /** 压缩结果：messages 已可直接作为"压缩后的上下文"使用；元数据供落库方做锚点与展示 */
    public static final class Outcome {
        private final List<ChatMessage> messages;
        private final int boundaryIndex;
        private final int protectedTurns;
        private final String summaryType;
        private final String summaryBody;

        Outcome(List<ChatMessage> messages, int boundaryIndex, int protectedTurns,
                String summaryType, String summaryBody) {
            this.messages = messages;
            this.boundaryIndex = boundaryIndex;
            this.protectedTurns = protectedTurns;
            this.summaryType = summaryType;
            this.summaryBody = summaryBody;
        }

        /** 压缩后的完整消息列表（未压缩时 = 原列表原样） */
        public List<ChatMessage> messages() {
            return messages;
        }

        /** 保护区首行在【原列表】的下标（落库方做锚点/重写用；未压缩时无意义） */
        public int boundaryIndex() {
            return boundaryIndex;
        }

        /** 实际保护的轮数（最后一轮始终保护，可能小于预算） */
        public int protectedTurns() {
            return protectedTurns;
        }

        /** 摘要方式：none（未压缩）/ llm / mechanical */
        public String summaryType() {
            return summaryType;
        }

        /** 摘要正文（none 时为 null；不含 reminder 包装，包装随 messages 并入） */
        public String summaryBody() {
            return summaryBody;
        }

        /** 是否真的发生了压缩（false = 保护区覆盖全部内容，原样返回） */
        public boolean compacted() {
            return boundaryIndex > 0;
        }

        /** 被折叠的旧消息条数 */
        public int archivedCount() {
            return boundaryIndex;
        }
    }

    /**
     * 执行压缩。
     *
     * @param messages        完整上下文（时间正序；含历史的工具调用行——工具行按所在轮整体保护）
     * @param protectedTurns  保护区轮数预算
     * @param protectedTokens 保护区 token 预算
     * @param summarizer      LLM 摘要器（null = 直接机械摘要）
     * @param estimator       token 估算器（null = 内建估算）
     * @throws IllegalStateException 消息里没有任何 user 行（无法界定"轮"）
     */
    public static Outcome compact(List<ChatMessage> messages, int protectedTurns, int protectedTokens,
                                  Summarizer summarizer, TokenEstimator estimator) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("上下文为空，无需压缩");
        }
        TokenEstimator est = estimator != null ? estimator : ContextCompactor::defaultEstimate;

        // ---- 1) 保护区边界：从尾部按 user 轮回退，双预算约束，最后一轮始终保护 ----
        List<Integer> userIdx = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (isUser(messages.get(i))) {
                userIdx.add(i);
            }
        }
        if (userIdx.isEmpty()) {
            throw new IllegalStateException("上下文中没有任何用户提问，无法计算保护区");
        }
        int turns = 0;
        long tokens = 0;
        for (int i = userIdx.size() - 1; i >= 0; i--) {
            int end = i + 1 < userIdx.size() ? userIdx.get(i + 1) : messages.size();
            long turnTokens = 0;
            for (int j = userIdx.get(i); j < end; j++) {
                turnTokens += est.estimate(messages.get(j));
            }
            if (turns > 0 && (turns + 1 > protectedTurns || tokens + turnTokens > protectedTokens)) {
                break; // 最后一轮始终保护；其余轮受轮数/token 双预算约束
            }
            turns++;
            tokens += turnTokens;
        }
        int boundaryIndex = userIdx.get(userIdx.size() - turns);
        if (boundaryIndex == 0) {
            // 保护区覆盖全部内容：没有可折叠的旧消息
            return new Outcome(new ArrayList<>(messages), 0, turns, "none", null);
        }

        List<ChatMessage> archived = new ArrayList<>(messages.subList(0, boundaryIndex));
        List<ChatMessage> protectedMsgs = new ArrayList<>(messages.subList(boundaryIndex, messages.size()));

        // 摘要输入防嵌套：user 行剥掉上次压缩的 reminder + user_query 信封再进摘要
        // （旧块若被逐字拼进新摘要正文，新 reminder 块里就会嵌着旧 reminder 块）
        List<ChatMessage> archivedForSummary = new ArrayList<>(archived.size());
        for (ChatMessage m : archived) {
            if (isUser(m)) {
                archivedForSummary.add(ChatMessage.withUser(stripCompactEnvelope(chatText(m))));
            } else {
                archivedForSummary.add(m);
            }
        }

        // ---- 2) 摘要生成：LLM 优先，机械降级 ----
        String summaryType = "mechanical";
        String summaryBody = null;
        if (summarizer != null) {
            try {
                String llm = summarizer.summarize(archivedForSummary);
                if (llm != null && llm.trim().length() >= 10) {
                    summaryType = "llm";
                    summaryBody = llm.trim();
                }
            } catch (Exception e) {
                // 摘要器失败不阻断压缩——降级机械
            }
        }
        if (summaryBody == null) {
            summaryBody = mechanicalSummary(archivedForSummary);
        }

        // ---- 3) 摘要并入保护区首轮 user 行（不产生独立摘要行） ----
        List<ChatMessage> out = new ArrayList<>(messages.size());
        String wrapped = wrapSummary(summaryBody, archived.size(), turns, summaryType);
        // 二次压缩防嵌套：保护区首轮可能已含上一次的 reminder + user_query 信封，
        // 剥掉旧信封取原始提问，再包进新摘要（与宿主聊天侧 stripCompactEnvelope 同语义）
        String firstQuestion = stripCompactEnvelope(chatText(protectedMsgs.get(0)));
        for (int i = 0; i < protectedMsgs.size(); i++) {
            ChatMessage m = protectedMsgs.get(i);
            if (i == 0 && isUser(m)) {
                out.add(ChatMessage.withUser(mergedUserContent(wrapped, firstQuestion)));
            } else {
                out.add(m);
            }
        }
        return new Outcome(out, boundaryIndex, turns, summaryType, summaryBody);
    }

    // ---------------------------------------------------------------- 内部

    private static boolean isUser(ChatMessage m) {
        return m != null && "user".equalsIgnoreCase(m.getRole());
    }

    /** 消息文本：纯文本取 text；多模态拼接全部 text 分段（图片不进摘要/合并文本） */
    private static String chatText(ChatMessage m) {
        if (m == null || m.getContent() == null) {
            return "";
        }
        Content c = m.getContent();
        if (c.getText() != null) {
            return c.getText();
        }
        StringBuilder sb = new StringBuilder();
        if (c.getMultiModals() != null) {
            for (Content.MultiModal part : c.getMultiModals()) {
                if (part != null && part.getText() != null && !part.getText().isEmpty()) {
                    sb.append(part.getText()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 机械摘要（内建降级）：逐轮 user 问题 + assistant 回答截断，不依赖任何模型 */
    static String mechanicalSummary(List<ChatMessage> archived) {
        StringBuilder sb = new StringBuilder();
        int turn = 0;
        for (ChatMessage m : archived) {
            String text = chatText(m);
            if (isUser(m)) {
                turn++;
                sb.append("\n第 ").append(turn).append(" 轮\n");
                sb.append("- 用户：").append(truncate(text, 200)).append('\n');
            } else if ("assistant".equalsIgnoreCase(m.getRole())) {
                if (!text.isEmpty()) {
                    sb.append("- 助手：").append(truncate(text, 300)).append('\n');
                }
            }
        }
        if (turn == 0) {
            return "（无可摘要的对话内容）";
        }
        return "共压缩 " + turn + " 轮对话。" + sb;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /**
     * 摘要的 reminder 块（不含提问）。完整 user 消息 = 本块 + 空行 + user_query 提问。
     * 与宿主聊天侧既有格式逐字符一致（前端原样显示、recall_history 提示随块下发）。
     */
    static String wrapSummary(String summary, int hiddenCount, int protectedTurns, String summaryType) {
        return "<system-reminder data-role=\"compact-summary\">\n"
                + "  之前的对话历史已压缩为以下摘要（归档 " + hiddenCount + " 条消息，"
                + "最近 " + protectedTurns + " 轮保留原文；摘要方式：" + summaryType + "）。\n"
                + "  摘要信息不足以回答时，可调用 recall_history 工具按关键词检索归档原文。\n\n"
                + "  <summary>\n" + summary + "\n  </summary>\n"
                + "</system-reminder>";
    }

    /** 完整的合并 user 文本：system-reminder 摘要块 + 空行 + user_query 包裹的原提问 */
    static String mergedUserContent(String wrapped, String question) {
        return wrapped + "\n\n<user_query>\n" + (question == null ? "" : question) + "\n</user_query>";
    }

    /**
     * 剥离上次压缩的合并信封，取 {@code <user_query>} 内的原始提问。
     * 二次压缩时保护区首轮已是"reminder + user_query"合并文本，
     * 不剥离就会形成 reminder 套 reminder 的嵌套。循环剥离兼容历史脏数据。
     */
    static String stripCompactEnvelope(String content) {
        if (content == null) {
            return null;
        }
        String s = content;
        for (int guard = 0; guard < 5; guard++) {
            int start = s.indexOf("<user_query>");
            int end = s.lastIndexOf("</user_query>");
            if (start < 0 || end <= start) {
                break;
            }
            s = s.substring(start + "<user_query>".length(), end);
        }
        return s;
    }

    /** 内建 token 估算：CJK×1 + 其他÷4 + 常数开销（与宿主节点侧旧实现同公式） */
    private static long defaultEstimate(ChatMessage m) {
        String text = chatText(m);
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isIdeographic(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + other / 4 + 8;
    }
}
