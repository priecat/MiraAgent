package net.itzq.mira.modules.ai.client.config;

import lombok.Builder;
import lombok.Data;
import net.itzq.mira.modules.ai.client.handle.HttpStreamEventInterface;

import java.util.Map;

/**
 *  ModelRegistration
 *
 *  @author tangzq
 */
@Data
@Builder
public class ModelApiConfig {

    private final String alias;

    private final String providerName;

    private final String apiModelName;

    private final String apiHost;

    private final String apiKey;

    private final String apiEndpoint;

    private final Map<String, String> apiHeaders;

    private final String sseEventHandler; // 事件处理器全类名，须先在StreamEventHandlerManage注册

    /** 是否支持图片输入（视觉）：1 支持 / 0 不支持 */
    @Builder.Default
    private final Integer supportImage = 1;

    // ==================== 声明域收归（P1）：模型高级参数 ====================
    // 原落宿主 ModelConfig 的 V3 高级列，收归内核声明；null/空 = 不参与请求（与节点参数同语义）。
    // 装配逻辑见 AgentRequestParamsFactory（内核按模型构造 ApiRequestParams）。

    /** 展示名（宿主 UI 用，可空；为空回退 alias） */
    private final String displayName;

    /** 模型类型（chat / embedding / ...；可空） */
    private final String modelType;

    /** 上下文窗口（输入/输出，token；可空） */
    private final Long contextWindowInput;

    private final Long contextWindowOutput;

    /** 工具调用最大轮数（可空） */
    private final Integer toolCallRounds;

    /** 思考模式：开/关参数（JSON 对象字符串，逐键展开进请求体；可空） */
    private final String thinkingEnableParam;

    private final String thinkingDisableParam;

    /** 采样参数（null = 不传） */
    private final Double samplingTemperature;

    private final Double samplingTopP;

    private final Integer samplingTopK;

    /** 自定义参数（JSON 对象字符串，逐键透传请求体；可空） */
    private final String customParams;

    /**
     * 模型是否支持图片输入（视觉多模态）。
     *
     * <p>注意 null 语义：JSON 反序列化／声明导入得到的配置可能没带 supportImage，
     * 此时按字段默认（1 = 支持）处理——此前 `supportImage == 1` 会对 null 拆箱抛 NPE，
     * 使"导出含 JSON 导入模型的声明"直接失败（fastjson 序列化时调用本 getter）。
     */
    public boolean isImageSupported() {
        return supportImage == null || supportImage == 1;
    }

}
