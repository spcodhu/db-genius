package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.mapper.UploadedFileMapper;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.service.FileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl extends ServiceImpl<UploadedFileMapper, UploadedFile> implements FileUploadService {

    @Value("${db-genius.file-upload-dir}")
    private String uploadDir;

    @Override
    public UploadedFile uploadFile(Long userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("File is empty");
        }

        try {
            Path dirPath = Paths.get(uploadDir);
            Files.createDirectories(dirPath);

            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf("."));
            }
            String storedName = UUID.randomUUID() + extension;
            Path storedPath = dirPath.resolve(storedName);
            file.transferTo(storedPath.toFile());

            UploadedFile uploadedFile = new UploadedFile();
            uploadedFile.setUserId(userId);
            uploadedFile.setOriginalName(originalName);
            uploadedFile.setStoredPath(storedPath.toString());
            uploadedFile.setFileSize(file.getSize());
            uploadedFile.setContentType(file.getContentType());
            save(uploadedFile);

            return uploadedFile;
        } catch (IOException e) {
            throw new BusinessException("File upload failed: " + e.getMessage());
        }
    }

    @Override
    public String getFilePath(Long fileId) {
        UploadedFile file = getById(fileId);
        if (file == null) {
            throw new BusinessException(404, "File not found");
        }
        return file.getStoredPath();
    }
}
