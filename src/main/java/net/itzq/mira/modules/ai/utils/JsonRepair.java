package net.itzq.mira.modules.ai.utils;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.json.JsonArray;
import net.itzq.mira.core.utils.json.JsonObject;
import net.itzq.mira.core.utils.json.JsonUtil;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.BasicAgent;
import net.itzq.mira.core.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *  CheckJson
 *
 *  @author tangzq
 */
@Slf4j
public class JsonRepair {

    public static String autoFix(String json) {
        // step 1
        String fastReplaceStr = fastReplace(json);

        Object o = fastTest(fastReplaceStr);
        if (o != null) {
            return JsonUtil.toJson(o);
        }

        // step 2
        String repairStr = repair(fastReplaceStr);
        if (repairStr != null) {
            o = fastTest(repairStr);
        }
        if (o != null) {
            return JsonUtil.toJson(o);
        }

        // aiRepair
        String aiRepair = aiRepair(fastReplaceStr);

        aiRepair = fastReplace(aiRepair);

        if (aiRepair != null) {
            o = fastTest(aiRepair);
        }
        if (o != null) {
            return JsonUtil.toJson(o);
        }

        log.warn("[CheckJson] 无法修复的json : {}", json);
        return null;
    }

    public static String aiRepair(String json) {
        String prompt = "你是一个json检查助手，输入的json存在明显结构错误。请帮助修正格式上的错误，尤其注意'{'数量应与'}'数量保持一致，'['数量应与']'数量保持一致，"
                + "请仅输出修复后的正确json，注意不要输出```json块，直接给出纯json不要包含其他无关内容";

        AgentContextHolder contextHolder = AgentContextHolder.builder().prompt(prompt).build();

        BasicAgent agent = new BasicAgent(contextHolder, "Json修复");

        String ans = agent.chat(json);

        log.info("[CheckJson] AI响应 : {}", ans);

        return ans;
    }

    public static String fastReplace(String json) {
        json = StringUtils.trim(json);

        if (StringUtils.startsWithIgnoreCase(json, "```json")) {
            json = StringUtils.substringAfter(json, "```json");
        }

        if (StringUtils.endsWithIgnoreCase(json, "```")) {
            json = StringUtils.substringBeforeLast(json, "```");
        }
        return json;
    }

    public static Object fastTest(String json) {

        try {
            Object parse = JsonUtil.parse(json);
            return parse;
        } catch (Exception e) {
            return null;
        }

    }

    public static String repair(String input) {
        try {
            Map<String, Object> result = JsonFixer.fixJsonResponse(input);

            log.info("[JsonRepair] 输入文本：{}", input);

            JsonObject res = JsonUtil.parseObject(JsonUtil.toJson(result));
            if (res.containsKey("result")) {
                String jsonString = res.getString("result");
                log.info("[JsonRepair] 输出文本：{}", jsonString);
                return jsonString;
            } else {
                log.info("[JsonRepair] 测试失败：{}", JsonUtil.toJson(result));
                return null;
            }

        } catch (Exception e) {
            log.error("失败", e);
            return null;
        }
    }

    public static class JsonFixer {

        public static Map<String, Object> fixJsonResponse(String llmResponse) {
            if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                try {
                    String rawContent = llmResponse.trim();

                    try {
                        Object parsed = JsonUtil.parse(rawContent);
                        if (parsed instanceof String) {
                            rawContent = (String) parsed;
                        }
                    } catch (Exception var7) {
                    }

                    String extractedContent = extractJsonContent(rawContent);
                    if (extractedContent != null && !extractedContent.isEmpty()) {
                        try {
                            Object finalResult = JsonUtil.parse(extractedContent);
                            return successMap(finalResult);
                        } catch (Exception var9) {
                            String repaired = repairJson(extractedContent);

                            try {
                                Object finalResult = JsonUtil.parse(repaired);
                                return successMap(finalResult);
                            } catch (Exception var8) {
                                Map<String, Object> error = errorMap("转换失败：无法解析JSON");
                                error.put("details", var8.getMessage());
                                error.put("extracted",
                                        extractedContent.length() > 200 ?
                                                extractedContent.substring(0, 200) :
                                                extractedContent);
                                return error;
                            }
                        }
                    } else {
                        return errorMap("转换失败：未找到JSON内容");
                    }
                } catch (Exception var10) {
                    Map<String, Object> error = errorMap("转换失败：处理失败");
                    error.put("details", var10.getMessage());
                    return error;
                }
            } else {
                return errorMap("转换失败：Invalid input");
            }
        }

        private static String extractJsonContent(String text) {
            if (text != null && !text.isEmpty()) {
                Pattern codeBlockPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", 2);
                Matcher codeBlockMatcher = codeBlockPattern.matcher(text);
                if (codeBlockMatcher.find()) {
                    String codeContent = codeBlockMatcher.group(1).trim();
                    if (isJsonLike(codeContent)) {
                        return codeContent;
                    }
                }

                Pattern jsonPrefixPattern = Pattern.compile("JSON:\\s*([\\s\\S]*?)(?:\\s{2,}|$)", 2);
                Matcher jsonPrefixMatcher = jsonPrefixPattern.matcher(text);
                if (jsonPrefixMatcher.find()) {
                    String prefixContent = jsonPrefixMatcher.group(1).trim();
                    if (isJsonLike(prefixContent)) {
                        return prefixContent;
                    }
                }

                return findFirstJsonStructure(text);
            } else {
                return "";
            }
        }

        private static boolean isJsonLike(String str) {
            if (str != null && !str.isEmpty()) {
                String trimmed = str.trim();
                return trimmed.startsWith("{") && trimmed.endsWith("}") || trimmed.startsWith("[") && trimmed.endsWith(
                        "]");
            } else {
                return false;
            }
        }

        private static String findFirstJsonStructure(String text) {
            int firstBrace = text.indexOf('{');
            int firstBracket = text.indexOf('[');

            int start = -1;
            if (firstBrace != -1 && (firstBracket == -1 || firstBrace < firstBracket)) {
                start = firstBrace;
            } else if (firstBracket != -1) {
                start = firstBracket;
            } else {
                return "";
            }

            char startChar = text.charAt(start);
            char endChar = startChar == '{' ? '}' : ']';

            int depth = 0;
            boolean inString = false;
            boolean escape = false;

            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);

                if (escape) {
                    escape = false;
                    continue;
                }

                if (c == '\\') {
                    escape = true;
                    continue;
                }

                if (c == '"' && !escape) {
                    inString = !inString;
                    continue;
                }

                if (!inString) {
                    if (c == startChar) {
                        depth++;
                    } else if (c == endChar) {
                        depth--;
                        if (depth == 0) {
                            return text.substring(start, i + 1).trim();
                        }
                    }
                }
            }
            return text.substring(start).trim();
        }

        private static String repairJson(String jsonStr) {
            if (jsonStr != null && !jsonStr.isEmpty()) {
                String result = jsonStr.trim();
                int firstBrace = result.indexOf(123);
                int firstBracket = result.indexOf(91);
                if (firstBrace == -1 && firstBracket == -1) {
                    return result;
                } else {
                    int start = firstBrace != -1 ? firstBrace : firstBracket;
                    if (start > 0) {
                        result = result.substring(start);
                    }

                    char lastChar = result.charAt(result.length() - 1);
                    if (result.startsWith("{") && lastChar != '}') {
                        result = result + "}";
                    } else if (result.startsWith("[") && lastChar != ']') {
                        result = result + "]";
                    }

                    result = fixUnquotedPropertyNames(result);
                    result = fixSingleQuotes(result);
                    result = removeTrailingCommas(result);
                    result = fixCommonIssues(result);
                    return result;
                }
            } else {
                return jsonStr;
            }
        }

        private static String fixUnquotedPropertyNames(String json) {
            StringBuilder result = new StringBuilder();
            boolean inString = false;
            boolean escape = false;

            label80:
            for (int i = 0; i < json.length(); ++i) {
                char c = json.charAt(i);
                if (escape) {
                    escape = false;
                    result.append(c);
                } else if (c == '\\') {
                    escape = true;
                    result.append(c);
                } else if (c == '"') {
                    inString = !inString;
                    result.append(c);
                } else {
                    if (!inString && (c == '{' || c == ',')) {
                        result.append(c);
                        int j = i + 1;

                        while (true) {
                            if (j >= json.length() || !Character.isWhitespace(json.charAt(j))) {
                                if (j < json.length() && (Character.isLetterOrDigit(json.charAt(j))
                                        || json.charAt(j) == '_' || json.charAt(j) == '-')) {
                                    StringBuilder key;
                                    for (key = new StringBuilder();
                                         j < json.length() && (Character.isLetterOrDigit(json.charAt(j))
                                                 || json.charAt(j) == '_' || json.charAt(j) == '-'); ++j) {
                                        key.append(json.charAt(j));
                                    }

                                    result.append('"').append(key).append('"');
                                    i = j - 1;
                                    continue label80;
                                }
                                break;
                            }

                            result.append(json.charAt(j));
                            ++j;
                        }
                    }

                    result.append(c);
                }
            }

            return result.toString();
        }

        private static String fixSingleQuotes(String json) {
            StringBuilder result = new StringBuilder();
            boolean inDoubleQuote = false;
            boolean inSingleQuote = false;
            boolean escape = false;

            for (int i = 0; i < json.length(); ++i) {
                char c = json.charAt(i);
                if (escape) {
                    escape = false;
                    result.append(c);
                } else if (c == '\\') {
                    escape = true;
                    result.append(c);
                } else if (c == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote;
                    result.append(c);
                } else if (c == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                    result.append('"');
                } else {
                    result.append(c);
                }
            }

            return result.toString();
        }

        private static String removeTrailingCommas(String json) {
            json = json.replaceAll(",\\s*}", "}");
            json = json.replaceAll(",\\s*]", "]");
            json = json.replaceAll(",\\s*([}\\]])", "$1");
            json = json.replaceAll(",+", ",");
            return json;
        }

        private static String fixCommonIssues(String json) {
            String result = json.replaceAll(":+", ":");
            result = result.trim();
            return result;
        }

        private static Map<String, Object> successMap(Object result) {
            Map<String, Object> map = new HashMap();
            map.put("result", result);
            return map;
        }

        private static Map<String, Object> errorMap(String errorMsg) {
            Map<String, Object> map = new HashMap();
            map.put("error", errorMsg);
            return map;
        }

        public static void main(String[] args) {
            Map<String, Object> result = fixJsonResponse("``````j[1,'2',3]`````");
            System.out.println();
            boolean success = !result.containsKey("error");
            String actualOutput;
            if (success) {
                Object resultObj = result.get("result");

                actualOutput = JsonUtil.writeNulls(resultObj);
                if (resultObj instanceof JsonObject) {
                    System.out.println("输出类型: JsonObject");
                } else if (resultObj instanceof JsonArray) {
                    System.out.println("输出类型: JsonArray");
                }
            } else {
                actualOutput = (String) result.get("error");
                if (result.containsKey("details")) {
                    actualOutput = actualOutput + ": " + result.get("details");
                }
            }

            System.out.println(actualOutput);
        }
    }

}
