package net.itzq.mira.modules.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具提供者声明（编排运行时协议 · declaration.toolProviders）。
 *
 * <p>把"工具注册表状态"纳入声明：导入端据此做存在性校验（缺注册 → 校验报告），
 * 导出端由内核从注册表快照生成。{@code source} 标注归属（{@code core} / {@code app:&lt;hostId&gt;}），
 * 供跨运行时判断哪些工具需要宿主补齐。
 */
@Data
public class ToolProviderDecl {

    /** builtin / http / mcp / skill */
    private String type;

    /** 归属：core / app:&lt;hostId&gt; / plugin:&lt;id&gt; */
    private String source;

    /** builtin：工具名清单 */
    private List<String> names = new ArrayList<>();

    /** http：工具键清单（配置在宿主 tool_http 表，凭据按策略导出） */
    private List<String> toolKeys = new ArrayList<>();

    /** mcp：服务器名清单 */
    private List<String> servers = new ArrayList<>();
}
