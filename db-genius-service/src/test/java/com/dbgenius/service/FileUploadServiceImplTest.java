package com.dbgenius.service;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.constant.FileTypes;
import com.dbgenius.service.config.OssProperties;
import com.dbgenius.service.impl.FileUploadServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FileUploadServiceImpl 单元测试：上传扩展名白名单与 20MB 大小上限。
 * 校验均在 OSS 上传之前完成，因此 OssService mock 不应有任何交互。
 * 试用版拒绝已改为 {@code @TrialDeny} 注解 + AOP，不走本类构造器，不在此测试范围内。
 */
class FileUploadServiceImplTest {

    private final OssService ossService = mock(OssService.class);
    private final FileUploadServiceImpl service =
            new FileUploadServiceImpl(ossService, new OssProperties());

    private MultipartFile mockFile(String filename, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    @Test
    void shouldRejectExecutableFile() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.uploadFile(1L, mockFile("evil.exe", 1024)));

        assertTrue(e.getMessage().contains("Unsupported file type"));
        verifyNoInteractions(ossService);
    }

    @Test
    void shouldRejectLegacyDocFile() {
        // .doc 老格式明确不支持（白名单仅含 docx）
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.uploadFile(1L, mockFile("report.doc", 1024)));

        assertTrue(e.getMessage().contains("Unsupported file type"));
        verifyNoInteractions(ossService);
    }

    @Test
    void shouldRejectFileOver20MB() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.uploadFile(1L, mockFile("big.pdf", FileTypes.MAX_FILE_SIZE + 1)));

        assertTrue(e.getMessage().contains("File too large"));
        assertTrue(e.getMessage().contains("20MB"));
        verifyNoInteractions(ossService);
    }
}
