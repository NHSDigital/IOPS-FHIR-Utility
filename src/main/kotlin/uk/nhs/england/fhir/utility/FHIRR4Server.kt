package uk.nhs.england.fhir.utility

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.server.RestfulServer
import org.springframework.beans.factory.annotation.Qualifier
import uk.nhs.england.fhir.utility.configuration.FHIRServerProperties
import uk.nhs.england.fhir.utility.interceptor.AWSAuditEventLoggingInterceptor
import uk.nhs.england.fhir.utility.interceptor.CapabilityStatementInterceptor
import uk.nhs.england.fhir.utility.provider.BinaryProvider
import uk.nhs.england.fhir.utility.provider.ImplementationGuideProvider
import java.util.*
import javax.servlet.annotation.WebServlet

@WebServlet("/FHIR/R4/*", loadOnStartup = 1)
class FHIRR4Server (public val fhirServerProperties: FHIRServerProperties,
                    @Qualifier("R4") fhirContext: FhirContext,
                    private val igCacheProvider: ImplementationGuideProvider,
                    private val binaryProvider: BinaryProvider
) : RestfulServer(fhirContext) {
    override fun initialize() {
        super.initialize()

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        registerProvider(igCacheProvider)
        registerProvider(binaryProvider)

        registerInterceptor(CapabilityStatementInterceptor(this.fhirContext,fhirServerProperties))

        val awsAuditEventLoggingInterceptor =
            AWSAuditEventLoggingInterceptor(
                this.fhirContext,
                fhirServerProperties
            )
        interceptorService.registerInterceptor(awsAuditEventLoggingInterceptor)


        isDefaultPrettyPrint = true
        defaultResponseEncoding = EncodingEnum.JSON
    }
}
