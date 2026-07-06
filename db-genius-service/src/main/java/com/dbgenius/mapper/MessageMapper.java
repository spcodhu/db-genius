package com.dbgenius.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dbgenius.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
