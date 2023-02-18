package uk.nhs.england.fhir.utility.provider

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.annotation.*
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.IResourceProvider
import kotlinx.coroutines.*
import mu.KLogging
import org.hl7.fhir.r4.model.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import uk.nhs.england.fhir.utility.awsProvider.AWSBinary
import uk.nhs.england.fhir.utility.interceptor.CognitoAuthInterceptor
import java.io.*
import java.util.*
import javax.servlet.http.HttpServletRequest


@Component
class BinaryProvider(@Qualifier("R4") private val fhirContext: FhirContext,
                     private val cognitoAuthInterceptor: CognitoAuthInterceptor,
                     private val awsBinary: AWSBinary,

) : IResourceProvider {
    companion object : KLogging()

    override fun getResourceType(): Class<Binary> {
        return Binary::class.java
    }

    @Read
    fun read(httpRequest : HttpServletRequest, @IdParam internalId: IdType): Binary? {
        return awsBinary.get(internalId)
    }

}
