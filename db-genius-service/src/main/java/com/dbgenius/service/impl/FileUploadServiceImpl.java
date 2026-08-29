package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.mapper.UploadedFileMapper;
import com.dbgenius.model.constant.FileTypes;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import com.dbgenius.service.OssService;
import com.dbgenius.service.config.OssProperties;
import com.dbgenius.trial.TrialDeny;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl extends ServiceImpl<UploadedFileMapper, UploadedFile> implements FileUploadService {

    private final OssService ossService;
    private final OssProperties ossProperties;

    @Override
    @TrialDeny(ErrorCode.TRIAL_FILE_UPLOAD)
    public UploadedFile uploadFile(Long userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }

        String originalName = file.getOriginalFilename();
        if (!FileTypes.isAllowed(originalName)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    FileTypes.DOC_EXTENSIONS, FileTypes.IMAGE_EXTENSIONS);
        }
        if (file.getSize() > FileTypes.MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, FileTypes.MAX_FILE_SIZE / 1024 / 1024);
        }

        String dirPrefix = ossProperties.getDirPrefix();
        if (!dirPrefix.endsWith("/")) {
            dirPrefix += "/";
        }
        String ossKey = dirPrefix + userId + "/" + UUID.randomUUID() + "." + FileTypes.getExtension(originalName);

        try (InputStream in = file.getInputStream()) {
            ossService.upload(ossKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, e.getMessage());
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
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        if (!file.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FILE_NO_PERMISSION);
        }
        return file;
    }

    @Override
    public InputStream openStream(UploadedFile file) {
        return ossService.download(file.getOssKey());
    }
}
