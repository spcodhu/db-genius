package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.entity.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

public interface FileUploadService extends IService<UploadedFile> {

    UploadedFile uploadFile(Long userId, MultipartFile file);

    String getFilePath(Long fileId);
}
