package net.itzq.mira.modules.ai.openapi;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.client.tool.FCUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OpenApiToolImporter {

    private static final String[] HTTP_METHODS =
            {"get", "post", "put", "delete", "patch", "head", "options"};

    public static List<ApiOperation> importSpec(String json) {
        return importSpec(json, new ImportConfig());
    }

    public static List<ApiOperation> importSpec(String json, ImportConfig cfg) {
        List<ApiOperation> registered = new ArrayList<>();
        if (json == null || json.trim().isEmpty())
            return registered;
        JSONObject root = JSON.parseObject(json);
        if (root == null)
            return registered;

        boolean isV3 = root.containsKey("openapi");
        String base = isV3 ? resolveV3Base(root) : resolveV2Base(root);
        String finalBase = (cfg.getBaseUrlOverride() != null && !cfg.getBaseUrlOverride().isEmpty()) ?
                cfg.getBaseUrlOverride() :
                base;
        finalBase = stripTrailingSlash(finalBase);

        OpenApiInvoker.setDefaultTimeoutMs(cfg.getTimeoutMs());
        OpenApiInvoker.setDefaultMaxBytes(cfg.getMaxResponseBytes());
        if (cfg.getHeaderProvider() != null) {
            OpenApiInvoker.setGlobalHeaderProvider(cfg.getHeaderProvider());
        }

        JSONObject paths = root.getJSONObject("paths");
        if (paths == null)
            return registered;

        for (String path : paths.keySet()) {
            JSONObject pathItem = paths.getJSONObject(path);
            if (pathItem == null)
                continue;
            JSONArray sharedParams = pathItem.getJSONArray("parameters");

            for (String m : HTTP_METHODS) {
                JSONObject op = pathItem.getJSONObject(m);
                if (op == null)
                    continue;
                if (!passTagFilter(op, cfg))
                    continue;

                ApiOperation ao = parseOperation(op, m, path, root, finalBase, cfg, sharedParams);
                if (ao == null)
                    continue;


                registered.add(ao);
            }
        }
        return registered;
    }

    public static void unregisterNamespace(String namespace) {
        String prefix = (namespace == null ? "openapi" : namespace) + "_";
        OpenApiRegistry.unregisterByPrefix(prefix);
        FCUtil.unregisterByPrefix(prefix);
    }


    private static List<ApiParam> parseParams(JSONObject op, JSONObject root,
            String path, JSONArray sharedParams) {
        List<ApiParam> list = new ArrayList<>();
        JSONArray parameters = mergeParameters(sharedParams, op.getJSONArray("parameters"));

        // 1) 普通参数
        for (int i = 0; i < parameters.size(); i++) {
            JSONObject p = parameters.getJSONObject(i);
            if (p == null) continue;
            p = resolveIfRef(root, p);
            if (p == null) continue;

            String inStr = p.getString("in");
            if ("body".equals(inStr) || "formData".equals(inStr)) {
                continue; // 统一在后面处理
            }

            ApiParam.In pin = mapIn(inStr, p.getString("name"), path);
            if (pin == null) continue;

            ApiParam ap = new ApiParam();
            ap.setName(p.getString("name"));
            ap.setIn(pin);
            ap.setRequired(pin == ApiParam.In.PATH || p.getBooleanValue("required"));
            ap.setDescription(p.getString("description"));

            JSONObject sch = p.getJSONObject("schema");
            if (sch == null) {
                sch = new JSONObject();
                copyIfPresent(p, sch, "type");
                copyIfPresent(p, sch, "format");
                copyIfPresent(p, sch, "items");
                copyIfPresent(p, sch, "enum");
                copyIfPresent(p, sch, "default");
            }
            sch = resolveIfRef(root, sch);
            ap.setType(typeOf(sch));
            list.add(ap);
        }

        // 2) 请求体处理 (JSON BODY)
        JSONObject bodySchema = null;
        boolean bodyRequired = false;

        for (int i = 0; i < parameters.size(); i++) {
            JSONObject p = parameters.getJSONObject(i);
            if (p == null) continue;
            p = resolveIfRef(root, p);
            if (p == null) continue;
            if ("body".equals(p.getString("in"))) {
                bodySchema = resolveIfRef(root, p.getJSONObject("schema"));
                bodyRequired = p.getBooleanValue("required");
                break;
            }
        }

        if (bodySchema == null && op.containsKey("requestBody")) {
            JSONObject rb = resolveIfRef(root, op.getJSONObject("requestBody"));
            if (rb != null) {
                bodyRequired = rb.getBooleanValue("required");
                JSONObject content = rb.getJSONObject("content");
                if (content != null) {
                    // 优先找 application/json
                    JSONObject mt = content.getJSONObject("application/json");
                    if (mt == null) {
                        for (String ct : content.keySet()) {
                            if (ct.contains("json")) {
                                mt = content.getJSONObject(ct);
                                break;
                            }
                        }
                    }
                    if (mt != null) {
                        bodySchema = resolveIfRef(root, mt.getJSONObject("schema"));
                    }
                }
            }
        }

        if (bodySchema != null) {
            String t = bodySchema.getString("type");
            if ("array".equals(t)) {
                ApiParam ap = new ApiParam();
                ap.setName("body");
                ap.setIn(ApiParam.In.BODY);
                ap.setRequired(bodyRequired);
                ap.setType(List.class);
                ap.setDescription("请求体");
                list.add(ap);
            } else if ("object".equals(t) || t == null) {
                JSONObject props = bodySchema.getJSONObject("properties");
                JSONArray requiredArr = bodySchema.getJSONArray("required");
                if (props != null && !props.isEmpty()) {
                    for (String propName : props.keySet()) {
                        JSONObject ps = resolveIfRef(root, props.getJSONObject(propName));
                        ApiParam ap = new ApiParam();
                        ap.setName(propName);
                        ap.setIn(ApiParam.In.BODY);
                        boolean propRequired = requiredArr != null && requiredArr.contains(propName);
                        ap.setRequired(bodyRequired && propRequired);
                        ap.setDescription(ps != null ? ps.getString("description") : null);
                        ap.setType(typeOf(ps));
                        list.add(ap);
                    }
                } else {
                    ApiParam ap = new ApiParam();
                    ap.setName("body");
                    ap.setIn(ApiParam.In.BODY);
                    ap.setRequired(bodyRequired);
                    ap.setType(Map.class);
                    ap.setDescription("请求体（对象）");
                    list.add(ap);
                }
            } else {
                ApiParam ap = new ApiParam();
                ap.setName("body");
                ap.setIn(ApiParam.In.BODY);
                ap.setRequired(bodyRequired);
                ap.setType(typeOf(bodySchema));
                list.add(ap);
            }
        }

        // 3) 表单参数处理
        // V2 formData
        for (int i = 0; i < parameters.size(); i++) {
            JSONObject p = parameters.getJSONObject(i);
            if (p == null) continue;
            p = resolveIfRef(root, p);
            if (p == null) continue;
            if ("formData".equals(p.getString("in"))) {
                ApiParam ap = new ApiParam();
                ap.setName(p.getString("name"));
                ap.setIn(ApiParam.In.FORM);
                ap.setRequired(p.getBooleanValue("required"));
                ap.setDescription(p.getString("description"));
                JSONObject sch = new JSONObject();
                copyIfPresent(p, sch, "type");
                copyIfPresent(p, sch, "format");
                copyIfPresent(p, sch, "default");
                ap.setType(typeOf(sch));
                list.add(ap);
            }
        }

        // V3 application/x-www-form-urlencoded
        if (op.containsKey("requestBody")) {
            JSONObject rb = resolveIfRef(root, op.getJSONObject("requestBody"));
            if (rb != null) {
                JSONObject content = rb.getJSONObject("content");
                if (content != null) {
                    JSONObject formMt = content.getJSONObject("application/x-www-form-urlencoded");
                    if (formMt != null) {
                        JSONObject formSchema = resolveIfRef(root, formMt.getJSONObject("schema"));
                        if (formSchema != null) {
                            JSONObject props = formSchema.getJSONObject("properties");
                            JSONArray reqArr = formSchema.getJSONArray("required");
                            if (props != null) {
                                for (String propName : props.keySet()) {
                                    JSONObject ps = resolveIfRef(root, props.getJSONObject(propName));
                                    ApiParam ap = new ApiParam();
                                    ap.setName(propName);
                                    ap.setIn(ApiParam.In.FORM);
                                    ap.setRequired(reqArr != null && reqArr.contains(propName));
                                    ap.setDescription(ps != null ? ps.getString("description") : null);
                                    ap.setType(typeOf(ps));
                                    list.add(ap);
                                }
                            }
                        }
                    }
                }
            }
        }

        return list;
    }

    private static boolean passTagFilter(JSONObject op, ImportConfig cfg) {
        if (cfg.getIncludeTags() == null && cfg.getExcludeTags() == null) return true;
        JSONArray tags = op.getJSONArray("tags");
        List<String> opTags = new ArrayList<>();
        if (tags != null) {
            for (int i = 0; i < tags.size(); i++) {
                String t = tags.getString(i);
                if (t != null) opTags.add(t);
            }
        }
        if (cfg.getExcludeTags() != null) {
            for (String ex : cfg.getExcludeTags()) {
                if (opTags.contains(ex)) return false;
            }
        }
        if (cfg.getIncludeTags() != null) {
            for (String inc : cfg.getIncludeTags()) {
                if (opTags.contains(inc)) return true;
            }
            return false;
        }
        return true;
    }

    private static String resolveV2Base(JSONObject root) {
        String host = root.getString("host");
        String basePath = root.getString("basePath");
        JSONArray schemes = root.getJSONArray("schemes");
        String scheme = (schemes != null && !schemes.isEmpty()) ? schemes.getString(0) : "http";
        StringBuilder sb = new StringBuilder();
        if (host != null && !host.isEmpty()) {
            sb.append(scheme).append("://").append(host);
        }
        if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath)) {
            sb.append(basePath);
        }
        return sb.toString();
    }

    private static String resolveV3Base(JSONObject root) {
        JSONArray servers = root.getJSONArray("servers");
        if (servers == null || servers.isEmpty()) return "";
        JSONObject s0 = servers.getJSONObject(0);
        String url = s0.getString("url");
        if (url == null || url.isEmpty()) return "";
        JSONObject vars = s0.getJSONObject("variables");
        if (vars != null) {
            for (String vk : vars.keySet()) {
                JSONObject v = vars.getJSONObject(vk);
                String def = v != null ? v.getString("default") : null;
                url = url.replace("{" + vk + "}", def == null ? "" : def);
            }
        }
        return url;
    }

    private static String stripTrailingSlash(String base) {
        if (base == null) return "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static ApiOperation parseOperation(JSONObject op, String method, String path,
            JSONObject root, String base, ImportConfig cfg,
            JSONArray sharedParams) {
        String opId = op.getString("operationId");
        if (opId == null || opId.isEmpty()) {
            opId = method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_");
        }
        String toolName = sanitize((cfg.getNamespace() == null ? "openapi" : cfg.getNamespace()) + "_" + opId);

        ApiOperation ao = new ApiOperation();
        ao.setName(toolName);
        ao.setSummary(op.getString("summary"));
        ao.setDescription(op.getString("description"));
        ao.setHttpMethod(method.toUpperCase());
        ao.setPathTemplate(path);
        ao.setServerUrl(base);
        if (cfg.getStaticHeaders() != null) {
            ao.setHeaders(cfg.getStaticHeaders());
        }
        ao.setParams(parseParams(op, root, path, sharedParams));
        return ao;
    }

    private static JSONArray mergeParameters(JSONArray sharedParams, JSONArray opParams) {
        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (opParams != null) {
            for (int i = 0; i < opParams.size(); i++) {
                JSONObject p = opParams.getJSONObject(i);
                if (p == null) continue;
                String key = paramKey(p);
                if (seen.add(key)) merged.add(p);
            }
        }
        if (sharedParams != null) {
            for (int i = 0; i < sharedParams.size(); i++) {
                JSONObject p = sharedParams.getJSONObject(i);
                if (p == null) continue;
                String key = paramKey(p);
                if (seen.add(key)) merged.add(p);
            }
        }
        return merged;
    }

    private static String paramKey(JSONObject p) {
        String name = p.getString("name");
        String in = p.getString("in");
        return (name == null ? "" : name) + ":" + (in == null ? "" : in);
    }

    private static void copyIfPresent(JSONObject src, JSONObject dst, String key) {
        if (src.containsKey(key)) dst.put(key, src.get(key));
    }

    private static ApiParam.In mapIn(String in, String name, String path) {
        if (in == null || in.isEmpty()) {
            if (name != null && path != null && path.contains("{" + name + "}")) {
                return ApiParam.In.PATH;
            }
            return ApiParam.In.FORM;
        }
        switch (in) {
            case "query": return ApiParam.In.QUERY;
            case "path": return ApiParam.In.PATH;
            case "header": return ApiParam.In.HEADER;
            case "body": return ApiParam.In.BODY;
            case "formData": return ApiParam.In.FORM;
            case "cookie": return null;
            default: return null;
        }
    }

    private static Class<?> typeOf(JSONObject schema) {
        if (schema == null) return String.class;
        String t = schema.getString("type");
        String fmt = schema.getString("format");
        if ("integer".equals(t)) return ("int64".equals(fmt) || "long".equals(fmt)) ? Long.class : Integer.class;
        if ("number".equals(t)) return "float".equals(fmt) ? Float.class : Double.class;
        if ("boolean".equals(t)) return Boolean.class;
        if ("array".equals(t)) return List.class;
        if ("object".equals(t)) return Map.class;
        if (t == null && schema.containsKey("properties")) return Map.class;
        return String.class;
    }

    private static JSONObject resolveIfRef(JSONObject root, JSONObject schema) {
        if (schema == null || !schema.containsKey("$ref")) return schema;
        String ref = schema.getString("$ref");
        if (ref == null || ref.isEmpty() || root == null) return schema;
        String p = ref.startsWith("#/") ? ref.substring(2) : ref;
        String[] parts = p.split("/");
        Object cur = root;
        for (String part : parts) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).get(part);
            } else {
                return schema;
            }
        }
        return (cur instanceof JSONObject) ? (JSONObject) cur : schema;
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }
}
