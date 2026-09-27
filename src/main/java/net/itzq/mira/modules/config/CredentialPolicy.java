package net.itzq.mira.modules.config;

/**
 * 凭据导出策略（编排运行时协议 · declaration 段）。
 *
 * <p>{@code INLINE}：密钥原文内联——本机自用快照；
 * {@code REFERENCE}：输出 {@code ${ENV_NAME}} 引用——分发用，导入端未解析时进校验报告 warning。
 */
public enum CredentialPolicy {

    /** 密钥原文内联（本机自用） */
    INLINE,

    /** 密钥以 ${ENV_NAME} 引用（分发用） */
    REFERENCE;

    public static CredentialPolicy of(String name) {
        if (name == null || name.trim().isEmpty()) {
            return INLINE;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return INLINE;
        }
    }

    /** 按策略转换密钥字段：REFERENCE 时输出 ${ENV:原值摘要} 占位，其余原样 */
    public String mask(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return secret;
        }
        if (this == INLINE) {
            return secret;
        }
        return "${ENV:" + (secret.length() > 8 ? secret.substring(secret.length() - 8) : secret) + "}";
    }
}
