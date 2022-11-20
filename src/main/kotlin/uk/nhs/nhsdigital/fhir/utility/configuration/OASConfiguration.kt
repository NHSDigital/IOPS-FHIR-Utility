package uk.nhs.nhsdigital.fhir.utility.configuration

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import io.swagger.v3.oas.models.servers.Server

@Configuration
class OASConfiguration {
    @Bean
    open fun customOpenAPI(
        fhirServerProperties: FHIRServerProperties
    ): OpenAPI? {

        val IG = "ImplementationGuide"
        val oas = OpenAPI()
            .info(
                Info()
                    .title(fhirServerProperties.server.name)
                    .version(fhirServerProperties.server.version)
                    .description(
                        /*
                        "\n\n The results of events or notifications posted from this OAS can be viewed on [Query for Existing Patient Data](http://lb-fhir-facade-926707562.eu-west-2.elb.amazonaws.com/)"
                        + "\n\n To view example patients (with example NHS Numbers), see **Patient Demographics Query** section of [Query for Existing Patient Data](http://lb-fhir-facade-926707562.eu-west-2.elb.amazonaws.com/)"

                                + "\n\n For ODS, GMP and GMP codes, see [Care Services Directory](http://lb-fhir-mcsd-1736981144.eu-west-2.elb.amazonaws.com/). This OAS also includes **Care Teams Management**"
                                + "\n\n For Document Notifications, see [Access to Health Documents](http://lb-fhir-mhd-1617422145.eu-west-2.elb.amazonaws.com/)."
                                */
                        "Internal FHIR Implementation Utilities"


                    )
                    .termsOfService("http://swagger.io/terms/")
                    .license(License().name("Apache 2.0").url("http://springdoc.org"))
            )

        oas.addServersItem(
            Server().description(fhirServerProperties.server.name).url(fhirServerProperties.server.baseUrl)
        )

        oas.addTagsItem(
            io.swagger.v3.oas.models.tags.Tag()
                .name(IG)
                )


        oas.path("/FHIR/R4/metadata", PathItem()
            .get(
                Operation()
                    .addTagsItem(IG)
                    .summary("server-capabilities: Fetch the server FHIR CapabilityStatement").responses(getApiResponses())))

        oas.path("/FHIR/R4/ImplementationGuide/\$cacheIG", PathItem()
            .get(
                Operation()
                    .addTagsItem(IG)
                    .summary("Retrieves an IG and triggers snapshot building process").responses(getApiResponses()))
            .addParametersItem(
                Parameter()
                    .name("name")
                    .`in`("query")
                    .required(true)
                    .style(Parameter.StyleEnum.SIMPLE)
                    .description("The name or package id")
                    .schema(StringSchema())
                    .example("uk.nhsdigital.r4")
            )
            .addParametersItem(
                Parameter()
                    .name("version")
                    .`in`("query")
                    .required(true)
                    .style(Parameter.StyleEnum.SIMPLE)
                    .description("The version of the package")
                    .schema(StringSchema())
                    .example("2.6.0")
            )
        )


        val implementationGuideItem = PathItem()
            .get(
                Operation()
                    .addTagsItem(IG)
                    .summary(IG+ " Option Search Parameters")
                    .responses(getApiResponses())
                    .addParametersItem(
                        Parameter()
                        .name("url")
                        .`in`("query")
                        .required(false)
                        .style(Parameter.StyleEnum.SIMPLE)
                        .description("The ID of the resource")
                        .schema(StringSchema())
                            .example("https://fhir.nhs.uk/ImplementationGuide/uk.nhsdigital.r4-2.6.0")
                    )

            )

        oas.path("/FHIR/R4/ImplementationGuide",implementationGuideItem)


        var binaryItem = PathItem()
            .get(
                Operation()
                    .addTagsItem(IG)
                    .summary("Read Binary. This returns the raw implementation guide")
                    .responses(getApiResponsesBinary())
                    .addParametersItem(Parameter()
                        .name("url")
                        .`in`("query")
                        .required(true)
                        .style(Parameter.StyleEnum.SIMPLE)
                        .description("url of the ImplementationGuide")
                        .schema(StringSchema())
                        .example("https://fhir.nhs.uk/ImplementationGuide/fhir.r4.ukcore.stu1-0.5.1")
                    )
            )



        oas.path("/FHIR/R4/ImplementationGuide/\$package",binaryItem)


        return oas
    }

    fun getApiResponses() : ApiResponses {

        val response200 = ApiResponse()
        response200.description = "OK"
        val exampleList = mutableListOf<Example>()
        exampleList.add(Example().value("{}"))
        response200.content = Content().addMediaType("application/fhir+json", MediaType().schema(StringSchema()._default("{}")))
        val apiResponses = ApiResponses().addApiResponse("200",response200)
        return apiResponses
    }

    fun getApiResponsesBinary() : ApiResponses {

        val response200 = ApiResponse()
        response200.description = "OK"
        val exampleList = mutableListOf<Example>()
        exampleList.add(Example().value("{}"))
        response200.content = Content().addMediaType("*/*", MediaType().schema(StringSchema()._default("{}")))
        val apiResponses = ApiResponses().addApiResponse("200",response200)
        return apiResponses
    }
}
