package com.dbgenius.model.vo;

import com.dbgenius.model.entity.UploadedFile;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传文件对外视图，不外泄 ossKey。
 */
@Data
public class UploadedFileVO {

    private Long id;
    private String originalName;
    private Long fileSize;
    private String contentType;
    private LocalDateTime createdAt;

    public static UploadedFileVO from(UploadedFile file) {
        UploadedFileVO vo = new UploadedFileVO();
        vo.setId(file.getId());
        vo.setOriginalName(file.getOriginalName());
        vo.setFileSize(file.getFileSize());
        vo.setContentType(file.getContentType());
        vo.setCreatedAt(file.getCreatedAt());
        return vo;
    }
}
