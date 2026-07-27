package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.mapper.UploadedFileMapper;
import com.dbgenius.model.constant.FileTypes;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import com.dbgenius.service.OssService;
import com.dbgenius.service.config.OssProperties;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl extends ServiceImpl<UploadedFileMapper, UploadedFile> implements FileUploadService {

    private final TrialGuard trialGuard;
    private final OssService ossService;
    private final OssProperties ossProperties;

    @Override
    public UploadedFile uploadFile(Long userId, MultipartFile file) {
        trialGuard.denyIfTrial("试用版暂不支持文件上传");
        if (file.isEmpty()) {
            throw new BusinessException("File is empty");
        }

        String originalName = file.getOriginalFilename();
        if (!FileTypes.isAllowed(originalName)) {
            throw new BusinessException("Unsupported file type, allowed: documents "
                    + FileTypes.DOC_EXTENSIONS + ", images " + FileTypes.IMAGE_EXTENSIONS);
        }
        if (file.getSize() > FileTypes.MAX_FILE_SIZE) {
            throw new BusinessException("File too large, max " + (FileTypes.MAX_FILE_SIZE / 1024 / 1024) + "MB");
        }

        String dirPrefix = ossProperties.getDirPrefix();
        if (!dirPrefix.endsWith("/")) {
            dirPrefix += "/";
        }
        String ossKey = dirPrefix + userId + "/" + UUID.randomUUID() + "." + FileTypes.getExtension(originalName);

        try (InputStream in = file.getInputStream()) {
            ossService.upload(ossKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BusinessException("File upload failed: " + e.getMessage());
        }

        UploadedFile uploadedFile = new UploadedFile();
        uploadedFile.setUserId(userId);
        uploadedFile.setOriginalName(originalName);
        uploadedFile.setOssKey(ossKey);
        uploadedFile.setFileSize(file.getSize());
        uploadedFile.setContentType(file.getContentType());
        save(uploadedFile);

        return uploadedFile;
    }

    @Override
    public UploadedFile getOwnedFile(Long fileId, Long userId) {
        UploadedFile file = getById(fileId);
        if (file == null) {
            throw new BusinessException(404, "File not found");
        }
        if (!file.getUserId().equals(userId)) {
            throw new BusinessException(403, "No permission to access this file");
        }
        return file;
    }

    @Override
    public InputStream openStream(UploadedFile file) {
        return ossService.download(file.getOssKey());
    }
}
