package com.dbgenius.controller;

import com.dbgenius.common.result.R;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 试用版状态查询接口，供前端判断当前环境是否为试用版。
 */
@RestController
@RequestMapping("/trial")
@RequiredArgsConstructor
public class TrialController {

    private final TrialGuard trialGuard;

    @GetMapping("/status")
    public R<Map<String, Boolean>> status() {
        return R.ok(Map.of("trialEnabled", trialGuard.isTrialMode()));
    }
}
