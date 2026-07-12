package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.dto.SalesContactRequest;
import com.dbgenius.model.entity.SalesContact;

public interface SalesContactService extends IService<SalesContact> {

    SalesContact submit(SalesContactRequest request);
}
