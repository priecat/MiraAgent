package net.itzq.mira.modules.ai.openapi;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class OpenApiInvoker {

    private static volatile Supplier<Map<String, String>> globalHeaderProvider;
    private static volatile int defaultTimeoutMs = 10000;
    private static volatile int defaultMaxBytes = 64 * 1024;

    public static void setGlobalHeaderProvider(Supplier<Map<String, String>> provider) {
        globalHeaderProvider = provider;
    }

    public static void setDefaultTimeoutMs(int ms) {
        defaultTimeoutMs = ms;
    }

    public static void setDefaultMaxBytes(int bytes) {
        defaultMaxBytes = bytes;
    }

    public String execute(JSONObject args, AgentContextHolder contextHolder, ApiOperation apiOperation) {
        if (apiOperation == null) {
            JSONObject rtn = new JSONObject();
            rtn.put("error", "工具元数据缺失");
            return rtn.toJSONString();
        }

        return invoke(apiOperation, args, contextHolder);
    }

    protected static String invoke(ApiOperation op, JSONObject args, AgentContextHolder contextHolder) {
        if (args == null) {
            args = new JSONObject();
        }
        try {
            String base = op.getServerUrl() != null ? op.getServerUrl() : "";
            String path = op.getPathTemplate() != null ? op.getPathTemplate() : "";

            // 1. 路径参数替换
            StringBuilder pathPart = new StringBuilder();
            int i = 0;
            while (i < path.length()) {
                char c = path.charAt(i);
                if (c == '{') {
                    int j = path.indexOf('}', i);
                    if (j < 0) {
                        pathPart.append(path.substring(i));
                        break;
                    }
                    String pname = path.substring(i + 1, j);
                    Object v = args.get(pname);
                    if (v != null) {
                        String encoded = URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8.name());
                        encoded = encoded.replace("+", "%20");
                        pathPart.append(encoded);
                    }
                    i = j + 1;
                } else {
                    pathPart.append(c);
                    i++;
                }
            }

            String fullUrl = joinUrl(base, pathPart.toString());
            String methodStr = op.getHttpMethod() != null ? op.getHttpMethod().toUpperCase() : "POST";

            // 转换为 Hutool 的 Method 枚举
            Method method;
            try {
                method = Method.valueOf(methodStr);
            } catch (IllegalArgumentException e) {
                method = Method.POST; // 默认 POST
            }

            // 2. 收集 Header（静态头 + 全局头）
            Map<String, String> headers = new HashMap<>();
            if (op.getHeaders() != null) {
                headers.putAll(op.getHeaders());
            }
            if (globalHeaderProvider != null) {
                Map<String, String> gh = globalHeaderProvider.get();
                if (gh != null) {
                    headers.putAll(gh);
                }
            }

            // 3. 使用 UrlBuilder 构建 URL 并处理 QUERY 参数
            UrlBuilder urlBuilder = UrlBuilder.ofHttpWithoutEncode(fullUrl);

            // 4. 分离参数：QUERY / HEADER / BODY (JSON) / FORM (UrlEncoded)
            List<ApiParam> bodyParams = new ArrayList<>();
            List<ApiParam> formParams = new ArrayList<>();

            if (op.getParams() != null) {
                for (ApiParam p : op.getParams()) {
                    if (p.getIn() == ApiParam.In.PATH) {
                        continue;
                    }
                    Object val = args.get(p.getName());
                    if (val == null) {
                        continue;
                    }
                    switch (p.getIn()) {
                        case QUERY:
                            if (val instanceof Iterable) {
                                for (Object item : (Iterable<?>) val) {
                                    urlBuilder.addQuery(p.getName(), item == null ? "" : String.valueOf(item));
                                }
                            } else {
                                urlBuilder.addQuery(p.getName(), String.valueOf(val));
                            }
                            break;
                        case HEADER:
                            headers.put(p.getName(), String.valueOf(val));
                            break;
                        case BODY:
                            bodyParams.add(p);
                            break;
                        case FORM:
                            formParams.add(p);
                            break;
                        default:
                            break;
                    }
                }
            }

            // 构建 Hutool HttpRequest
            HttpRequest req = HttpRequest.of(urlBuilder.build())
                    .method(method)
                    .setConnectionTimeout(defaultTimeoutMs)
                    .setReadTimeout(defaultTimeoutMs);

            // 应用所有收集到的 Header
            for (Map.Entry<String, String> e : headers.entrySet()) {
                req.header(e.getKey(), e.getValue());
            }

            // 5. 处理 JSON BODY
            if (!bodyParams.isEmpty()) {
                Object bodyPayload;
                if (bodyParams.size() == 1) {
                    ApiParam p = bodyParams.get(0);
                    bodyPayload = args.get(p.getName());
                } else {
                    JSONObject bodyObj = new JSONObject();
                    for (ApiParam p : bodyParams) {
                        Object val = args.get(p.getName());
                        if (val != null) {
                            bodyObj.put(p.getName(), val);
                        }
                    }
                    bodyPayload = bodyObj;
                }

                if (bodyPayload != null) {
                    req.header("Content-Type", "application/json");
                    if (bodyPayload instanceof String) {
                        req.body((String) bodyPayload);
                    } else {
                        req.body(JSON.toJSONString(bodyPayload));
                    }
                }
            }

            // 6. 处理 FORM (x-www-form-urlencoded)
            // Hutool 在调用 form 时会自动设置 Content-Type: application/x-www-form-urlencoded
            if (!formParams.isEmpty()) {
                for (ApiParam p : formParams) {
                    Object val = args.get(p.getName());
                    if (val != null) {
                        if (val instanceof Iterable) {
                            for (Object item : (Iterable<?>) val) {
                                req.form(p.getName(), item == null ? "" : String.valueOf(item));
                            }
                        } else {
                            req.form(p.getName(), String.valueOf(val));
                        }
                    }
                }
            }

            // 执行请求并处理响应
            try (HttpResponse resp = req.execute()) {
                return normalize(resp);
            }
        } catch (Exception e) {
            JSONObject rtn = new JSONObject();
            rtn.put("error", e.getMessage());
            return rtn.toJSONString();
        }
    }

    private static String joinUrl(String base, String path) {
        if (base == null || base.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(base);
        boolean baseEndsSlash = base.endsWith("/");
        boolean pathStartsSlash = !path.isEmpty() && path.charAt(0) == '/';
        if (baseEndsSlash && pathStartsSlash) {
            sb.append(path.substring(1));
        } else if (!baseEndsSlash && !pathStartsSlash) {
            sb.append('/').append(path);
        } else {
            sb.append(path);
        }
        return sb.toString();
    }

    private static String normalize(HttpResponse resp) {
        int status = resp.getStatus();
        String bodyStr = resp.body();
        if (bodyStr != null && bodyStr.length() > defaultMaxBytes) {
            bodyStr = bodyStr.substring(0, defaultMaxBytes) + "...(truncated)";
        }
        JSONObject out = new JSONObject();
        out.put("status", status);
        try {
            Object parsed = JSON.parse(bodyStr);
            out.put("body", parsed);
        } catch (Exception e) {
            out.put("body", bodyStr);
        }
        return out.toJSONString();
    }
}
