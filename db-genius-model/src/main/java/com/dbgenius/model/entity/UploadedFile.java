package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("uploaded_file")
public class UploadedFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String originalName;

    private String storedPath;

    private Long fileSize;

    private String contentType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
