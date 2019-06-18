package com.papsign.ktor.openapigen

import com.papsign.ktor.openapigen.annotations.encodings.APIEncoding
import com.papsign.ktor.openapigen.content.type.ContentTypeProvider
import com.papsign.ktor.openapigen.modules.CachingModuleProvider
import com.papsign.ktor.openapigen.modules.schema.*
import com.papsign.ktor.openapigen.openapi.ExternalDocumentation
import com.papsign.ktor.openapigen.openapi.OpenAPI
import com.papsign.ktor.openapigen.openapi.Schema.SchemaRef
import com.papsign.ktor.openapigen.openapi.Server
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.request.path
import io.ktor.util.AttributeKey
import org.reflections.Reflections
import kotlin.reflect.KType

class OpenAPIGen(private val config: Configuration) {

    val api = config.api

    private val tags = HashMap<String, APITag>()

    val schemaNamer = object : SchemaNamer {
        private val fn = config.schemaNamer

        override fun get(type: KType): String  = fn(type)
    }

    private val registrars: Array<out PartialSchemaRegistrar> = config.registrars.plus(PrimitiveSchemas(schemaNamer))
    val schemaRegistrar = Schemas()

    val globalModuleProvider = CachingModuleProvider()

    init {
        val reflections = Reflections(this::class.java.`package`.name)
        val classes = reflections.getTypesAnnotatedWith(APIEncoding::class.java).mapNotNull { it.kotlin.objectInstance }
        classes.forEach {
            when (it) {
                is ContentTypeProvider -> {
                    globalModuleProvider.registerModule(it)
                }
            }
        }
    }

    class Configuration(val api: OpenAPI) {
        inline fun info(crossinline configure: OpenAPI.Info.() -> Unit) {
            api.info = OpenAPI.Info().apply(configure)
        }

        inline fun OpenAPI.Info.contact(crossinline configure: OpenAPI.Contact.() -> Unit) {
            contact = OpenAPI.Contact().apply(configure)
        }

        inline fun server(url: String, crossinline configure: Server.() -> Unit = {}) {
            api.servers.add(Server(url).apply(configure))
        }

        inline fun externalDocs(url: String, crossinline configure: ExternalDocumentation.() -> Unit = {}) {
            api.externalDocs = ExternalDocumentation(url).apply(configure)
        }

        var swaggerUiPath = "swagger-ui"
        var serveSwaggerUi = true

        var schemaNamer: (KType)->String = KType::toString

        var registrars: Array<PartialSchemaRegistrar> = arrayOf()
    }


    inner class Schemas: SimpleSchemaRegistrar(schemaNamer) {

        private val schemas = HashSchemaMap()
        private val names = HashMap<KType, String>()

        override fun get(type: KType, master: SchemaRegistrar): NamedSchema {
            val predefined = registrars.fold(null as NamedSchema?) { acc, reg ->
                acc ?: reg[type]
            }
            if (predefined != null)
                return predefined
            schemas.getOrPut(type) {
                val (name, schema) = super.get(type, master)
                schemas[type] = schema
                names[type] = name
                api.components.schemas[name] = schema
                schema
            }
            val name= names[type]!!
            return NamedSchema(name, SchemaRef<Any>("#/components/schemas/$name"))
        }
    }

    fun getOrRegisterTag(tag: APITag): String {
        val other = tags.getOrPut(tag.name) {
            api.tags.add(tag.toTag())
            tag
        }
        if (other != tag) error("TagModule named ${tag.name} is already defined")
        return tag.name
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Configuration, OpenAPIGen> {

        override val key = AttributeKey<OpenAPIGen>("OpenAPI Generator")

        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): OpenAPIGen {
            val api = OpenAPI()
            val cfg = Configuration(api).apply(configure)
            if (cfg.serveSwaggerUi) {
                val ui = SwaggerUi(cfg.swaggerUiPath)
                pipeline.intercept(ApplicationCallPipeline.Call) {
                    val cmp = "/${cfg.swaggerUiPath.trim('/')}/"
                    if (call.request.path().startsWith(cmp))
                        ui.serve(call.request.path().removePrefix(cmp), call)
                }
            }
            return OpenAPIGen(cfg)
        }
    }
}