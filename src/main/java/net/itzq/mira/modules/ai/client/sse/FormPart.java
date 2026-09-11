package net.itzq.mira.modules.ai.client.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FormPart - multipart/form-data 表单部件
 * <p>
 * {@code value} 非空为普通字段；{@code fileBytes} 非空为文件部分
 * （内存零落盘，经 Hutool {@code BytesResource} 上传）。
 * </p>
 *
 * @author tangzq
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormPart {

    /** 字段名 */
    private String name;

    /** 普通字段值（与 fileBytes 互斥） */
    private String value;

    /** 文件内容（文件部分） */
    private byte[] fileBytes;

    /** 文件名（含扩展名，上游按此识别格式） */
    private String filename;

    /** 文件 MIME 类型（如 audio/mpeg，可选，留空按文件名推断） */
    private String contentType;

    /** 构造普通字段部件 */
    public static FormPart field(String name, String value) {
        return new FormPart(name, value, null, null, null);
    }

    /** 构造文件部件 */
    public static FormPart file(String name, byte[] fileBytes, String filename, String contentType) {
        return new FormPart(name, null, fileBytes, filename, contentType);
    }
}
