package com.healthconnexx.hcxssfhirimport.controller;

import com.healthconnexx.hcxssfhirimport.model.ImportResult;
import com.healthconnexx.hcxssfhirimport.service.FhirImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// HDC-221: @Deprecated — primary trigger is the ECS AppRunner (one-shot task pattern).
// Retained as an emergency manual trigger. Remove in a follow-up cleanup ticket.
@Deprecated
@RestController
@RequestMapping("/api/v1/fhir")
@RequiredArgsConstructor
public class FhirImportController {

    private final FhirImportService fhirImportService;

    @PostMapping("/import")
    public ResponseEntity<ImportResult> triggerImport() {
        return ResponseEntity.ok(fhirImportService.processAll());
    }
}
