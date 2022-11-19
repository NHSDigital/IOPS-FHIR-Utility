package uk.nhs.nhsdigital.fhir.utility.provider

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.annotation.*
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.IResourceProvider
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import com.google.gson.JsonElement
import mu.KLogging
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion
import org.hl7.fhir.utilities.npm.NpmPackage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import uk.nhs.nhsdigital.fhir.utility.awsProvider.AWSImplementationGuide
import uk.nhs.nhsdigital.fhir.utility.interceptor.CognitoAuthInterceptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class ImplementationGuideProvider(@Qualifier("R4") private val fhirContext: FhirContext,
                                  private val cognitoAuthInterceptor: CognitoAuthInterceptor,
                                  private val awsImplementationGuide: AWSImplementationGuide
) : IResourceProvider {
    companion object : KLogging()

    override fun getResourceType(): Class<ImplementationGuide> {
        return ImplementationGuide::class.java
    }

    @Search
    fun search(
        httpRequest : HttpServletRequest,
        @OptionalParam(name = ImplementationGuide.SP_URL) url: TokenParam?): List<ImplementationGuide> {
        val list = mutableListOf<ImplementationGuide>()
       // var decodeUri = java.net.URLDecoder.decode(url.value, StandardCharsets.UTF_8.name());
        val resource: Resource? = cognitoAuthInterceptor.readFromUrl(httpRequest.pathInfo, httpRequest.queryString)
        if (resource != null && resource is Bundle) {
            for (entry in resource.entry) {
                if (entry.hasResource() && entry.resource is ImplementationGuide) list.add(entry.resource as ImplementationGuide)
            }
        }
        return list
    }

    @Operation(name = "\$cacheIG", idempotent = true)
    fun convertOpenAPI(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @OperationParam(name="name") igNameParameter : String,
        @OperationParam(name="version") igVersionParameter : String
    ) : ImplementationGuide? {
        val implementationGuide = ImplementationGuide()
        var version = igVersionParameter ?: servletRequest.getParameter("version")
        var name = igNameParameter ?: servletRequest.getParameter("name")
        val packages = downloadPackage(name, version)
        for (packageEntry in packages) {
            if ((packageEntry.npm != null) && packageEntry.npm.isJsonObject) {
                 val igDetails=packageEntry.npm
                val igName=igDetails.get("name").asString.replace("\"","")
                val igDesc=igDetails.get("description").asString.replace("\"","")
                val igVersion = igDetails.get("version").asString.replace("\"","")
                val igAuthor=igDetails.get("author").asString.replace("\"","")
                if (igVersion.equals(version) && igName.equals(name)) {
                    logger.info("Found it")
                    implementationGuide.url="https://fhir.nhs.uk/ImplementationGuide/"+name+"-"+version
                    implementationGuide.description = igDesc
                    implementationGuide.name=name
                    implementationGuide.version=version
                    implementationGuide.copyright=igAuthor
                    implementationGuide.status = Enumerations.PublicationStatus.ACTIVE
                    implementationGuide.packageId=igName
                } else if (igName.equals("hl7.fhir.r4.core")) {
                    //implementationGuide.fhirVersion.add("4.0.0")
                } else {
                    implementationGuide.dependsOn.add(
                        ImplementationGuide.ImplementationGuideDependsOnComponent()
                            .setUri("https://fhir.nhs.uk/ImplementationGuide/"+igName+"-"+igVersion)
                            .setVersion(igVersion)
                            .setPackageId(igName)
                    )
                }
            }
        }
        val outcome = awsImplementationGuide.createUpdate(implementationGuide)
        if (outcome != null) return outcome
        return null
    }

    open fun downloadPackage(name : String, version : String) : List<NpmPackage> {
        logger.info("Downloading {} - {}",name, version)
        val inputStream = readFromUrl("https://packages.simplifier.net/"+name+"/"+version)
        val packages = arrayListOf<NpmPackage>()
        val npmPackage = NpmPackage.fromPackage(inputStream)

        val dependency= npmPackage.npm.get("dependencies")

        if (dependency.isJsonArray) logger.info("isJsonArray")
        if (dependency.isJsonObject) {
            logger.info("isJsonObject")
            val obj = dependency.asJsonObject

            val entrySet: Set<Map.Entry<String?, JsonElement?>> = obj.entrySet()
            for (entry in entrySet) {
                logger.info(entry.key + " version =  " + entry.value)
                if (entry.key != "hl7.fhir.r4.core") {
                    val version = entry.value?.asString?.replace("\"","")
                   if (entry.key != null && version != null) {
                       val packs = downloadPackage(entry.key!!, version)
                       if (packs.size > 0) {
                           for (pack in packs) {
                               packages.add(pack)
                           }
                       }
                   }
                }
            }
        }
        packages.add(npmPackage)
        if (dependency.isJsonNull) logger.info("isNull")
        if (dependency.isJsonPrimitive) logger.info("isJsonPrimitive")

        return packages
    }
    fun readFromUrl(url: String): InputStream {

        var myUrl: URL =  URL(url)
        var retry = 2
        while (retry > 0) {
            val conn = myUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            try {
                conn.connect()
                return conn.inputStream
            } catch (ex: FileNotFoundException) {
                null
                retry--
                if (retry < 1) throw UnprocessableEntityException(ex.message)
            } catch (ex: IOException) {
                retry--
                if (retry < 1) throw UnprocessableEntityException(ex.message)

            }
        }
        throw UnprocessableEntityException("Number of retries exhausted")
    }
}
