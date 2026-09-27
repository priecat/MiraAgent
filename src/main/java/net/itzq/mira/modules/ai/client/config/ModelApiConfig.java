package net.itzq.mira.modules.ai.client.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.itzq.mira.modules.ai.client.handle.HttpStreamEventInterface;

import java.util.Map;

/**
 *  ModelRegistration
 *
 *  @author tangzq
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelApiConfig {

    private String alias;

    private String providerName;

    private String apiModelName;

    private String apiHost;

    private String apiKey;

    private String apiEndpoint;

    private Map<String, String> apiHeaders;

    private String sseEventHandler; // 事件处理器全类名，须先在StreamEventHandlerManage注册

    /** 是否支持图片输入（视觉）：1 支持 / 0 不支持 */
    @Builder.Default
    private Integer supportImage = 1;

    // ==================== 模型高级参数 ====================
    // 原落宿主 ModelConfig 的 V3 高级列，收归内核声明；null/空 = 不参与请求（与节点参数同语义）。
    // 装配逻辑见 AgentRequestParamsFactory（内核按模型构造 ApiRequestParams）。

    /** 展示名（宿主 UI 用，可空；为空回退 alias） */
    private String displayName;

    /** 模型类型（chat / embedding / ...；可空） */
    private String modelType;

    /** 上下文窗口（输入/输出，token；可空） */
    private Long contextWindowInput;

    private Long contextWindowOutput;

    /** 工具调用最大轮数（可空） */
    private Integer toolCallRounds;

    /** 思考模式：开/关参数（JSON 对象字符串，逐键展开进请求体；可空） */
    private String thinkingEnableParam;

    private String thinkingDisableParam;

    /** 采样参数（null = 不传） */
    private Double samplingTemperature;

    private Double samplingTopP;

    private Integer samplingTopK;

    /** 自定义参数（JSON 对象字符串，逐键透传请求体；可空） */
    private String customParams;

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
