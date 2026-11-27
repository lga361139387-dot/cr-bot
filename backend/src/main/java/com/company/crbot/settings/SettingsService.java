package com.company.crbot.settings;

import com.company.crbot.common.ApiException;
import com.company.crbot.config.AppProperties;
import com.company.crbot.datasource.AesCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SettingsService {

    public static final String KB_MIN_SCORE = "kb.minScore";
    public static final String MAX_DIFF_BYTES = "review.maxDiffBytes";
    public static final String BATCH_FILE_COUNT = "review.batchFileCount";
    public static final String POST_MR_NOTE = "review.postMrNote";
    public static final String POST_MR_INLINE = "review.postMrInline";
    public static final String POST_FILTER = "review.postFilterEnabled";
    public static final String PATH_RULES = "review.pathRulesEnabled";
    public static final String SECOND_ROUND = "review.secondRoundEnabled";
    public static final String RELOCATE = "review.relocateEnabled";
    public static final String PLAN_LINES = "review.planLineThreshold";
    public static final String STANDARDS_TOP_K = "standards.topK";
    public static final String KNOWLEDGE_TOP_K = "knowledge.topK";
    public static final String DB_TOOL_TRANSPORT = "db.toolTransport";
    public static final String JOB_TIMEOUT_SECONDS = "review.jobTimeoutSeconds";
    public static final String EMBEDDING_BASE_URL = "embedding.baseUrl";
    public static final String LLM_TOOLS = "llm.toolsEnabled";
    public static final String LLM_TEMPERATURE = "llm.temperature";
    public static final String LLM_MODEL = "llm.model";
    public static final String EMBEDDING_MODEL = "embedding.model";
    public static final String GITLAB_BASE_URL = "gitlab.baseUrl";
    public static final String LLM_BASE_URL = "llm.baseUrl";
    public static final String SECRET_LLM_API_KEY = "secret.llmApiKey";
    public static final String SECRET_EMBEDDING_API_KEY = "secret.embeddingApiKey";
    public static final String SECRET_GITLAB_TOKEN = "secret.gitlabToken";
    public static final String SECRET_WEBHOOK = "secret.webhook";
    public static final String KB_PRECHECK = "kb.precheckEnabled";
    public static final String KB_PRECHECK_SKIP = "kb.precheckSkipExtensions";
    public static final String KB_PRECHECK_FORCE = "kb.precheckForceDocTypes";

    private final AppSettingRepository settingRepository;
    private final PromptTemplateRepository promptRepository;
    private final AppProperties properties;

    public SettingsService(AppSettingRepository settingRepository, PromptTemplateRepository promptRepository,
                           AppProperties properties) {
        this.settingRepository = settingRepository;
        this.promptRepository = promptRepository;
        this.properties = properties;
    }

    @Transactional
    public void seedDefaults() {
        // 缺省项只在没有记录时写入，避免覆盖管理端已改配置
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(KB_MIN_SCORE, "0.55");
        defaults.put(MAX_DIFF_BYTES, "512000");
        defaults.put(BATCH_FILE_COUNT, "8");
        defaults.put(POST_MR_NOTE, "false");
        defaults.put(POST_MR_INLINE, "false");
        defaults.put(POST_FILTER, "false");
        defaults.put(PATH_RULES, "true");
        defaults.put(SECOND_ROUND, "true");
        defaults.put(RELOCATE, "false");
        defaults.put(PLAN_LINES, "50");
        defaults.put(STANDARDS_TOP_K, "8");
        defaults.put(KNOWLEDGE_TOP_K, "6");
        defaults.put(DB_TOOL_TRANSPORT, "jdbc");
        defaults.put(JOB_TIMEOUT_SECONDS, "300");
        defaults.put(KB_PRECHECK, "true");
        defaults.put(KB_PRECHECK_SKIP, "txt,md,markdown");
        defaults.put(KB_PRECHECK_FORCE, "meeting,prototype,testcase");
        defaults.forEach(this::putIfAbsent);
        // 没有启用中的评审提示词时写入内置版本
        if (promptRepository.findFirstByNameAndActiveTrueOrderByIdDesc(DefaultPrompt.NAME).isEmpty()) {
            savePrompt(DefaultPrompt.TEXT, "v1");
        }
        // 旧提示词没有高危分级时换成内置版本，避免评审仍按模型自由定级
        promptRepository.findFirstByNameAndActiveTrueOrderByIdDesc(DefaultPrompt.NAME).ifPresent(current -> {
            if (!current.getContent().contains("三个维度") || !current.getContent().contains("existingCode")) {
                savePrompt(DefaultPrompt.TEXT, "v4");
            }
        });
    }

    public String get(String key, String defaultValue) {
        return settingRepository.findById(key).map(AppSettingEntity::getValue).orElse(defaultValue);
    }

    public double kbMinScore() {
        // 相似度达到 80% 才算命中，不再按低中高切换
        return 0.80;
    }

    public int maxDiffBytes() {
        return Integer.parseInt(get(MAX_DIFF_BYTES, "512000"));
    }

    public int batchFileCount() {
        return Integer.parseInt(get(BATCH_FILE_COUNT, "8"));
    }

    public boolean postMrNote() {
        return Boolean.parseBoolean(get(POST_MR_NOTE, "false"));
    }

    public boolean postMrInline() {
        return Boolean.parseBoolean(get(POST_MR_INLINE, "false"));
    }

    public boolean postFilterEnabled() {
        return Boolean.parseBoolean(get(POST_FILTER, "false"));
    }

    public boolean pathRulesEnabled() {
        return Boolean.parseBoolean(get(PATH_RULES, "true"));
    }

    public boolean secondRoundEnabled() {
        return Boolean.parseBoolean(get(SECOND_ROUND, "true"));
    }

    public boolean relocateEnabled() {
        return Boolean.parseBoolean(get(RELOCATE, "false"));
    }

    public int planLineThreshold() {
        return Integer.parseInt(get(PLAN_LINES, "50"));
    }

    public int standardsTopK() {
        return Integer.parseInt(get(STANDARDS_TOP_K, "8"));
    }

    public int knowledgeTopK() {
        return Integer.parseInt(get(KNOWLEDGE_TOP_K, "6"));
    }

    public String dbToolTransport() {
        // 查库固定走页面上配置好的只读数据源
        return "jdbc";
    }

    public int jobTimeoutSeconds() {
        return Integer.parseInt(get(JOB_TIMEOUT_SECONDS, "300"));
    }

    public boolean kbPrecheckEnabled() {
        return Boolean.parseBoolean(get(KB_PRECHECK, "true"));
    }

    public java.util.Set<String> kbPrecheckSkipExtensions() {
        return splitCsv(get(KB_PRECHECK_SKIP, "txt,md,markdown"));
    }

    public java.util.Set<String> kbPrecheckForceDocTypes() {
        return splitCsv(get(KB_PRECHECK_FORCE, "meeting,prototype,testcase"));
    }

    public String llmModel() {
        // 页面保存的值优先，方便接入不同模型；没配过才用环境变量
        return firstNonBlank(get(LLM_MODEL, ""), getenv("LLM_MODEL"), properties.getLlmModel());
    }

    public String embeddingModel() {
        return firstNonBlank(get(EMBEDDING_MODEL, ""), getenv("EMBEDDING_MODEL"), properties.getEmbeddingModel());
    }

    public String llmBaseUrl() {
        return firstNonBlank(get(LLM_BASE_URL, ""), getenv("LLM_BASE_URL"), properties.getLlmBaseUrl());
    }

    public String embeddingBaseUrl() {
        return firstNonBlank(get(EMBEDDING_BASE_URL, ""), properties.getEmbeddingBaseUrl(), llmBaseUrl());
    }

    public boolean llmToolsEnabled() { return Boolean.parseBoolean(get(LLM_TOOLS, "true")); }

    // 空字符串省略 temperature，兼容不接受该参数的推理模型。
    public String llmTemperature() { return get(LLM_TEMPERATURE, ""); }

    public String gitlabBaseUrl() {
        return firstNonBlank(get(GITLAB_BASE_URL, ""), getenv("GITLAB_BASE_URL"), properties.getGitlabBaseUrl());
    }

    public String llmApiKey() {
        // 页面密钥加密入库；还没在页面保存时，继续用环境变量，避免本地试用中断
        return firstNonBlank(readSecret(SECRET_LLM_API_KEY), properties.getLlmApiKey());
    }

    public String embeddingApiKey() {
        String dedicated = firstNonBlank(readSecret(SECRET_EMBEDDING_API_KEY), properties.getEmbeddingApiKey());
        if (!dedicated.isBlank()) {
            return dedicated;
        }
        return embeddingBaseUrl().equals(llmBaseUrl()) ? llmApiKey() : "";
    }

    public String gitlabToken() {
        return firstNonBlank(readSecret(SECRET_GITLAB_TOKEN), properties.getGitlabToken());
    }

    public String webhookSecret() {
        return firstNonBlank(readSecret(SECRET_WEBHOOK), properties.getGitlabWebhookSecret());
    }

    public PromptTemplateEntity activePrompt() {
        return promptRepository.findFirstByNameAndActiveTrueOrderByIdDesc(DefaultPrompt.NAME)
                .orElseThrow(() -> new ApiException("未配置评审提示词"));
    }

    public SettingsView view() {
        return new SettingsView(
                firstNonBlank(get(EMBEDDING_BASE_URL, ""), properties.getEmbeddingBaseUrl()), llmToolsEnabled(), llmTemperature(),
                llmModel(),
                llmBaseUrl(),
                !llmApiKey().isBlank(),
                embeddingModel(),
                !readSecret(SECRET_EMBEDDING_API_KEY).isBlank() || envPresent("EMBEDDING_API_KEY"),
                gitlabBaseUrl(),
                !gitlabToken().isBlank(),
                !webhookSecret().isBlank(),
                postMrNote(),
                dbToolTransport(),
                postMrInline(),
                postFilterEnabled(),
                pathRulesEnabled(),
                secondRoundEnabled(),
                relocateEnabled(),
                planLineThreshold(),
                kbPrecheckEnabled(),
                get(KB_PRECHECK_SKIP, "txt,md,markdown"),
                get(KB_PRECHECK_FORCE, "meeting,prototype,testcase")
        );
    }

    @Transactional
    public SettingsView update(SettingsUpdate update) {
        validateBaseUrl(update.llmBaseUrl());
        validateBaseUrl(update.embeddingBaseUrl());
        if (update.llmTemperature() != null && !update.llmTemperature().isBlank()) {
            try {
                double value = Double.parseDouble(update.llmTemperature());
                if (!Double.isFinite(value) || value < 0 || value > 2) throw new NumberFormatException();
            } catch (NumberFormatException ex) { throw new ApiException("温度必须为 0 到 2 的数字，或留空使用模型默认值"); }
        }
        putText(EMBEDDING_BASE_URL, update.embeddingBaseUrl());
        putText(LLM_TEMPERATURE, update.llmTemperature());
        if (update.llmToolsEnabled() != null) put(LLM_TOOLS, update.llmToolsEnabled().toString());
        // 页面配置写入数据库，之后优先于环境变量
        putText(LLM_MODEL, update.llmModel());
        putText(LLM_BASE_URL, update.llmBaseUrl());
        putText(EMBEDDING_MODEL, update.embeddingModel());
        putText(GITLAB_BASE_URL, update.gitlabBaseUrl());
        putSecret(SECRET_LLM_API_KEY, update.llmApiKey());
        putSecret(SECRET_EMBEDDING_API_KEY, update.embeddingApiKey());
        putSecret(SECRET_GITLAB_TOKEN, update.gitlabToken());
        putSecret(SECRET_WEBHOOK, update.webhookSecret());
        if (update.postMrNote() != null) {
            put(POST_MR_NOTE, Boolean.toString(update.postMrNote()));
        }
        if (update.postMrInline() != null) {
            put(POST_MR_INLINE, Boolean.toString(update.postMrInline()));
        }
        if (update.postFilterEnabled() != null) {
            put(POST_FILTER, Boolean.toString(update.postFilterEnabled()));
        }
        if (update.pathRulesEnabled() != null) {
            put(PATH_RULES, Boolean.toString(update.pathRulesEnabled()));
        }
        if (update.secondRoundEnabled() != null) {
            put(SECOND_ROUND, Boolean.toString(update.secondRoundEnabled()));
        }
        if (update.relocateEnabled() != null) {
            put(RELOCATE, Boolean.toString(update.relocateEnabled()));
        }
        if (update.kbPrecheckEnabled() != null) {
            put(KB_PRECHECK, Boolean.toString(update.kbPrecheckEnabled()));
        }
        if (update.kbPrecheckSkipExtensions() != null) {
            put(KB_PRECHECK_SKIP, normalizeCsv(update.kbPrecheckSkipExtensions()));
        }
        if (update.kbPrecheckForceDocTypes() != null) {
            put(KB_PRECHECK_FORCE, normalizeCsv(update.kbPrecheckForceDocTypes()));
        }
        if (update.dbToolTransport() != null) {
            String transport = update.dbToolTransport().trim();
            if (!transport.equals("jdbc") && !transport.equals("mcp")) {
                throw new ApiException("数据查询方式不正确");
            }
            put(DB_TOOL_TRANSPORT, transport);
        }
        return view();
    }

    public static void validateBaseUrl(String value) {
        if (value == null || value.isBlank()) return;
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new ApiException("接口地址须为 HTTP(S) 基础地址，不能包含密钥、查询参数或片段"); }
    }

    private void savePrompt(String content, String version) {
        // 旧版本取消启用，新版本成为当前提示词
        for (PromptTemplateEntity old : promptRepository.findByNameAndActiveTrue(DefaultPrompt.NAME)) {
            old.setActive(false);
            promptRepository.save(old);
        }
        PromptTemplateEntity created = new PromptTemplateEntity();
        created.setName(DefaultPrompt.NAME);
        created.setVersion(version);
        created.setContent(content);
        created.setActive(true);
        promptRepository.save(created);
    }

    private void putIfAbsent(String key, String value) {
        if (settingRepository.existsById(key)) {
            return;
        }
        put(key, value);
    }

    private void put(String key, String value) {
        AppSettingEntity entity = settingRepository.findById(key).orElseGet(AppSettingEntity::new);
        entity.setKey(key);
        entity.setValue(value);
        entity.setUpdatedAt(OffsetDateTime.now());
        settingRepository.save(entity);
    }

    private void putText(String key, String value) {
        if (value == null) {
            return;
        }
        put(key, value.trim());
    }

    private void putSecret(String key, String plain) {
        // 留空表示不改已保存的密钥，避免每次保存把密钥清空
        if (plain == null || plain.isBlank()) {
            return;
        }
        put(key, AesCipher.encrypt(plain.trim(), properties.getEncKey()));
    }

    private String readSecret(String key) {
        String stored = get(key, "");
        if (stored.isBlank()) {
            return "";
        }
        return AesCipher.decrypt(stored, properties.getEncKey());
    }

    private static String levelOf(double value, double lowMax, double midMax) {
        if (value <= lowMax) {
            return "low";
        }
        if (value <= midMax) {
            return "mid";
        }
        return "high";
    }

    private static double scoreOf(String level) {
        return switch (requireLevel(level)) {
            case "low" -> 0.35;
            case "high" -> 0.75;
            default -> 0.55;
        };
    }

    private static int choiceOf(String level, int low, int mid, int high) {
        return switch (requireLevel(level)) {
            case "low" -> low;
            case "high" -> high;
            default -> mid;
        };
    }

    private static String requireLevel(String level) {
        if ("low".equals(level) || "mid".equals(level) || "high".equals(level)) {
            return level;
        }
        throw new ApiException("请选择低、中或高");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String getenv(String name) {
        return blankToEmpty(System.getenv(name));
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static java.util.Set<String> splitCsv(String raw) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        if (raw == null) {
            return values;
        }
        for (String part : raw.split(",")) {
            String item = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (item.startsWith(".")) {
                item = item.substring(1);
            }
            if (!item.isBlank()) {
                values.add(item);
            }
        }
        return values;
    }

    private static String normalizeCsv(String raw) {
        return String.join(",", splitCsv(raw));
    }

    public record SettingsView(
            String embeddingBaseUrl, boolean llmToolsEnabled, String llmTemperature,
            String llmModel,
            String llmBaseUrl,
            boolean llmApiKeyConfigured,
            String embeddingModel,
            boolean embeddingApiKeyConfigured,
            String gitlabBaseUrl,
            boolean gitlabTokenConfigured,
            boolean webhookSecretConfigured,
            boolean postMrNote,
            String dbToolTransport,
            boolean postMrInline,
            boolean postFilterEnabled,
            boolean pathRulesEnabled,
            boolean secondRoundEnabled,
            boolean relocateEnabled,
            int planLineThreshold,
            boolean kbPrecheckEnabled,
            String kbPrecheckSkipExtensions,
            String kbPrecheckForceDocTypes
    ) {
    }

    public record SettingsUpdate(
            String embeddingBaseUrl, Boolean llmToolsEnabled, String llmTemperature,
            String llmModel,
            String llmBaseUrl,
            String embeddingModel,
            String gitlabBaseUrl,
            String llmApiKey,
            String embeddingApiKey,
            String gitlabToken,
            String webhookSecret,
            Boolean postMrNote,
            String dbToolTransport,
            Boolean postMrInline,
            Boolean postFilterEnabled,
            Boolean pathRulesEnabled,
            Boolean secondRoundEnabled,
            Boolean relocateEnabled,
            Boolean kbPrecheckEnabled,
            String kbPrecheckSkipExtensions,
            String kbPrecheckForceDocTypes
    ) {
    }
}
