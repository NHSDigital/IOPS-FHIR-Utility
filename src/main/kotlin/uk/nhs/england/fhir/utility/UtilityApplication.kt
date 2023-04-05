package uk.nhs.england.fhir.utility

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.ServletComponentScan
import uk.nhs.england.fhir.utility.configuration.FHIRServerProperties
import uk.nhs.england.fhir.utility.configuration.TerminologyValidationProperties

@SpringBootApplication
@ServletComponentScan
@EnableConfigurationProperties(TerminologyValidationProperties::class,FHIRServerProperties::class)
open class UtilityApplication

fun main(args: Array<String>) {
    runApplication<uk.nhs.england.fhir.utility.UtilityApplication>(*args)
}
