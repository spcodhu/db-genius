package com.dbgenius.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dbgenius.common.result.R;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileUploadService fileUploadService;

    @PostMapping("/upload")
    public R<UploadedFile> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(fileUploadService.uploadFile(StpUtil.getLoginIdAsLong(), file));
    }
}
