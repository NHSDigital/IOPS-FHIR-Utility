package uk.nhs.england.fhir.utility.provider

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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.utilities.json.model.JsonProperty
import org.hl7.fhir.utilities.npm.NpmPackage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import uk.nhs.england.fhir.utility.awsProvider.AWSBinary
import uk.nhs.england.fhir.utility.awsProvider.AWSImplementationGuide
import uk.nhs.england.fhir.utility.configuration.FHIRServerProperties
import uk.nhs.england.fhir.utility.interceptor.CognitoAuthInterceptor
import uk.nhs.england.fhir.utility.service.ImplementationGuideParser
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


@Component
class ImplementationGuideProvider(@Qualifier("R4") private val fhirContext: FhirContext,
                                  private val cognitoAuthInterceptor: CognitoAuthInterceptor,
                                  private val awsImplementationGuide: AWSImplementationGuide,
                                  private val awsBinary: AWSBinary,
                                  private val implementationGuideParser: ImplementationGuideParser,
                                  private val fhirServerProperties: FHIRServerProperties
) : IResourceProvider {
    companion object : KLogging()

    val IG_EXTENSION = "https://fhir.nhs.uk/StructureDefinition/Extension-IGPackage"

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

    @Operation(name = "\$package", idempotent = true, manualResponse = true)
    fun packageGet(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @OperationParam(name="url") igPackage : String
    )
    {
        val implementationGuide = awsImplementationGuide.get(igPackage)
        if (implementationGuide == null) throw UnprocessableEntityException("ImplementationGuide not found")
        if (implementationGuide.getExtensionByUrl(IG_EXTENSION) == null){
            throw UnprocessableEntityException("Dependent package ("+igPackage+") has not been processed beforehand or is being processed. Aborted")
        }
        val igExtValue = implementationGuide.getExtensionByUrl(IG_EXTENSION).value
        if (igExtValue == null || !(igExtValue is Reference) ) throw UnprocessableEntityException("Malformed ImplementationGuide extension")
        val json = cognitoAuthInterceptor.getBinaryLocation("/"+(igExtValue as Reference).reference)

        val preSignedUrl = json.getString("presignedGetUrl")
        val connection = cognitoAuthInterceptor.getBinary(preSignedUrl)
        if (connection!=null ) {
            servletResponse.setContentType(connection.getHeaderField("Content-Type"))
            servletResponse.setCharacterEncoding("UTF-8")
           // servletResponse.setContentLength(inputStream.)
            val buffer = ByteArray(20 * 1024)
            val outputStream = servletResponse.getOutputStream()
            while (true) {
                val readSize: Int = connection.inputStream.read(buffer)
                if (readSize == -1) break
                    outputStream.write(buffer, 0, readSize)
            }
        }
      //  servletResponse.writer.flush()
    }
    @Operation(name = "\$cacheIG", idempotent = true)
    fun cacheIG(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        @OperationParam(name="name") igNameParameter : String,
        @OperationParam(name="version") igVersionParameter : String
    ) : ImplementationGuide?  {
        val implementationGuide = ImplementationGuide()
        var version = igVersionParameter ?: servletRequest.getParameter("version")
        var name = igNameParameter ?: servletRequest.getParameter("name")
        var packages = downloadPackage(name, version)
        for (packageEntry in packages) {
            if ((packageEntry.npm != null) && packageEntry.npm.isJsonObject) {
                 val igDetails=packageEntry.npm
                val igName=igDetails.get("name").asString().replace("\"","")
                val igDesc=igDetails.get("description").asString().replace("\"","")
                val igVersion = igDetails.get("version").asString().replace("\"","")
                val igAuthor=igDetails.get("author").asString().replace("\"","")
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
                    if (dependentIG.getExtensionByUrl(IG_EXTENSION) == null){
                        throw UnprocessableEntityException("Dependent package ("+dependentPackageUrl+") has not been processed beforehand or is being processed. Aborted")
                    }
                }
            }
        }
        val implementationGuideNew = awsImplementationGuide.createUpdate(implementationGuide)
        if (implementationGuideNew != null) {

            // Fire (FHIR) and forget
            if (implementationGuideNew.getExtensionByUrl(IG_EXTENSION) != null){
                throw UnprocessableEntityException("Package "+implementationGuideNew.url+" has already been processed. Aborted")
            }
            GlobalScope.launch {
                expandIg(implementationGuideNew, name, version, packages)
                packages = emptyList()
            }
            return implementationGuideNew
        }

        return null
    }


    private fun expandIg(implementationGuide: ImplementationGuide, name: String?, version: String?, packages: List<NpmPackage>) {
        println("delay started")
        //  delay(123123)
        var supportChain = ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext),
            SnapshotGeneratingValidationSupport(fhirContext),
        )
        packages.map(implementationGuideParser::createPrePopulatedValidationSupport)
            .forEach(supportChain::addValidationSupport)
        generateSnapshots(supportChain)

        for (npmPackage in packages) {
            if (npmPackage.name().equals(name) && npmPackage.version().equals(version)) {
                val jsonParser = fhirContext.newJsonParser()
                for (folderName in npmPackage.folders) {
                    val folder = folderName.value
                    println("Folder "+folder.name)
                    val removeList = mutableMapOf<String,StructureDefinition>()
                    // Build list of profiles to be removed
                    for(file in folder.listFiles()){
                        val content = npmPackage.load(folderName.key, file)
                        val resource = jsonParser.parseResource(content)
                        if (resource is StructureDefinition) {
                            println("Adding to remove list "+resource.url)
                            removeList.put(file,resource)
                        } else {
                            println("Skipping " + file)
                        }
                    }
                    for (entry in removeList) {
                        // Remove old entry
                        folder.removeFile(entry.key)
                        // Add new entry
                        val content = jsonParser.encodeResourceToString(supportChain.fetchStructureDefinition(entry.value.url))
                        if (content == null) throw UnprocessableEntityException("Error encoding "+entry.key)
                        println("Re-adding  " + entry.key)
                        npmPackage.addFile(folder.name,entry.key,content.toByteArray(),"StructureDefinition")
                    }
                }

                // Saves the processed package
                npmPackage.save(File("package"))
                // Follow same convention as simplifier
                try {
                    Files.move(
                        File("package/" + name + "/examples").toPath(),
                        File("package/" + name + "/package/examples").toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (ex : Exception) {
                    logger.warn(ex.message);
                }
                val outputFilename=name + "-" + version + ".tgz"
                CreateTarGZ("package/" + name,outputFilename )
                val outcome = awsBinary.create(outputFilename)
                if (outcome != null && outcome.resource != null && outcome.resource is Binary) {
                    var binary : Binary = outcome.resource as Binary
                    implementationGuide.addExtension(
                        Extension()
                            .setUrl(IG_EXTENSION)
                            .setValue(Reference()
                                .setReference("Binary/"+binary.id))
                    )
                    awsImplementationGuide.createUpdate(implementationGuide)
                }
                FileUtils.deleteDirectory(File("package"));
                val file = File(outputFilename).delete()
            }
        }
        // Try to empty chain
        supportChain = ValidationSupportChain()

    }

    @Throws(IOException::class)
    fun CreateTarGZ(inputDirectoryPath: String?, outputPath: String?) {
        val inputFile = File(inputDirectoryPath)
        val outputFile = File(outputPath)
        FileOutputStream(outputFile).use { fileOutputStream ->
            BufferedOutputStream(fileOutputStream).use { bufferedOutputStream ->
                GzipCompressorOutputStream(bufferedOutputStream).use { gzipOutputStream ->
                    TarArchiveOutputStream(gzipOutputStream).use { tarArchiveOutputStream ->
                        tarArchiveOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                        tarArchiveOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                        val files: List<File?> = ArrayList<File>(
                            FileUtils.listFiles(
                                inputFile,
                                RegexFileFilter("^(.*?)"),
                                DirectoryFileFilter.DIRECTORY
                            )
                        )
                        for (i in files.indices) {
                            val currentFile = files[i]
                            val relativeFilePath = inputFile.toURI().relativize(
                                File(currentFile!!.absolutePath).toURI()
                            ).path
                            val tarEntry = TarArchiveEntry(currentFile, relativeFilePath)
                            tarEntry.size = currentFile.length()
                            tarArchiveOutputStream.putArchiveEntry(tarEntry)
                            tarArchiveOutputStream.write(IOUtils.toByteArray(FileInputStream(currentFile)))
                            tarArchiveOutputStream.closeArchiveEntry()
                        }
                        tarArchiveOutputStream.close()
                    }
                }
            }
        }
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
                                it.id.contains("Encounter.reasonReference") ||
                                it.id.contains("Encounter.appointment")
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
        var inputStream : InputStream

        // Try self first
        try {
            val packUrl =  "https://fhir.nhs.uk/ImplementationGuide/" + name+"-" + version
            inputStream = readFromUrl(fhirServerProperties.server.baseUrl + "/FHIR/R4/ImplementationGuide/\$package?url="+packUrl )
        } catch (ex : Exception) {
            logger.info("Package not found in AWS Cache "+name+ "-"+version)
            inputStream = readFromUrl("https://packages.simplifier.net/" + name + "/" + version)
        }
        val packages = arrayListOf<NpmPackage>()
        val npmPackage = NpmPackage.fromPackage(inputStream)

        val dependency= npmPackage.npm.get("dependencies")

        if (dependency.isJsonObject) {

            val obj = dependency.asJsonObject()
            val entrySet: MutableList<JsonProperty>? = obj.properties
            entrySet?.forEach() {
                logger.info(it.name + " version =  " + it.value)
                if (it.name != "hl7.fhir.r4.core") {
                    val entryVersion = it.value?.asString()?.replace("\"","")
                    if (it.name != null && entryVersion != null) {
                        val packs = downloadPackage(it.name!!, entryVersion)
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
