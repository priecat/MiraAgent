package net.itzq.mira.core.utils;

import java.nio.file.Files;
import java.nio.file.Paths;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 *  SystemLoader
 *
 *  @author tangzq
 */
@Slf4j
public class PromptLoader {

    public static String prompt_react_system = "assets/prompt/react.md";

    public static String readFileString(String path) {

        StringBuilder str = new StringBuilder();
        try {
            str.append(new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // ignore
        }

        if (StringUtils.isNoneBlank(str.toString())) {
            log.info(">>> Load [{}] File", path);
            return str.toString();
        }

        str = new StringBuilder();



        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(path);
                InputStreamReader isr = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);) {
            String line;
            while ((line = br.readLine()) != null) {
                str.append(line).append("\n");
            }

        } catch (Exception e) {

        }

        if (StringUtils.isNoneBlank(str.toString())) {
            log.info(">>> Load [{}] Resources", path);
            return str.toString();
        }

        return "";
    }

    public static Template createTemplate(String templateContent) {
        return createTemplate(IdGen.uuidShort(), templateContent);
    }

    public static Template createTemplate(String name, String templateContent) {
        Configuration config = new Configuration(Configuration.VERSION_2_3_30);
        config.setTemplateLoader(new StringTemplateLoader());

        try {
            return new Template(name, templateContent, config);
        } catch (IOException e) {
            log.error("", e);
            return null;
        }
    }

    public static String prompt(String path) {
        return prompt(path, null);
    }

    public static String prompt(String path, PropsMap params) {
        String templateString = PromptLoader.readFileString(path);
        Template template = PromptLoader.createTemplate(templateString);
        if (params == null) {
            params = new PropsMap();
        }

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                params.put(entry.getKey(), "");
            }
        }

        return processTemplate(template, params);
    }

    public static String processTemplate(Template template, PropsMap params) {
        if (template == null) {
            log.warn("template is null");
            return "";
        }

        if (params == null) {
            params = new PropsMap();
        }

        StringWriter stringWriter = new StringWriter();
        try {
            template.process(params, stringWriter);
        } catch (Exception e) {
            log.error("", e);
            return "";
        }
        return stringWriter.toString();
    }
}
