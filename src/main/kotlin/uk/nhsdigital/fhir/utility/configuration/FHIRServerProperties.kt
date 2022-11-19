package uk.nhsdigital.fhir.utility.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = "fhir")
data class FHIRServerProperties(
    var server: Server,
    var igC: String?,
    var ig: String?
) {
    data class Server(
        var baseUrl: String,
        var name: String,
        var version: String
    )
}
