package com.company.crbot.standard;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import com.company.crbot.common.TextFiles;
import com.company.crbot.rag.Chunker;
import com.company.crbot.rag.RagSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class StandardService {

    private static final Logger log = LoggerFactory.getLogger(StandardService.class);

    private final StandardDocRepository repository;
    private final StandardRuleRepository ruleRepository;
    private final RagSearchService ragSearchService;
    private final ObjectMapper objectMapper;
    private final com.company.crbot.usage.TokenLedger tokenLedger;

    public StandardService(StandardDocRepository repository, StandardRuleRepository ruleRepository,
                           RagSearchService ragSearchService, ObjectMapper objectMapper,
                           com.company.crbot.usage.TokenLedger tokenLedger) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.ragSearchService = ragSearchService;
        this.objectMapper = objectMapper;
        this.tokenLedger = tokenLedger;
    }

    public List<StandardDocEntity> list() {
        return repository.findByDeletedFalseOrderByIdDesc();
    }

    public StandardDocEntity get(Long id) {
        return require(id);
    }

    public RulesView rules(Long id) {
        StandardDocEntity entity = require(id);
        List<RuleItem> rules = syncRules(entity);
        long enabledCount = rules.stream().filter(RuleItem::enabled).count();
        String summary = rules.isEmpty() ? "" : "共 " + rules.size() + " 条规则，启用 " + enabledCount + " 条。评审只引用已启用的规则。";
        return new RulesView(entity.getId(), entity.getTitle(), entity.getVersion(), entity.isActive(), entity.getStatus(), summary, rules);
    }

    public String enabledRulePrompt() {
        StringBuilder builder = new StringBuilder();
        for (StandardDocEntity entity : repository.findByActiveTrueAndDeletedFalse()) {
            // 文件未启用的规范不进评审；文件启用时也只带仍开启的规则
            List<StandardRuleExtractor.Rule> rules = enabledOnly(entity);
            if (rules.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(StandardRuleExtractor.render(entity.getTitle() + " " + entity.getVersion(), rules));
        }
        return builder.toString().trim();
    }

    @Transactional
    public StandardDocEntity upload(String title, String version, MultipartFile file) {
        if (title == null || title.isBlank() || version == null || version.isBlank()) {
            throw new ApiException("标题和版本不能为空");
        }
        StandardDocEntity entity = new StandardDocEntity();
        entity.setTitle(title.trim());
        entity.setVersion(version.trim());
        entity.setSourceFilename(file.getOriginalFilename());
        entity.setContentText(TextFiles.readUtf8(file));
        entity.setStatus("UPLOADED");
        entity.setActive(false);
        entity.setDeleted(false);
        repository.save(entity);
        log.info("上传编码规范 user={} id={} title={}", CurrentUser.username(), entity.getId(), entity.getTitle());
        return reindex(entity.getId());
    }

    @Transactional
    public StandardDocEntity activate(Long id) {
        StandardDocEntity entity = require(id);
        if (!"INDEXED".equals(entity.getStatus())) {
            throw new ApiException("只有索引成功的规范可以启用");
        }
        // 多份规范可以同时启用，这里只打开当前这一份
        entity.setActive(true);
        syncRules(entity);
        // 文件级启用打开该文件的全部规则
        setRulesEnabled(id, true);
        log.info("启用编码规范 user={} id={}", CurrentUser.username(), id);
        return repository.save(entity);
    }

    @Transactional
    public StandardDocEntity deactivate(Long id) {
        StandardDocEntity entity = require(id);
        // 未启用时再点禁用没有可关闭的规则，直接拒绝
        if (!entity.isActive()) {
            throw new ApiException("这条规范没有启用");
        }
        entity.setActive(false);
        syncRules(entity);
        // 文件级禁用关掉该文件的全部规则
        setRulesEnabled(id, false);
        log.info("禁用编码规范 user={} id={}", CurrentUser.username(), id);
        return repository.save(entity);
    }

    @Transactional
    public RulesView setRuleEnabled(Long id, String code, boolean enabled) {
        StandardDocEntity entity = require(id);
        if (!"INDEXED".equals(entity.getStatus())) {
            throw new ApiException("只有索引成功的规范可以改规则开关");
        }
        syncRules(entity);
        StandardRuleEntity rule = ruleRepository.findByStandardIdAndCode(id, code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "规则不存在"));
        if (enabled) {
            // 打开一条规则时，只把这份文件标成启用，其它规范保持原状
            entity.setActive(true);
            rule.setEnabled((short) 1);
        } else {
            rule.setEnabled((short) 0);
            // 规则全部关掉后，文件也不再算启用
            boolean anyEnabled = ruleRepository.findByStandardIdOrderByIdAsc(id).stream().anyMatch(item -> item.getEnabled() != null && item.getEnabled() == 1);
            if (!anyEnabled) {
                entity.setActive(false);
            }
        }
        log.info("规则开关 user={} standardId={} code={} enabled={}", CurrentUser.username(), id, code, enabled);
        ruleRepository.save(rule);
        return rules(id);
    }

    @Transactional
    public void delete(Long id) {
        StandardDocEntity entity = require(id);
        entity.setDeleted(true);
        entity.setActive(false);
        ragSearchService.deleteChunks("standard", id);
        ruleRepository.deleteAll(ruleRepository.findByStandardIdOrderByIdAsc(id));
        log.info("软删除编码规范 user={} id={}", CurrentUser.username(), id);
        repository.save(entity);
    }

    @Transactional
    public StandardDocEntity reindex(Long id) {
        StandardDocEntity entity = require(id);
        boolean opened = tokenLedger.openIfAbsent(com.company.crbot.usage.TokenLedger.STANDARD_INDEX, id, entity.getTitle());
        try {
            return reindexOpened(entity);
        } finally {
            if (opened) {
                tokenLedger.close();
            }
        }
    }

    private StandardDocEntity reindexOpened(StandardDocEntity entity) {
        try {
            String metadata = objectMapper.createObjectNode()
                    .put("title", entity.getTitle())
                    .put("version", entity.getVersion())
                    .toString();
            ragSearchService.replaceChunks("standard", entity.getId(), Chunker.chunkStandard(entity.getContentText()), metadata);
            entity.setStatus("INDEXED");
            entity.setErrorMessage(null);
        } catch (Exception ex) {
            entity.setStatus("FAILED");
            entity.setErrorMessage(trim(ex.getMessage()));
            log.warn("规范索引失败 id={} message={}", entity.getId(), ex.getMessage());
        }
        return repository.save(entity);
    }

    private StandardDocEntity require(Long id) {
        return repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "规范不存在"));
    }

    public record RuleItem(String code, String title, String summary, List<String> requirements, List<String> counterexamples,
                           boolean enabled) {
    }

    public record RulesView(Long id, String title, String version, boolean active, String status, String summary,
                            List<RuleItem> rules) {
    }

    private List<StandardRuleExtractor.Rule> enabledOnly(StandardDocEntity entity) {
        List<RuleItem> items = syncRules(entity);
        List<StandardRuleExtractor.Rule> picked = new java.util.ArrayList<>();
        for (RuleItem item : items) {
            // 单项禁用的规则不送进评审
            if (!item.enabled()) {
                continue;
            }
            picked.add(new StandardRuleExtractor.Rule(item.code(), item.title(), item.summary(), item.requirements(), item.counterexamples()));
        }
        return picked;
    }

    private List<RuleItem> syncRules(StandardDocEntity entity) {
        List<StandardRuleExtractor.Rule> extracted = StandardRuleExtractor.extract(entity.getContentText());
        java.util.Map<String, StandardRuleEntity> stored = new java.util.LinkedHashMap<>();
        for (StandardRuleEntity row : ruleRepository.findByStandardIdOrderByIdAsc(entity.getId())) {
            stored.put(row.getCode(), row);
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<RuleItem> items = new java.util.ArrayList<>();
        for (StandardRuleExtractor.Rule rule : extracted) {
            seen.add(rule.code());
            StandardRuleEntity row = stored.get(rule.code());
            if (row == null) {
                row = new StandardRuleEntity();
                row.setStandardId(entity.getId());
                row.setCode(rule.code());
                // 文件已启用时新规则默认开启，文件禁用时新规则默认关闭
                row.setEnabled(entity.isActive() ? (short) 1 : (short) 0);
            }
            row.setTitle(rule.title());
            ruleRepository.save(row);
            items.add(new RuleItem(rule.code(), rule.title(), rule.summary(), rule.requirements(), rule.counterexamples(), row.getEnabled() != null && row.getEnabled() == 1));
        }
        for (StandardRuleEntity row : stored.values()) {
            // 文档里已经没有的标题不再保留开关
            if (!seen.contains(row.getCode())) {
                ruleRepository.delete(row);
            }
        }
        return items;
    }

    private void setRulesEnabled(Long standardId, boolean enabled) {
        short flag = enabled ? (short) 1 : (short) 0;
        for (StandardRuleEntity row : ruleRepository.findByStandardIdOrderByIdAsc(standardId)) {
            row.setEnabled(flag);
            ruleRepository.save(row);
        }
    }

    private String trim(String message) {
        if (message == null || message.isBlank()) {
            return "索引失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
