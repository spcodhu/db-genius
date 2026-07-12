package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系销售留言实体。
 */
@Data
@TableName("sales_contact")
public class SalesContact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String companyName;

    private String contact;

    private String remark;

    private LocalDateTime createdAt;
}
