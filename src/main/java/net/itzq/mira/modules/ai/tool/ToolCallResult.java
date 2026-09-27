package net.itzq.mira.modules.ai.tool;

/**
 * 工具调用结果的<b>统一协议</b>。
 *
 * <h3>为什么需要它</h3>
 * 内核工具统一返回 {@code String}，调用方（前端工具卡片的红/绿状态点、结果渲染器）
 * 过去只能靠"内容里是否出现『失败 / 错误 / 不存在』"来猜结果 —— 误判率很高：
 * <ul>
 *   <li>{@code 工具调用失败，错误信息：...} 会被当成失败，但工具正常返回一句
 *       "查询不到相关记录，请换关键词" 也会被误判成失败；</li>
 *   <li>反过来的情况更糟：工具内部判定失败（如"编辑失败：匹配不到内容"）在内核看来
 *       是一次<b>正常返回</b>，无法与成功区分。</li>
 * </ul>
 *
 * <p>本类给出一个<b>显式</b>约定：工具方法返回时用 {@link #success(String)} /
 * {@link #error(String)} 包一层，结果字符串的<b>第一行</b>就是固定标记，
 * 判定方只读这一行即可，不再猜关键词。
 *
 * <h3>格式</h3>
 * <pre>
 * [mira:ok]
 * 真正的结果内容（可多行，原样保留）
 * </pre>
 * 标记独立成行，好处是：判定方可以{@link #unwrap(String)}剥掉标记拿到干净内容；
 * 模型在上下文里看到结果时也能一眼分辨这次调用是成功还是失败。
 *
 * <h3>降级规则（重要，协议是"可选"的）</h3>
 * 未按本协议返回的普通字符串（没有标记）时，判定方应降级为：
 * <b>只把内核强制产生的异常文案（{@link #KERNEL_ERROR_PREFIX}）视为失败，其余一律视为成功</b>。
 * 也就是说：
 * <ul>
 *   <li>抛异常 → 内核（{@code FCUtil}）会包成 {@code [mira:err]}，必然被识别；</li>
 *   <li>业务失败 → 由工具自己用 {@link #error(String)} 声明；</li>
 *   <li>没声明又没抛异常 → 当成功处理（老工具/第三方工具的安全默认）。</li>
 * </ul>
 *
 * <h3>给改造者看的约定 {@code (internal)}</h3>
 * <ul>
 *   <li>标记必须是返回值的<b>第一个字符起</b>，不要在前面加空行或空格；</li>
 *   <li>内容里不需要再自己加"成功/失败"前缀，标记已经表达了；</li>
 *   <li>返回值已经带标记时，<b>不要嵌套调用</b>（会叠两层标记）。</li>
 * </ul>
 */
public final class ToolCallResult {

    /** 成功标记。独立成行，位于返回值最前面 */
    public static final String OK_MARK = "[mira:ok]";

    /** 失败标记。工具内部判定失败时使用；内核捕获异常时也用它 */
    public static final String ERR_MARK = "[mira:err]";

    /**
     * 内核在工具抛异常时统一使用的文案前缀。
     * 判定方在"没有标记"的降级路径上据此识别失败。
     */
    public static final String KERNEL_ERROR_PREFIX = "工具调用失败，错误信息：";

    private ToolCallResult() {
    }

    /**
     * 成功结果。
     *
     * @param content 结果正文（可为多行；{@code null} 视为空串）
     * @return {@code [mira:ok]} + 换行 + 正文
     */
    public static String success(String content) {
        return OK_MARK + "\n" + (content == null ? "" : content);
    }

    /**
     * 失败结果。
     *
     * @param content 失败原因（可为多行；{@code null} 视为空串）
     * @return {@code [mira:err]} + 换行 + 正文
     */
    public static String error(String content) {
        return ERR_MARK + "\n" + (content == null ? "" : content);
    }

    /** 按布尔判定选择标记，适合"计算结果 + 一个 isOk 标志"的写法 */
    public static String of(boolean success, String content) {
        return success ? success(content) : error(content);
    }

    /**
     * 内容<b>还没有</b>标记时按成功包装；已有标记则原样返回。
     *
     * <p>用途：一个工具的调用链常是「工具方法 → 若干私有 helper」。helper 内部也可能
     * 出现失败分支（例如"offset 超出范围"），那种分支自己标 {@link #error(String)}，
     * 而工具方法对外返回时用本方法兜底。这样：
     * <ul>
     *   <li>不会把已经标好的失败覆盖成成功；</li>
     *   <li>也不会产生 {@code [mira:ok][mira:err]} 这种叠两层的畸形结果。</li>
     * </ul>
     */
    public static String successUnlessMarked(String content) {
        return isMarked(content) ? content : success(content);
    }

    /** 是否带本协议标记 */
    public static boolean isMarked(String text) {
        return markOf(text) != null;
    }

    /** 是否标记为成功（无标记返回 {@code false}，由调用方走降级逻辑） */
    public static boolean isSuccess(String text) {
        return OK_MARK.equals(markOf(text));
    }

    /** 读取标记；不是本协议的结果返回 {@code null} */
    public static String markOf(String text) {
        if (text == null) {
            return null;
        }
        if (text.startsWith(OK_MARK)) {
            return OK_MARK;
        }
        if (text.startsWith(ERR_MARK)) {
            return ERR_MARK;
        }
        return null;
    }

    /**
     * 剥掉标记行，返回干净正文；没有标记时原样返回。
     * 供需要"只看内容"的调用方使用（如落库、二次加工）。
     */
    public static String unwrap(String text) {
        String mark = markOf(text);
        if (mark == null) {
            return text;
        }
        String rest = text.substring(mark.length());
        if (rest.startsWith("\r\n")) {
            return rest.substring(2);
        }
        if (rest.startsWith("\n")) {
            return rest.substring(1);
        }
        return rest;
    }
}
