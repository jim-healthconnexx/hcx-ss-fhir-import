package com.healthconnexx.hcxssfhirimport.model;

/** HDC-221: Result of one FHIR import run — counts across all processed files. */
public record ImportResult(int totalFiles, int successCount, int errorCount) {}
