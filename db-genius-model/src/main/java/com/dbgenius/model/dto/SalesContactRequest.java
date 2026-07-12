package com.dbgenius.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 联系销售留言请求。
 */
@Data
public class SalesContactRequest {

    @NotBlank(message = "公司名称不能为空")
    @Size(max = 256, message = "公司名称长度不能超过 256 个字符")
    private String companyName;

    @NotBlank(message = "联系方式不能为空")
    @Size(max = 256, message = "联系方式长度不能超过 256 个字符")
    private String contact;

    @Size(max = 4000, message = "备注长度不能超过 4000 个字符")
    private String remark;
}
