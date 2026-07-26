package com.dbgenius.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dbgenius.common.result.R;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.vo.DbConfigVO;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.trial.TrialGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/db-config")
@RequiredArgsConstructor
public class DbConfigController {

    private final DbConfigService dbConfigService;
    private final TrialGuard trialGuard;

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

    /**
     * 手动刷新数据库文档（异步受理，立即返回）。
     *
     * <p>数据库结构变更后调用：服务端先校验归属与试用限制，再通过 MQ 异步
     * 「重新验证连接 + 重新生成文档」，可通过 GET /db-config/{id} 轮询状态。</p>
     */
    @PostMapping("/{id}/refresh-doc")
    public R<String> refreshDoc(@PathVariable Long id) {
        dbConfigService.refreshDoc(StpUtil.getLoginIdAsLong(), id);
        return R.ok("文档刷新任务已受理，正在后台重新验证连接并生成文档");
    }

    @GetMapping("/{id}/doc")
    public R<String> getDoc(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        String content = dbConfigService.getDocContent(userId, id);
        if (trialGuard.isTrialMode() && dbConfigService.isBuiltinConfig(userId, id)) {
            return R.ok("*");
        }
        return R.ok(content);
    }
}
