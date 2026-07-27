package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.entity.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface FileUploadService extends IService<UploadedFile> {

    UploadedFile uploadFile(Long userId, MultipartFile file);

    /**
     * 查库并校验属主：文件不存在抛 404，不属于该用户抛 403
     */
    UploadedFile getOwnedFile(Long fileId, Long userId);

    /**
     * 打开文件内容流（委托 OSS），调用方负责关闭
     */
    InputStream openStream(UploadedFile file);
}
