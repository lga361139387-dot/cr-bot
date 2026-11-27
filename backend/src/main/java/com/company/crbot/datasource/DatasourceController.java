package com.company.crbot.datasource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/datasources")
public class DatasourceController {

    private final DatasourceService datasourceService;

    public DatasourceController(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    @GetMapping
    public List<DatasourceService.View> list() {
        // 列表不返回密码
        return datasourceService.list();
    }

    @PostMapping("/probe")
    public void probe(@RequestBody DatasourceService.ProbeRequest request) {
        datasourceService.probe(request);
    }

    @PostMapping
    public DatasourceService.View create(@RequestBody DatasourceService.SaveRequest request) {
        return datasourceService.create(request);
    }

    @PutMapping("/{id}")
    public DatasourceService.View update(@PathVariable Long id, @RequestBody DatasourceService.SaveRequest request) {
        return datasourceService.update(id, request);
    }

    @PostMapping("/{id}/test")
    public DatasourceService.View test(@PathVariable Long id) {
        return datasourceService.test(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        datasourceService.delete(id);
    }
}
