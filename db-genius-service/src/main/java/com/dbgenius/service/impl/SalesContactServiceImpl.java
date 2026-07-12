package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.mapper.SalesContactMapper;
import com.dbgenius.model.dto.SalesContactRequest;
import com.dbgenius.model.entity.SalesContact;
import com.dbgenius.service.SalesContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesContactServiceImpl extends ServiceImpl<SalesContactMapper, SalesContact> implements SalesContactService {

    @Override
    public SalesContact submit(SalesContactRequest request) {
        SalesContact contact = new SalesContact();
        contact.setCompanyName(request.getCompanyName());
        contact.setContact(request.getContact());
        contact.setRemark(request.getRemark());
        save(contact);
        log.info("Sales contact submitted: company={}, contact={}", request.getCompanyName(), request.getContact());
        return contact;
    }
}
