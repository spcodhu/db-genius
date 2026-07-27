package com.dbgenius.model.constant;

import java.util.Set;

/**
 * 上传文件类型与大小限制，集中定义供上传校验与 agent 文件工具复用。
 */
public final class FileTypes {

    /**
     * 文档类扩展名（不含 .doc 老格式）
     */
    public static final Set<String> DOC_EXTENSIONS = Set.of("xlsx", "xls", "csv", "docx", "pdf", "md");

    /**
     * 图片类扩展名
     */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp");

    /**
     * 单文件大小上限：20MB
     */
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private FileTypes() {
    }

    /**
     * 取文件名的扩展名（小写、不含点），无扩展名返回空串
     */
    public static String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    public static boolean isDocument(String filename) {
        return DOC_EXTENSIONS.contains(getExtension(filename));
    }

    public static boolean isImage(String filename) {
        return IMAGE_EXTENSIONS.contains(getExtension(filename));
    }

    /**
     * 文件名是否在允许上传的白名单内（文档或图片）
     */
    public static boolean isAllowed(String filename) {
        return isDocument(filename) || isImage(filename);
    }
}
