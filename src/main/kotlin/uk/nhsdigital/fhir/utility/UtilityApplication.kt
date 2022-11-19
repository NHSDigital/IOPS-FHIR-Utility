package uk.nhsdigital.fhir.utility

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.ServletComponentScan
import uk.nhsdigital.fhir.utility.configuration.FHIRServerProperties

@SpringBootApplication
@ServletComponentScan
@EnableConfigurationProperties(FHIRServerProperties::class)
open class UtilityApplication

fun main(args: Array<String>) {
    runApplication<UtilityApplication>(*args)
}
