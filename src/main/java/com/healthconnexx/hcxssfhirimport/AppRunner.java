package com.healthconnexx.hcxssfhirimport;

import com.healthconnexx.hcxssfhirimport.model.ImportResult;
import com.healthconnexx.hcxssfhirimport.service.FhirImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * HDC-221: Runs FHIR import once on startup and exits.
 * Called as a one-shot ECS task by hcs-ss-fhir-processor.
 */
@Slf4j
@Component
public class AppRunner implements ApplicationRunner {

    private final FhirImportService fhirImportService;
    private final ApplicationContext applicationContext;

    public AppRunner(FhirImportService fhirImportService, ApplicationContext applicationContext) {
        this.fhirImportService = fhirImportService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("HDC-221: AppRunner started — beginning FHIR import.");
        try {
            ImportResult result = fhirImportService.processAll();
            log.info("HDC-221: FHIR import complete. total={} success={} errors={}",
                    result.totalFiles(), result.successCount(), result.errorCount());

            // HDC-221: Exit non-zero only if every file failed AND there were files to process.
            int exitCode = (result.totalFiles() > 0 && result.successCount() == 0) ? 1 : 0;
            exitApplication(exitCode);
        } catch (Exception e) {
            log.error("HDC-221: Unhandled exception during FHIR import; exiting with error.", e);
            exitApplication(1);
        }
    }

    private void exitApplication(int code) {
        int exitCode = SpringApplication.exit(applicationContext, () -> code);
        System.exit(exitCode);
    }
}
