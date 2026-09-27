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

    /** 模型是否支持图片输入（视觉多模态） */
    public boolean isImageSupported() {
        return supportImage == 1;
    }

}
