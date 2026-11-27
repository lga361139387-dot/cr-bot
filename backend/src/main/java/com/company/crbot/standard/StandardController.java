package com.company.crbot.standard;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/standards")
public class StandardController {

    private final StandardService standardService;

    public StandardController(StandardService standardService) {
        this.standardService = standardService;
    }

    @GetMapping
    public List<StandardDocEntity> list() {
        return standardService.list();
    }

    @GetMapping("/{id}")
    public StandardDocEntity get(@PathVariable Long id) {
        return standardService.get(id);
    }

    @GetMapping("/{id}/rules")
    public StandardService.RulesView rules(@PathVariable Long id) {
        return standardService.rules(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StandardDocEntity upload(@RequestParam String title, @RequestParam String version,
                                    @RequestParam("file") MultipartFile file) {
        return standardService.upload(title, version, file);
    }

    @PostMapping("/{id}/activate")
    public StandardDocEntity activate(@PathVariable Long id) {
        return standardService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public StandardDocEntity deactivate(@PathVariable Long id) {
        return standardService.deactivate(id);
    }

    @PostMapping("/{id}/rules/{code}/activate")
    public StandardService.RulesView activateRule(@PathVariable Long id, @PathVariable String code) {
        return standardService.setRuleEnabled(id, code, true);
    }

    @PostMapping("/{id}/rules/{code}/deactivate")
    public StandardService.RulesView deactivateRule(@PathVariable Long id, @PathVariable String code) {
        return standardService.setRuleEnabled(id, code, false);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        standardService.delete(id);
    }

    @PostMapping("/{id}/reindex")
    public StandardDocEntity reindex(@PathVariable Long id) {
        return standardService.reindex(id);
    }
}
