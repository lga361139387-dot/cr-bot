package com.company.crbot.knowledge;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import com.company.crbot.rag.Chunker;
import com.company.crbot.rag.RagSearchService;
import com.company.crbot.review.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final Set<String> DOC_TYPES = Set.of("prototype", "logic", "meeting", "other", "outline", "solution", "testcase");

    private final KbDocRepository repository;
    private final KbOwnerRepository ownerRepository;
    private final KbIterationRepository iterationRepository;
    private final KbDocOwnerRepository docOwnerRepository;
    private final RagSearchService ragSearchService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final com.company.crbot.usage.TokenLedger tokenLedger;
    private final KnowledgePrecheckService precheckService;

    public KnowledgeService(KbDocRepository repository, KbOwnerRepository ownerRepository,
                            KbIterationRepository iterationRepository, KbDocOwnerRepository docOwnerRepository,
                            RagSearchService ragSearchService, LlmClient llmClient, ObjectMapper objectMapper,
                            com.company.crbot.usage.TokenLedger tokenLedger, KnowledgePrecheckService precheckService) {
        this.repository = repository;
        this.ownerRepository = ownerRepository;
        this.iterationRepository = iterationRepository;
        this.docOwnerRepository = docOwnerRepository;
        this.ragSearchService = ragSearchService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.tokenLedger = tokenLedger;
        this.precheckService = precheckService;
    }

    public List<IterationFacet> iterationFacets() {
        Map<Long, String> iterationNames = new LinkedHashMap<>();
        for (KbIterationEntity iteration : iterationRepository.findAllByOrderByIdAsc()) {
            iterationNames.put(iteration.getId(), iteration.getName());
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (KbDocEntity doc : repository.findAllByOrderByIdDesc()) {
            if (doc.getIterationId() == null) {
                continue;
            }
            counts.merge(doc.getIterationId(), 1L, Long::sum);
        }
        List<IterationFacet> facets = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            facets.add(new IterationFacet(entry.getKey(), iterationNames.getOrDefault(entry.getKey(), ""), entry.getValue()));
        }
        return facets;
    }

    public DocView detail(Long id) {
        require(id);
        for (DocView view : assemble(null, null)) {
            if (id.equals(view.id())) {
                return view;
            }
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "知识库文档不存在");
    }

    public List<DocView> list(String q, Long iterationId) {
        return assemble(q, iterationId).stream().map(DocView::withoutContent).toList();
    }

    private List<DocView> assemble(String q, Long iterationId) {
        List<KbDocEntity> docs = repository.findAllByOrderByIdDesc();
        Map<Long, String> iterationNames = new LinkedHashMap<>();
        for (KbIterationEntity iteration : iterationRepository.findAllByOrderByIdAsc()) {
            iterationNames.put(iteration.getId(), iteration.getName());
        }
        Map<Long, KbOwnerEntity> people = new LinkedHashMap<>();
        for (KbOwnerEntity owner : ownerRepository.findAll()) {
            people.put(owner.getId(), owner);
        }
        List<DocView> views = new ArrayList<>();
        for (KbDocEntity doc : docs) {
            List<OwnerView> owners = new ArrayList<>();
            List<OwnerView> developers = new ArrayList<>();
            List<OwnerView> testers = new ArrayList<>();
            for (KbDocOwnerEntity link : docOwnerRepository.findByDocId(doc.getId())) {
                KbOwnerEntity person = people.get(link.getOwnerId());
                if (person == null) {
                    continue;
                }
                OwnerView view = new OwnerView(person.getId(), person.getName());
                // 按人员角色拆开展示
                if (person.getRole() != null && person.getRole() == 2) {
                    developers.add(view);
                } else if (person.getRole() != null && person.getRole() == 3) {
                    testers.add(view);
                } else {
                    owners.add(view);
                }
            }
            String iterationName = doc.getIterationId() == null ? "" : iterationNames.getOrDefault(doc.getIterationId(), "");
            views.add(new DocView(doc.getId(), doc.getTitle(), doc.getDocType(), doc.getModule(), doc.getTags(),
                    doc.getStatus(), doc.getErrorMessage(), doc.getIterationId(), iterationName, owners, developers, testers, doc.getContentText()));
        }
        if (iterationId != null) {
            // 页签选中的迭代按编号精确匹配
            List<DocView> scoped = new ArrayList<>();
            for (DocView view : views) {
                if (iterationId.equals(view.iterationId())) {
                    scoped.add(view);
                }
            }
            views = scoped;
        }
        if (q == null || q.isBlank()) {
            return views;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<DocView> matched = new ArrayList<>();
        for (DocView view : views) {
            // 任意字段包含关键字即命中，包含正文
            if (view.searchText().contains(needle)) {
                matched.add(view);
            }
        }
        return matched;
    }

    @Transactional
    public KbDocEntity create(SaveRequest request, MultipartFile file) {
        tokenLedger.open(com.company.crbot.usage.TokenLedger.KB_IMPORT, null, request.title());
        try {
            return createOpened(request, file);
        } finally {
            tokenLedger.close();
        }
    }

    private KbDocEntity createOpened(SaveRequest request, MultipartFile file) {
        KbDocEntity entity = new KbDocEntity();
        apply(entity, request, file, true);
        // 标签不接收手工输入，按正文切分；切不出来就拒绝保存
        entity.setStatus("UPLOADED");
        repository.save(entity);
        replaceOwners(entity.getId(), peopleIds(request));
        log.info("创建知识库文档 user={} id={} title={}", CurrentUser.username(), entity.getId(), entity.getTitle());
        tokenLedger.bind(entity.getId());
        return afterContentReady(entity);
    }

    @Transactional
    public KbDocEntity update(Long id, SaveRequest request, MultipartFile file) {
        KbDocEntity entity = require(id);
        String previous = entity.getContentText();
        apply(entity, request, file, false);
        if (request.ownerIds() != null || request.developerIds() != null || request.testerIds() != null) {
            replaceOwners(id, peopleIds(request));
        }
        boolean contentChanged = request.contentText() != null || (file != null && !file.isEmpty());
        if (contentChanged && (entity.getContentText() == null || !entity.getContentText().equals(previous))) {
            tokenLedger.open(com.company.crbot.usage.TokenLedger.KB_IMPORT, id, entity.getTitle());
            try {
                ragSearchService.deleteChunks("knowledge", id);
                entity.setTags(null);
                entity.setStatus("UPLOADED");
                entity.setErrorMessage(null);
                repository.save(entity);
                return afterContentReady(entity);
            } finally {
                tokenLedger.close();
            }
        }
        return repository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        ragSearchService.deleteChunks("knowledge", id);
        docOwnerRepository.deleteByDocId(id);
        log.info("删除知识库文档 user={} id={}", CurrentUser.username(), id);
        repository.deleteById(id);
    }

    @Transactional
    public KbDocEntity reindex(Long id) {
        KbDocEntity entity = require(id);
        if ("PRECHECK".equals(entity.getStatus()) || "PRECHECK_FAILED".equals(entity.getStatus())) {
            throw new ApiException("请先完成疑点确认");
        }
        boolean opened = tokenLedger.openIfAbsent(com.company.crbot.usage.TokenLedger.KB_REINDEX, id, entity.getTitle());
        try {
            return reindexOpened(entity);
        } finally {
            if (opened) {
                tokenLedger.close();
            }
        }
    }

    private KbDocEntity reindexOpened(KbDocEntity entity) {
        try {
            String metadata = objectMapper.createObjectNode()
                    .put("title", entity.getTitle())
                    .put("docType", entity.getDocType())
                    .put("module", entity.getModule() == null ? "" : entity.getModule())
                    .put("tags", entity.getTags() == null ? "" : entity.getTags())
                    .toString();
            ragSearchService.replaceChunks("knowledge", entity.getId(),
                    Chunker.chunkKnowledge(entity.getDocType(), entity.getContentText()), metadata);
            entity.setStatus("INDEXED");
            entity.setErrorMessage(null);
        } catch (Exception ex) {
            entity.setStatus("FAILED");
            entity.setErrorMessage(trim(ex.getMessage()));
            log.warn("知识库索引失败 id={} message={}", entity.getId(), ex.getMessage());
        }
        return repository.save(entity);
    }

    public List<RagSearchService.ChunkView> chunks(Long id) {
        require(id);
        return ragSearchService.listChunks("knowledge", id);
    }

    public KnowledgePrecheckService.PrecheckView precheck(Long id) {
        require(id);
        return precheckService.view(id);
    }

    @Transactional
    public KbDocEntity retryPrecheck(Long id) {
        KbDocEntity entity = require(id);
        if (!"PRECHECK".equals(entity.getStatus()) && !"PRECHECK_FAILED".equals(entity.getStatus())) {
            throw new ApiException("当前文档不在预审中");
        }
        tokenLedger.open(com.company.crbot.usage.TokenLedger.KB_IMPORT, id, entity.getTitle());
        try {
            return afterContentReady(entity);
        } finally {
            tokenLedger.close();
        }
    }

    @Transactional
    public KbDocEntity confirmPrecheck(Long id, List<PrecheckTexts.Decision> decisions) {
        KbDocEntity entity = require(id);
        if (!"PRECHECK".equals(entity.getStatus())) {
            throw new ApiException("当前文档不在待确认状态");
        }
        tokenLedger.open(com.company.crbot.usage.TokenLedger.KB_IMPORT, id, entity.getTitle());
        try {
            precheckService.confirm(id, decisions);
            return indexOriginal(entity);
        } finally {
            tokenLedger.close();
        }
    }

    private KbDocEntity afterContentReady(KbDocEntity entity) {
        if (precheckService.enabled()) {
            try {
                // 预审打开时先梳理全文，入库和详情都用这份正文
                entity.setContentText(precheckService.organize(entity.getContentText()));
            } catch (Exception ex) {
                entity.setStatus("PRECHECK_FAILED");
                entity.setErrorMessage(trim(ex.getMessage()));
                log.warn("知识库梳理失败 id={} message={}", entity.getId(), ex.getMessage());
                return repository.save(entity);
            }
        }
        return indexOriginal(entity);
    }

    private KbDocEntity indexOriginal(KbDocEntity entity) {
        // 分块和向量只使用解析后的原文，预审备注不写进正文
        entity.setTags(cutTags(entity.getContentText()));
        repository.save(entity);
        return reindexOpened(entity);
    }

    private void apply(KbDocEntity entity, SaveRequest request, MultipartFile file, boolean creating) {
        if (creating && (request.title() == null || request.title().isBlank())) {
            throw new ApiException("标题不能为空");
        }
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new ApiException("标题不能为空");
            }
            entity.setTitle(request.title().trim());
        }
        if (creating || request.docType() != null) {
            String docType = request.docType() == null ? "" : request.docType().trim();
            if (!DOC_TYPES.contains(docType)) {
                throw new ApiException("文档类型不正确");
            }
            entity.setDocType(docType);
        }
        if (creating || request.iterationId() != null) {
            if (request.iterationId() == null) {
                throw new ApiException("迭代版本不能为空");
            }
            // 版本必须来自已维护列表
            if (!iterationRepository.existsById(request.iterationId())) {
                throw new ApiException("迭代版本不存在");
            }
            entity.setIterationId(request.iterationId());
        }
        if (request.module() != null && !request.module().isBlank()) {
            entity.setModule(request.module().trim());
        }
        if (creating && (request.ownerIds() == null || request.ownerIds().isEmpty())) {
            throw new ApiException("请选择产品负责人");
        }
        requirePeople(request.ownerIds(), (short) 1, "产品负责人");
        requirePeople(request.developerIds(), (short) 2, "开发人员");
        requirePeople(request.testerIds(), (short) 3, "测试人员");
        if (file != null && !file.isEmpty()) {
            entity.setSourceExt(KnowledgeFiles.extension(file));
            entity.setContentText(KnowledgeFiles.read(file));
        } else if (request.contentText() != null) {
            if (request.contentText().isBlank()) {
                throw new ApiException("正文不能为空");
            }
            entity.setContentText(request.contentText());
        } else if (creating) {
            throw new ApiException("请上传文件");
        }
    }

    private void requirePeople(List<Long> ids, short role, String label) {
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            KbOwnerEntity person = id == null ? null : ownerRepository.findById(id).orElse(null);
            // 必须是对应角色的人员，不能把开发人员当成产品负责人
            if (person == null || person.getRole() == null || person.getRole() != role) {
                throw new ApiException(label + "不存在");
            }
        }
    }

    private List<Long> peopleIds(SaveRequest request) {
        List<Long> ids = new ArrayList<>();
        if (request.ownerIds() != null) {
            ids.addAll(request.ownerIds());
        }
        if (request.developerIds() != null) {
            ids.addAll(request.developerIds());
        }
        if (request.testerIds() != null) {
            ids.addAll(request.testerIds());
        }
        return ids;
    }

    private void replaceOwners(Long docId, List<Long> ownerIds) {
        docOwnerRepository.deleteByDocId(docId);
        if (ownerIds == null) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>(ownerIds);
        for (Long ownerId : unique) {
            KbDocOwnerEntity link = new KbDocOwnerEntity();
            link.setDocId(docId);
            link.setOwnerId(ownerId);
            docOwnerRepository.save(link);
        }
    }

    private String cutTags(String content) {
        String sample = content.length() > 6000 ? content.substring(0, 6000) : content;
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject().put("role", "system").put("content",
                "根据文档切出检索标签。只输出 JSON：{\"tags\":[\"标签\"]}。3到8个简短中文词，不要解释。");
        messages.addObject().put("role", "user").put("content", sample);
        JsonNode message = llmClient.chat(messages, null);
        JsonNode tags = readTags(message.path("content").asText(""));
        List<String> values = new ArrayList<>();
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String text = tag.asText("").trim();
                if (!text.isBlank() && !values.contains(text)) {
                    values.add(text);
                }
            }
        }
        if (values.isEmpty()) {
            throw new ApiException("模型没有切出标签");
        }
        String joined = String.join(",", values);
        return joined.length() > 512 ? joined.substring(0, 512) : joined;
    }

    private JsonNode readTags(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ApiException("模型没有返回标签 JSON");
        }
        try {
            return objectMapper.readTree(trimmed.substring(start, end + 1)).path("tags");
        } catch (Exception ex) {
            throw new ApiException("解析标签失败");
        }
    }

    private KbDocEntity require(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "知识库文档不存在"));
    }

    private String trim(String message) {
        if (message == null || message.isBlank()) {
            return "索引失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    public record SaveRequest(String title, String docType, Long iterationId, List<Long> ownerIds, List<Long> developerIds,
                              List<Long> testerIds, String module, String contentText) {
    }

    public record IterationFacet(Long id, String name, long count) {
    }

    public record OwnerView(Long id, String name) {
    }

    public record DocView(Long id, String title, String docType, String module, String tags, String status, String errorMessage,
                          Long iterationId, String iterationName, List<OwnerView> owners, List<OwnerView> developers,
                          List<OwnerView> testers, String contentText) {

        String searchText() {
            StringBuilder builder = new StringBuilder();
            builder.append(title == null ? "" : title).append(' ')
                    .append(docType == null ? "" : docType).append(' ')
                    .append(module == null ? "" : module).append(' ')
                    .append(tags == null ? "" : tags).append(' ')
                    .append(iterationName == null ? "" : iterationName).append(' ')
                    .append(contentText == null ? "" : contentText);
            for (OwnerView owner : owners) {
                builder.append(' ').append(owner.name());
            }
            for (OwnerView developer : developers) {
                builder.append(' ').append(developer.name());
            }
            for (OwnerView tester : testers) {
                builder.append(' ').append(tester.name());
            }
            return builder.toString().toLowerCase(Locale.ROOT);
        }

        DocView withoutContent() {
            return new DocView(id, title, docType, module, tags, status, errorMessage, iterationId, iterationName, owners, developers, testers, null);
        }
    }
}
