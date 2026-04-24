package com.moggo._gdg.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 리소스 경로 {@code classpath:prompts/*.md} 에 있는 모든 프롬프트 템플릿을 앱 시작 시 로드하고,
 * {@code chat.prompt.active-template} 설정으로 지정된 템플릿을 "현재 활성 system prompt" 로 제공한다.
 * <p>
 * 새 템플릿을 추가하려면 해당 디렉토리에 {@code <name>.md} 파일만 만들고 재배포하면 된다.
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private static final String TEMPLATE_LOCATION = "classpath:prompts/*.md";

    private final String activeTemplateName;
    private final Map<String, String> templates = new HashMap<>();

    public PromptTemplateService(
            @Value("${chat.prompt.active-template:polar-bear}") String activeTemplateName) {
        this.activeTemplateName = activeTemplateName;
    }

    @PostConstruct
    void loadTemplates() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        Resource[] resources = resolver.getResources(TEMPLATE_LOCATION);
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) continue;
            String name = filename.replaceFirst("\\.md$", "");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            templates.put(name, content);
        }
        log.info("Loaded {} prompt template(s): {}. Active: '{}'",
                templates.size(), templates.keySet(), activeTemplateName);
        if (!templates.containsKey(activeTemplateName)) {
            log.warn("Active template '{}' not found among loaded templates — system prompt will be empty.",
                    activeTemplateName);
        }
    }

    /** 활성 템플릿의 전체 본문. 없으면 빈 문자열. */
    public String getActiveSystemPrompt() {
        return templates.getOrDefault(activeTemplateName, "");
    }

    public String getActiveTemplateName() {
        return activeTemplateName;
    }

    public Set<String> availableTemplates() {
        return Collections.unmodifiableSet(templates.keySet());
    }
}
