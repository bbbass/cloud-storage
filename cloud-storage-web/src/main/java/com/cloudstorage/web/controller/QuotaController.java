package com.cloudstorage.web.controller;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.result.Result;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.quota.dto.QuotaView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配额查询：前端用来展示"已用 / 上限 / 剩余"。写路径由上传链路内部驱动。
 */
@RestController
@RequestMapping("/api/v1/quota")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;

    @GetMapping
    public Result<QuotaView> quota() {
        return Result.ok(quotaService.getQuota(UserContext.userId()));
    }
}
