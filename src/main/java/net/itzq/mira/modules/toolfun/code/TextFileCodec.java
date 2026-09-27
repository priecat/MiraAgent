package net.itzq.mira.modules.toolfun.code;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * TextFileCodec - 文本文件的 BOM / 字符集 / 换行风格探测与读写保真工具。
 *
 * 设计目标：Read / Write / Edit 共用同一套编解码规则，
 * 避免“Read 按 UTF-8 读、Edit 按 UTF-16 写”这类跨工具不一致导致的静默损坏。
 *
 * 规则：
 * - BOM 优先：UTF-8(EF BB BF) / UTF-16LE(FF FE) / UTF-16BE(FE FF)
 * - 无 BOM：先严格 UTF-8，失败再回退 GBK（中文环境常见编码）
 * - 严格解码：非法字节直接失败，绝不静默替换成 U+FFFD
 * - 换行风格按样本数量占优判定，写回时原样恢复
 *
 * @author tangzq
 */
public final class TextFileCodec {

    /** 换行风格：LF */
    public static final String EOL_LF = "LF";
    /** 换行风格：CRLF */
    public static final String EOL_CRLF = "CRLF";

    /** UTF-8 BOM 字节 */
    public static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** UTF-16LE BOM 字节 */
    public static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    /** UTF-16BE BOM 字节 */
    public static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};

    /** 二进制判定：不可打印字符占比超过该阈值即视为二进制 */
    private static final double NON_PRINTABLE_RATIO = 0.3;

    private TextFileCodec() {
    }

    /** 解码结果：已去 BOM 的文本 + 原字符集 / BOM / 换行风格 */
    public static final class Decoded {
        /** 已剥离 BOM 的文本 */
        public final String text;
        /** 原文件字符集（写回时必须沿用） */
        public final Charset charset;
        /** 原 BOM 字节，无 BOM 为 null */
        public final byte[] bom;
        /** 原换行风格：LF / CRLF */
        public final String lineEndings;

        Decoded(String text, Charset charset, byte[] bom, String lineEndings) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
            this.lineEndings = lineEndings;
        }
    }

    /** 是否带 UTF-16 的 BOM（此类文件正文本身含 NUL 字节，不能按 NUL 判二进制） */
    public static boolean hasUtf16Bom(byte[] raw) {
        return startsWith(raw, BOM_UTF16LE) || startsWith(raw, BOM_UTF16BE);
    }

    /**
     * 探测 BOM / 字符集并严格解码。
     *
     * @param raw 文件原始字节
     * @return 解码结果；二进制或不受支持的编码返回 null
     */
    public static Decoded decode(byte[] raw) {
        Charset charset;
        byte[] bom;
        int offset;

        if (startsWith(raw, BOM_UTF8)) {
            charset = StandardCharsets.UTF_8;
            bom = BOM_UTF8;
            offset = BOM_UTF8.length;
        } else if (startsWith(raw, BOM_UTF16LE)) {
            charset = StandardCharsets.UTF_16LE;
            bom = BOM_UTF16LE;
            offset = BOM_UTF16LE.length;
        } else if (startsWith(raw, BOM_UTF16BE)) {
            charset = StandardCharsets.UTF_16BE;
            bom = BOM_UTF16BE;
            offset = BOM_UTF16BE.length;
        } else {
            // 无 BOM：先 UTF-8，再 GBK 回退
            String utf8 = decodeStrict(raw, 0, StandardCharsets.UTF_8);
            if (utf8 != null) {
                return new Decoded(utf8, StandardCharsets.UTF_8, null, detectLineEndings(utf8));
            }
            Charset gbk = charsetOrNull("GBK");
            String gbkText = gbk == null ? null : decodeStrict(raw, 0, gbk);
            if (gbkText != null) {
                return new Decoded(gbkText, gbk, null, detectLineEndings(gbkText));
            }
            return null;
        }

        String text = decodeStrict(raw, offset, charset);
        if (text == null) {
            return null;
        }
        return new Decoded(text, charset, bom, detectLineEndings(text));
    }

    /**
     * 按原字符集编码，并在需要时补回原 BOM。
     *
     * @param text    要写入的文本（调用方应已恢复为原换行风格）
     * @param charset 原字符集
     * @param bom     原 BOM，null 表示不写 BOM
     * @return 可直接落盘的字节
     */
    public static byte[] encode(String text, Charset charset, byte[] bom) {
        byte[] body = text.getBytes(charset);
        if (bom == null || bom.length == 0) {
            return body;
        }
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    /** 严格解码：遇到非法字节返回 null（而不是静默变成 U+FFFD） */
    private static String decodeStrict(byte[] bytes, int offset, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** CRLF 折叠为 LF —— 编辑与 diff 的统一内存形态（孤立 \r 保持不动） */
    public static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n");
    }

    /** 按检测到的风格写回：内容需已是纯 LF，才能安全全量替换 */
    public static String restoreLineEndings(String content, String lineEndings) {
        return EOL_CRLF.equals(lineEndings) ? content.replace("\n", "\r\n") : content;
    }

    /** 以样本内的数量占优判定换行风格（默认 LF） */
    public static String detectLineEndings(String text) {
        int sampleSize = Math.min(text.length(), 4096);
        int crlfCount = 0;
        int lfCount = 0;
        for (int i = 0; i < sampleSize; i++) {
            if (text.charAt(i) != '\n') {
                continue;
            }
            if (i > 0 && text.charAt(i - 1) == '\r') {
                crlfCount++;
            } else {
                lfCount++;
            }
        }
        return crlfCount > lfCount ? EOL_CRLF : EOL_LF;
    }

    /**
     * 二进制判定：含 NUL 直接判定为二进制；否则按不可打印字符占比 > 0.3 判定。
     * 注意：UTF-16 文本天然含 NUL，调用方应先排除 {@link #hasUtf16Bom(byte[])}。
     *
     * @param sample 采样字节（建议取前 4096 字节）
     */
    public static boolean isBinary(byte[] sample) {
        if (sample == null || sample.length == 0) {
            return false;
        }
        int nonPrintable = 0;
        for (byte b : sample) {
            int v = b & 0xFF;
            if (v == 0) {
                return true;
            }
            if (v < 9 || (v > 13 && v < 32)) {
                nonPrintable++;
            }
        }
        return (double) nonPrintable / sample.length > NON_PRINTABLE_RATIO;
    }

    /** 取前 maxBytes 个字节作为采样 */
    public static byte[] sample(byte[] raw, int maxBytes) {
        if (raw == null) {
            return new byte[0];
        }
        int len = Math.min(raw.length, maxBytes);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, len);
        return out;
    }
}
