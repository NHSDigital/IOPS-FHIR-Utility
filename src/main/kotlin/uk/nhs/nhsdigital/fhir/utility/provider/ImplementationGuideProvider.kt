package uk.nhs.nhsdigital.fhir.utility.provider

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.context.support.IValidationSupport
import ca.uhn.fhir.context.support.ValidationSupportContext
import ca.uhn.fhir.rest.annotation.*
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.IResourceProvider
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException
import com.google.gson.JsonElement
import kotlinx.coroutines.*
import mu.KLogging
import org.hl7.fhir.r4.model.*
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
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import uk.nhs.nhsdigital.fhir.utility.service.ImplementationGuideParser
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext

@Component
class ImplementationGuideProvider(@Qualifier("R4") private val fhirContext: FhirContext,
                                  private val cognitoAuthInterceptor: CognitoAuthInterceptor,
                                  private val awsImplementationGuide: AWSImplementationGuide,
                                  private val implementationGuideParser: ImplementationGuideParser
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
    ) : ImplementationGuide?  {
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
                    val dependentPackageUrl = "https://fhir.nhs.uk/ImplementationGuide/"+igName+"-"+igVersion
                    implementationGuide.dependsOn.add(
                        ImplementationGuide.ImplementationGuideDependsOnComponent()
                            .setUri(dependentPackageUrl)
                            .setVersion(igVersion)
                            .setPackageId(igName)
                    )
                    val dependentIG = awsImplementationGuide.get(dependentPackageUrl)
                    if (dependentIG == null) throw UnprocessableEntityException("Dependent IG ("+dependentPackageUrl+") must be processed beforehand. Aborted")
                    if (dependentIG.getExtensionByUrl("https://fhir.nhs.uk/StructureDefinition/Extension-IGPackage") == null){
                        throw UnprocessableEntityException("Dependent package ("+dependentPackageUrl+") has not been processed beforehand or is being processed. Aborted")
                    }
                }
            }
        }
        val outcome = awsImplementationGuide.createUpdate(implementationGuide)
        if (outcome != null) {

            // Fire (FHIR) and forget

            GlobalScope.launch {
                expandIg(name, version, packages)
            }


            return outcome
        }
        return null
    }


    private fun expandIg(name: String?, version: String?, packages: List<NpmPackage>) {
        println("delay started")
        //  delay(123123)
        val supportChain = ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext),
            SnapshotGeneratingValidationSupport(fhirContext),
        )
        packages.map(implementationGuideParser::createPrePopulatedValidationSupport)
            .forEach(supportChain::addValidationSupport)
        generateSnapshots(supportChain)
        println("Delay called")
    }

    fun generateSnapshots(supportChain: IValidationSupport) {
        val structureDefinitions = supportChain.fetchAllStructureDefinitions<StructureDefinition>() ?: return
        val context = ValidationSupportContext(supportChain)
        structureDefinitions
            .filter { shouldGenerateSnapshot(it) }
            .forEach {
                try {
                    circularReferenceCheck(it,supportChain)
                } catch (e: Exception) {
                    logger.error("Failed to generate snapshot for $it", e)
                }
            }

        structureDefinitions
            .filter { shouldGenerateSnapshot(it) }
            .forEach {
                try {
                    val start: Instant = Instant.now()
                    supportChain.generateSnapshot(context, it, it.url, "https://fhir.nhs.uk/R4", it.name)
                    val end: Instant = Instant.now()
                    val duration: Duration = Duration.between(start, end)
                    logger.info(duration.toMillis().toString() + " ms $it")
                } catch (e: Exception) {
                    logger.error("Failed to generate snapshot for $it", e)
                }
            }
    }

    private fun circularReferenceCheck(structureDefinition: StructureDefinition, supportChain: IValidationSupport): StructureDefinition {
        if (structureDefinition.hasSnapshot()) logger.error(structureDefinition.url + " has snapshot!!")
        structureDefinition.differential.element.forEach{
            //   ||
            if ((
                        it.id.endsWith(".partOf") ||
                                it.id.endsWith(".basedOn") ||
                                it.id.endsWith(".replaces") ||
                                it.id.contains("Condition.stage.assessment") ||
                                it.id.contains("Observation.derivedFrom") ||
                                it.id.contains("Observation.hasMember") ||
                                it.id.contains("CareTeam.encounter") ||
                                it.id.contains("CareTeam.reasonReference") ||
                                it.id.contains("ServiceRequest.encounter") ||
                                it.id.contains("ServiceRequest.reasonReference") ||
                                it.id.contains("EpisodeOfCare.diagnosis.condition") ||
                                it.id.contains("Encounter.diagnosis.condition") ||
                                it.id.contains("Encounter.reasonReference")
                        )
                && it.hasType()) {
                logger.warn(structureDefinition.url + " has circular references ("+ it.id + ")")
                it.type.forEach{
                    if (it.hasTargetProfile())
                        it.targetProfile.forEach {
                            it.value = getBase(it.value, supportChain);
                        }
                }
            }
        }
        return structureDefinition
    }

    private fun getBase(profile : String,supportChain: IValidationSupport): String? {
        val structureDefinition : StructureDefinition=
            supportChain.fetchStructureDefinition(profile) as StructureDefinition;
        if (structureDefinition.hasBaseDefinition()) {
            var baseProfile = structureDefinition.baseDefinition
            if (baseProfile.contains(".uk")) baseProfile = getBase(baseProfile, supportChain)
            return baseProfile
        }
        return null;
    }
    private fun shouldGenerateSnapshot(structureDefinition: StructureDefinition): Boolean {
        return !structureDefinition.hasSnapshot() && structureDefinition.derivation == StructureDefinition.TypeDerivationRule.CONSTRAINT
    }
    open fun downloadPackage(name : String, version : String) : List<NpmPackage> {
        logger.info("Downloading {} - {}",name, version)
        val inputStream = readFromUrl("https://packages.simplifier.net/"+name+"/"+version)
        val packages = arrayListOf<NpmPackage>()
        val npmPackage = NpmPackage.fromPackage(inputStream)

        val dependency= npmPackage.npm.get("dependencies")

        if (dependency.isJsonObject) {

            val obj = dependency.asJsonObject

            val entrySet: Set<Map.Entry<String?, JsonElement?>> = obj.entrySet()
            for (entry in entrySet) {

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
