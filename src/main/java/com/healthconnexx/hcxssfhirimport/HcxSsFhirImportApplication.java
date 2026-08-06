package com.healthconnexx.hcxssfhirimport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// HDC-221: FHIR R4 bundle import from S3 into medication_history schema.
@SpringBootApplication
public class HcxSsFhirImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(HcxSsFhirImportApplication.class, args);
    }
}
