package uk.nhs.england.fhir.utility.interceptor

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Interceptor
import ca.uhn.fhir.interceptor.api.Pointcut
import org.hl7.fhir.instance.model.api.IBaseConformance
import org.hl7.fhir.r4.model.*
import uk.nhs.england.fhir.utility.configuration.FHIRServerProperties

@Interceptor
class CapabilityStatementInterceptor(
    fhirContext: FhirContext,
    private val fhirServerProperties: FHIRServerProperties
) {

    @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
    fun customize(theCapabilityStatement: IBaseConformance) {

        // Cast to the appropriate version
        val cs: CapabilityStatement = theCapabilityStatement as CapabilityStatement

        // Customize the CapabilityStatement as desired
        val apiextension = Extension();
        apiextension.url = "https://fhir.nhs.uk/StructureDefinition/Extension-NHSDigital-CapabilityStatement-Package"

        val packageExtension = Extension();
        packageExtension.url="openApi"
        packageExtension.extension.add(Extension().setUrl("documentation").setValue(UriType("https://simplifier.net/guide/NHSDigital/Home")))
        packageExtension.extension.add(Extension().setUrl("description").setValue(StringType("NHS Digital FHIR Implementation Guide")))
        apiextension.extension.add(packageExtension)
        cs.extension.add(apiextension)

        cs.name = fhirServerProperties.server.name
        cs.software.name = fhirServerProperties.server.name
        cs.software.version = fhirServerProperties.server.version
        cs.publisher = "NHS Digital"
        cs.implementation.url = "https://simplifier.net/guide/nhsdigital"
        cs.implementation.description = "NHS Digital FHIR Implementation Guide"
    }

    fun getResourceComponent(type : String, cs : CapabilityStatement ) : CapabilityStatement.CapabilityStatementRestResourceComponent? {
        for (rest in cs.rest) {
            for (resource in rest.resource) {
                // println(type + " - " +resource.type)
                if (resource.type.equals(type))
                    return resource
            }
        }
        return null
    }


}
