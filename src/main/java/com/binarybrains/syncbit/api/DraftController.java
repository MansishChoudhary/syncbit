package com.binarybrains.syncbit.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping("/api/drafts")
    public List<DraftView> list(@RequestParam(required = false, defaultValue = "draft") String status) {
        return draftService.list(status.isBlank() ? null : status);
    }

    @PostMapping("/api/drafts/{id}/approve")
    public void approve(@PathVariable("id") long draftId, @RequestParam(defaultValue = "demo-user") String approvedBy) {
        draftService.approve(draftId, approvedBy);
    }

    @PostMapping("/api/drafts/{id}/reject")
    public void reject(@PathVariable("id") long draftId, @RequestParam(defaultValue = "demo-user") String approvedBy) {
        draftService.reject(draftId, approvedBy);
    }
}
