package com.dbgenius.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.dbgenius.service.OssService;
import com.dbgenius.service.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class OssServiceImpl implements OssService {

    private final OSS ossClient;
    private final OssProperties ossProperties;

    @Override
    public String upload(String key, InputStream in, long contentLength, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        if (contentType != null) {
            metadata.setContentType(contentType);
        }
        ossClient.putObject(ossProperties.getBucket(), key, in, metadata);
        return key;
    }

    @Override
    public InputStream download(String key) {
        return ossClient.getObject(ossProperties.getBucket(), key).getObjectContent();
    }

    @Override
    public void delete(String key) {
        ossClient.deleteObject(ossProperties.getBucket(), key);
    }
}
