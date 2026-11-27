package com.company.crbot.knowledge;

import com.company.crbot.gitlab.GitlabClient;
import com.company.crbot.rag.RagSearchService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeCatalogService catalogService;
    private final GitlabClient gitlabClient;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeCatalogService catalogService, GitlabClient gitlabClient) {
        this.knowledgeService = knowledgeService;
        this.catalogService = catalogService;
        this.gitlabClient = gitlabClient;
    }

    @GetMapping("/docs")
    public List<KnowledgeService.DocView> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long iterationId) {
        // 不传迭代时返回全部文档
        return knowledgeService.list(q, iterationId);
    }

    @GetMapping("/docs/{id}")
    public KnowledgeService.DocView detail(@PathVariable Long id) {
        return knowledgeService.detail(id);
    }

    @GetMapping("/docs/facets")
    public List<KnowledgeService.IterationFacet> facets() {
        // 页签只列出已经有文档的迭代
        return knowledgeService.iterationFacets();
    }

    @GetMapping("/projects")
    public List<GitlabClient.RepoProject> projects() {
        return gitlabClient.listProjects();
    }

    @GetMapping("/owners")
    public List<KbOwnerEntity> owners(@RequestParam Short role) {
        return catalogService.owners(role);
    }

    @GetMapping("/gitlab-users")
    public List<GitlabClient.GitUser> gitlabUsers() {
        return gitlabClient.listUsers();
    }

    @PostMapping("/owners")
    public KbOwnerEntity createOwner(@RequestBody Map<String, Object> body) {
        Object role = body.get("role");
        Short roleCode = role instanceof Number number ? number.shortValue() : null;
        Object gitlabUserId = body.get("gitlabUserId");
        Long gitId = gitlabUserId instanceof Number number ? number.longValue() : null;
        return catalogService.createOwner(body.get("name") == null ? null : String.valueOf(body.get("name")), roleCode, gitId);
    }

    @DeleteMapping("/owners/{id}")
    public void deleteOwner(@PathVariable Long id) {
        catalogService.deleteOwner(id);
    }

    @GetMapping("/iterations")
    public List<KbIterationEntity> iterations() {
        return catalogService.iterations();
    }

    @PostMapping("/iterations")
    public KbIterationEntity createIteration(@RequestBody Map<String, String> body) {
        return catalogService.createIteration(body.get("name"));
    }

    @DeleteMapping("/iterations/{id}")
    public void deleteIteration(@PathVariable Long id) {
        catalogService.deleteIteration(id);
    }

    @PostMapping(value = "/docs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KbDocEntity createJson(@RequestBody KnowledgeService.SaveRequest request) {
        return knowledgeService.create(request, null);
    }

    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KbDocEntity createFile(@RequestParam String title,
                                  @RequestParam String docType,
                                  @RequestParam Long iterationId,
                                  @RequestParam List<Long> ownerIds,
                                  @RequestParam(required = false) List<Long> developerIds,
                                  @RequestParam(required = false) List<Long> testerIds,
                                  @RequestPart("file") MultipartFile file) {
        return knowledgeService.create(new KnowledgeService.SaveRequest(title, docType, iterationId, ownerIds, developerIds, testerIds, null, null), file);
    }

    @PutMapping(value = "/docs/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KbDocEntity updateJson(@PathVariable Long id, @RequestBody KnowledgeService.SaveRequest request) {
        return knowledgeService.update(id, request, null);
    }

    @DeleteMapping("/docs/{id}")
    public void delete(@PathVariable Long id) {
        knowledgeService.delete(id);
    }

    @PostMapping("/docs/{id}/reindex")
    public KbDocEntity reindex(@PathVariable Long id) {
        return knowledgeService.reindex(id);
    }

    @GetMapping("/docs/{id}/chunks")
    public List<RagSearchService.ChunkView> chunks(@PathVariable Long id) {
        return knowledgeService.chunks(id);
    }

    @GetMapping("/docs/{id}/precheck")
    public KnowledgePrecheckService.PrecheckView precheck(@PathVariable Long id) {
        return knowledgeService.precheck(id);
    }

    @PostMapping("/docs/{id}/precheck/retry")
    public KbDocEntity retryPrecheck(@PathVariable Long id) {
        return knowledgeService.retryPrecheck(id);
    }

    @PostMapping("/docs/{id}/precheck/confirm")
    public KbDocEntity confirmPrecheck(@PathVariable Long id, @RequestBody List<PrecheckTexts.Decision> decisions) {
        return knowledgeService.confirmPrecheck(id, decisions);
    }
}
