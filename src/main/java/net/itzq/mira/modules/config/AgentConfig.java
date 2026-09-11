package net.itzq.mira.modules.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局统一配置（可序列化，支持导入/导出）
 *
 * @author tangzq
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    /** 技能目录默认路径 */
    public static final String DEFAULT_SKILLS_DIR = "data-skills/skills";

    public static final String DEFAULT_WORKSPCE_DIR = System.getProperty("user.dir");

    public static final String DEFAULT_SANDBOX_USER =  System.getenv("SANDBOX_USER");

    /** 默认对话模型别名 */
    private String defaultModel;

    /** 默认 Embedding 别名 */
    private String defaultEmbedding;

    /** 技能目录路径 */
    @Builder.Default
    private String skillsDir = DEFAULT_SKILLS_DIR;

    /** 工作空间目录路径 */
    @Builder.Default
    private String bashWorkspceDir = DEFAULT_WORKSPCE_DIR;

    @Builder.Default
    private String bashSandBoxUser = DEFAULT_SANDBOX_USER;


}
