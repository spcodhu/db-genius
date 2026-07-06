package com.dbgenius.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbgenius.model.entity.DbConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DbConfigMapper extends BaseMapper<DbConfig> {
}
