package com.dbgenius.service;

import java.io.InputStream;

/**
 * 阿里云 OSS 对象存储。
 */
public interface OssService {

    /**
     * 流式上传对象，返回对象 key
     */
    String upload(String key, InputStream in, long contentLength, String contentType);

    /**
     * 打开对象内容流，调用方负责关闭
     */
    InputStream download(String key);

    /**
     * 删除对象
     */
    void delete(String key);
}
