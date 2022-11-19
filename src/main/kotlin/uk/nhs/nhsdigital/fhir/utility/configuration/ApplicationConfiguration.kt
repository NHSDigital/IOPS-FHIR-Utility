package uk.nhs.nhsdigital.fhir.utility.configuration

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.StrictErrorHandler
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import uk.nhs.nhsdigital.fhirvalidator.util.CorsFilter
import javax.servlet.Filter



@Configuration
class ApplicationConfiguration {
    @Bean("R4")
    open fun fhirR4Context(): FhirContext {
        val fhirContext = FhirContext.forR4()
        fhirContext.setParserErrorHandler(StrictErrorHandler())
        return fhirContext
    }

    @Bean
    fun corsFilter(): FilterRegistrationBean<*>? {
        val source = UrlBasedCorsConfigurationSource()
        val config = CorsConfiguration()
        config.allowCredentials = true
        config.addAllowedOrigin("*")
        config.addAllowedHeader("*")
        config.addAllowedMethod("*")
        source.registerCorsConfiguration("/**", config)
        val bean: FilterRegistrationBean<*> = FilterRegistrationBean<Filter>(CorsFilter())
        bean.order = 0
        return bean
    }
}
