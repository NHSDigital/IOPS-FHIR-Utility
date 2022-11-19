package uk.nhs.nhsdigital.fhir.utility.awsProvider

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.api.MethodOutcome
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.r4.model.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import uk.nhs.nhsdigital.fhir.utility.configuration.FHIRServerProperties
import uk.nhs.nhsdigital.fhir.utility.configuration.MessageProperties
import java.util.*

@Component
class AWSImplementationGuide(val messageProperties: MessageProperties, val awsClient: IGenericClient,
    //sqs: AmazonSQS?,
                             @Qualifier("R4") val ctx: FhirContext,
                             val fhirServerProperties: FHIRServerProperties,
                             val awsAuditEvent: AWSAuditEvent) {

    private val log = LoggerFactory.getLogger("FHIRAudit")


    fun createUpdate(newImplementationGuide: ImplementationGuide): ImplementationGuide? {
        var awsBundle: Bundle? = null
        if (!newImplementationGuide.hasUrl()) throw UnprocessableEntityException("ImplementationGuide has no identifier")
        var nhsIdentifier = newImplementationGuide.url
        if (nhsIdentifier == null) throw UnprocessableEntityException("ImplementationGuide has no identifier")
        var retry = 3
        while (retry > 0) {
            try {

                awsBundle = awsClient!!.search<IBaseBundle>().forResource(ImplementationGuide::class.java)
                    .where(
                        ImplementationGuide.URL.matches().value(nhsIdentifier)
                    )
                    .returnBundle(Bundle::class.java)
                    .execute()
                break
            } catch (ex: Exception) {
                // do nothing
                log.error(ex.message)
                retry--
                if (retry == 0) throw ex
            }
        }


        // This v3esquw data should have been processed into propoer resources so remove
        newImplementationGuide.contained = ArrayList()

        if (awsBundle!!.hasEntry() && awsBundle.entryFirstRep.hasResource()
            && awsBundle.entryFirstRep.hasResource()
            && awsBundle.entryFirstRep.resource is ImplementationGuide
        ) {
            val diagnosticReport = awsBundle.entryFirstRep.resource as ImplementationGuide
            // Dont update for now - just return aws ImplementationGuide
            return update(diagnosticReport, newImplementationGuide)!!.resource as ImplementationGuide
        } else {
            return create(newImplementationGuide)!!.resource as ImplementationGuide
        }
    }

    public fun getImplementationGuide(url : String): ImplementationGuide? {
        var bundle: Bundle? = null
        var retry = 3
        while (retry > 0) {
            try {
                bundle = awsClient
                    .search<IBaseBundle>()
                    .forResource(ImplementationGuide::class.java)
                    .where(
                        ImplementationGuide.URL.matches().value(url)
                    )
                    .returnBundle(Bundle::class.java)
                    .execute()
                break
            } catch (ex: Exception) {
                // do nothing
                log.error(ex.message)
                retry--
                if (retry == 0) throw ex
            }
        }
        if (bundle == null || !bundle.hasEntry()) return null
        return bundle.entryFirstRep.resource as ImplementationGuide
    }

    private fun update(implementationGuide: ImplementationGuide, newImplementationGuide: ImplementationGuide): MethodOutcome? {
        var response: MethodOutcome? = null
        var changed: Boolean

        // TODO do change detection
        changed = true

        if (!changed) return MethodOutcome().setResource(implementationGuide)
        var retry = 3
        while (retry > 0) {
            try {
                newImplementationGuide.id = implementationGuide.idElement.value
                response = awsClient!!.update().resource(newImplementationGuide).withId(implementationGuide.id).execute()
                log.info("AWS ImplementationGuide updated " + response.resource.idElement.value)
                val auditEvent = awsAuditEvent.createAudit(implementationGuide, AuditEvent.AuditEventAction.C)
                awsAuditEvent.writeAWS(auditEvent)
                break
            } catch (ex: Exception) {
                // do nothing
                log.error(ex.message)
                retry--
                if (retry == 0) throw ex
            }
        }
        return response

    }

    private fun create(newImplementationGuide: ImplementationGuide): MethodOutcome? {

        var response: MethodOutcome? = null

        var retry = 3
        while (retry > 0) {
            try {
                response = awsClient
                    .create()
                    .resource(newImplementationGuide)
                    .execute()
                val diagnosticReport = response.resource as ImplementationGuide
                val auditEvent = awsAuditEvent.createAudit(diagnosticReport, AuditEvent.AuditEventAction.C)
                awsAuditEvent.writeAWS(auditEvent)
                break
            } catch (ex: Exception) {
                // do nothing
                log.error(ex.message)
                retry--
                if (retry == 0) throw ex
            }
        }
        return response
    }
}
