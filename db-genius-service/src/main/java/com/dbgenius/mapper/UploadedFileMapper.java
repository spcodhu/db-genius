package com.dbgenius.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbgenius.model.entity.UploadedFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadedFileMapper extends BaseMapper<UploadedFile> {
}
