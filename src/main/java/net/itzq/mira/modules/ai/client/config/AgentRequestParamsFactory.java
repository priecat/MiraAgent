package net.itzq.mira.modules.ai.client.config;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.handle.ApiRequestParams;

/**
 * 按模型声明装配请求参数（声明域收归 · P1）。
 *
 * <p>原来这套逻辑在宿主（mira-code KernelChatEngine.buildRequestParams，读宿主 ModelConfig 的
 * V3 高级列）；现在模型高级参数已收归内核 {@link ModelApiConfig}，装配随之下沉内核——
 * 任何运行时只要装载了声明，就能得到一致的请求参数，无需宿主逐请求透传。
 *
 * <h3>装配顺序（后写覆盖前写，自定义参数优先级最高）</h3>
 * <ol>
 *   <li>思考模式：按 enableThinking 取 {@code thinkingEnable/DisableParam} JSON 逐键展开；
 *       无配置且要求关闭思考 → 兜底 {@code enable_thinking=false}</li>
 *   <li>采样：temperature / top_p 走具名 setter；top_k 走自定义透传</li>
 *   <li>自定义参数：{@code customParams} JSON 逐键透传（保留类型：字符串/布尔/嵌套对象）</li>
 * </ol>
 */
@Slf4j
public final class AgentRequestParamsFactory {

    private AgentRequestParamsFactory() {
    }

    /**
     * 无模型声明（未命中）时的最小参数：只处理"要求关闭思考"的兜底。
     */
    public static ApiRequestParams minimal(boolean enableThinking) {
        ApiRequestParams req = new ApiRequestParams();
        if (!enableThinking) {
            req.addCustomParam("enable_thinking", false);
        }
        return req;
    }

    /**
     * 按模型声明装配。
     *
     * @param model          模型声明（可 null → 退化为 {@link #minimal(boolean)}）
     * @param enableThinking 本次是否启用思考（调用级；null 视为 false）
     */
    public static ApiRequestParams build(ModelApiConfig model, Boolean enableThinking) {
        boolean thinking = enableThinking != null && enableThinking;
        if (model == null) {
            return minimal(thinking);
        }
        ApiRequestParams req = new ApiRequestParams();

        // 1) 思考模式
        String thinkingJson = thinking ? model.getThinkingEnableParam() : model.getThinkingDisableParam();
        boolean applied = applyJsonParams(req, thinkingJson);
        if (!applied && !thinking) {
            req.addCustomParam("enable_thinking", false);
        }

        // 2) 采样参数
        if (model.getSamplingTemperature() != null) {
            req.setTemperature(model.getSamplingTemperature().floatValue());
        }
        if (model.getSamplingTopP() != null) {
            req.setTopP(model.getSamplingTopP().floatValue());
        }
        if (model.getSamplingTopK() != null) {
            req.addCustomParam("top_k", model.getSamplingTopK());
        }

        // 3) 自定义参数：优先级最高
        applyJsonParams(req, model.getCustomParams());
        return req;
    }

    /** 把 JSON 对象字符串逐键 addCustomParam；空/坏数据返回 false（调用方决定是否兜底） */
    public static boolean applyJsonParams(ApiRequestParams req, String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject obj = JSONObject.parseObject(json);
            if (obj == null || obj.isEmpty()) {
                return false;
            }
            for (String key : obj.keySet()) {
                req.addCustomParam(key, obj.get(key));
            }
            return true;
        } catch (Exception e) {
            log.warn("跳过无法解析的模型参数 JSON: {}", json);
            return false;
        }
    }
}
