package com.dbgenius.controller;

import com.dbgenius.common.result.R;
import com.dbgenius.model.dto.SalesContactRequest;
import com.dbgenius.model.entity.SalesContact;
import com.dbgenius.service.SalesContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联系销售接口，供试用版用户提交商务咨询。
 * 该接口无需登录即可访问。
 */
@RestController
@RequestMapping("/sales")
@RequiredArgsConstructor
public class SalesContactController {

    private final SalesContactService salesContactService;

    @PostMapping("/contact")
    public R<SalesContact> contact(@Valid @RequestBody SalesContactRequest request) {
        return R.ok(salesContactService.submit(request));
    }
}
