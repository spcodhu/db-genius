package com.dbgenius.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dbgenius.common.result.R;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.vo.DbConfigVO;
import com.dbgenius.service.DbConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/db-config")
@RequiredArgsConstructor
public class DbConfigController {

    private final DbConfigService dbConfigService;

    @PostMapping
    public R<DbConfigVO> create(@Valid @RequestBody DbConfigRequest request) {
        return R.ok(dbConfigService.createConfig(StpUtil.getLoginIdAsLong(), request));
    }

    @PutMapping("/{id}")
    public R<DbConfigVO> update(@PathVariable Long id, @Valid @RequestBody DbConfigRequest request) {
        return R.ok(dbConfigService.updateConfig(StpUtil.getLoginIdAsLong(), id, request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dbConfigService.deleteConfig(StpUtil.getLoginIdAsLong(), id);
        return R.ok();
    }

    @GetMapping
    public R<List<DbConfigVO>> list() {
        return R.ok(dbConfigService.listConfigs(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{id}")
    public R<DbConfigVO> get(@PathVariable Long id) {
        return R.ok(dbConfigService.getConfig(StpUtil.getLoginIdAsLong(), id));
    }

    @PostMapping("/{id}/test")
    public R<Boolean> testConnection(@PathVariable Long id) {
        return R.ok(dbConfigService.testConnection(StpUtil.getLoginIdAsLong(), id));
    }

    @PostMapping("/{id}/generate-doc")
    public R<String> generateDoc(@PathVariable Long id) {
        return R.ok(dbConfigService.generateDoc(StpUtil.getLoginIdAsLong(), id));
    }

    @GetMapping("/{id}/doc")
    public R<String> getDoc(@PathVariable Long id) {
        return R.ok(dbConfigService.getDocContent(StpUtil.getLoginIdAsLong(), id));
    }
}
