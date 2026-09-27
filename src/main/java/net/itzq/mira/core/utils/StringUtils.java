package net.itzq.mira.core.utils;

/**
 * org.apache.commons.lang3.StringUtils 的内核平替：只覆盖内核实际用到的语义，
 * 行为与 lang3 保持一致（null 安全、空串语义相同），避免引入 commons-lang3 依赖。
 */
public final class StringUtils {

    private StringUtils() {
    }

    // ---------- 判空 ----------

    /**
     * 与 lang3 一致：null、空串、纯空白 → true
     */
    public static boolean isBlank(CharSequence cs) {
        if (cs == null || cs.length() == 0) {
            return true;
        }
        for (int i = 0; i < cs.length(); i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(CharSequence cs) {
        return !isBlank(cs);
    }

    /**
     * 与 lang3 一致：全部非空白 → true
     */
    public static boolean isNoneBlank(CharSequence... css) {
        if (css == null) {
            return true;
        }
        for (CharSequence cs : css) {
            if (isBlank(cs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 与 lang3 一致：null 或空串 → true（纯空白不算空）
     */
    public static boolean isEmpty(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    public static boolean isNotEmpty(CharSequence cs) {
        return !isEmpty(cs);
    }

    // ---------- 比较 ----------

    /**
     * 与 lang3 一致：null 安全；两者都为 null → true
     */
    public static boolean equalsIgnoreCase(CharSequence cs1, CharSequence cs2) {
        if (cs1 == cs2) {
            return true;
        }
        if (cs1 == null || cs2 == null) {
            return false;
        }
        if (cs1.length() != cs2.length()) {
            return false;
        }
        return cs1 instanceof String && cs2 instanceof String
                ? ((String) cs1).equalsIgnoreCase((String) cs2)
                : cs1.toString().equalsIgnoreCase(cs2.toString());
    }

    /**
     * 与 lang3 一致：null 安全；两者都为 null → true，任一为 null → false
     */
    public static boolean startsWithIgnoreCase(CharSequence str, CharSequence prefix) {
        if (str == null || prefix == null) {
            return str == null && prefix == null;
        }
        if (prefix.length() > str.length()) {
            return false;
        }
        return str.toString().regionMatches(true, 0, prefix.toString(), 0, prefix.length());
    }

    /**
     * 与 lang3 一致：null 安全；两者都为 null → true，任一为 null → false
     */
    public static boolean endsWithIgnoreCase(CharSequence str, CharSequence suffix) {
        if (str == null || suffix == null) {
            return str == null && suffix == null;
        }
        if (suffix.length() > str.length()) {
            return false;
        }
        return str.toString().regionMatches(true, str.length() - suffix.length(),
                suffix.toString(), 0, suffix.length());
    }

    /**
     * 与 lang3 一致：null 安全；两者都为 null → true，任一为 null → false
     */
    public static boolean endsWith(CharSequence str, CharSequence suffix) {
        if (str == null || suffix == null) {
            return str == null && suffix == null;
        }
        if (suffix.length() > str.length()) {
            return false;
        }
        return str.toString().regionMatches(false, str.length() - suffix.length(),
                suffix.toString(), 0, suffix.length());
    }

    // ---------- 截取 ----------

    /**
     * 与 lang3 一致：null → null；len < 0 → ""；超长取全串
     */
    public static String left(String str, int len) {
        if (str == null) {
            return null;
        }
        if (len < 0) {
            return "";
        }
        if (str.length() <= len) {
            return str;
        }
        return str.substring(0, len);
    }

    /**
     * 与 lang3 一致：null/空串 → 原样返回；separator 为 null → ""；找不到分隔符 → ""；
     * 找到 → 返回第一个分隔符之后的全部内容
     */
    public static String substringAfter(String str, String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (separator == null) {
            return "";
        }
        int pos = str.indexOf(separator);
        if (pos < 0) {
            return "";
        }
        return str.substring(pos + separator.length());
    }

    /**
     * 与 lang3 一致：str 或 separator 为空 → 原样返回 str；找不到分隔符 → 原样返回 str；
     * 找到 → 返回最后一个分隔符之前的全部内容
     */
    public static String substringBeforeLast(String str, String separator) {
        if (isEmpty(str) || isEmpty(separator)) {
            return str;
        }
        int pos = str.lastIndexOf(separator);
        if (pos < 0) {
            return str;
        }
        return str.substring(0, pos);
    }

    // ---------- 变换 ----------

    /**
     * 与 lang3 一致：null → null；其余走 String.trim()
     */
    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    /**
     * 与 lang3 一致：null → null；首字符转大写（title case）
     */
    public static String capitalize(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        char first = str.charAt(0);
        char upper = Character.toTitleCase(first);
        if (first == upper) {
            return str;
        }
        return new StringBuilder(str.length())
                .append(upper)
                .append(str.substring(1))
                .toString();
    }

    /**
     * 与 lang3 一致：null → null；空串 → 空数组；
     * separatorChars 为 null → 按空白切分；
     * 否则 separatorChars 中每个字符都视为分隔符（相邻分隔符合并，不产生空 token）
     */
    public static String[] split(String str, String separatorChars) {
        if (str == null) {
            return null;
        }
        if (str.isEmpty()) {
            return new String[0];
        }
        java.util.List<String> tokens = new java.util.ArrayList<>();
        int len = str.length();
        int i = 0;
        int start = 0;
        boolean splitOnWhitespace = separatorChars == null;
        while (i < len) {
            char c = str.charAt(i);
            boolean isSep = splitOnWhitespace
                    ? Character.isWhitespace(c)
                    : separatorChars.indexOf(c) >= 0;
            if (isSep) {
                if (i > start) {
                    tokens.add(str.substring(start, i));
                }
                start = ++i;
            } else {
                i++;
            }
        }
        if (start < len) {
            tokens.add(str.substring(start));
        }
        return tokens.toArray(new String[0]);
    }
}
