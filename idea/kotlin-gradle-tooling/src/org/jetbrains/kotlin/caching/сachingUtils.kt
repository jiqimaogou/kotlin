/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.gradle.getMethodOrNull
import org.jetbrains.kotlin.utils.addToStdlib.cast
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

typealias CachedCompilerArgumentBySourceSet = Map<String, CachedArgsInfo>
typealias FlatCompilerArgumentBySourceSet = MutableMap<String, FlatArgsInfo>

private val ARGUMENT_ANNOTATION_CLASSES =
    setOf("org.jetbrains.kotlin.cli.common.arguments.Argument", "org.jetbrains.kotlin.com.sampullara.cli.Argument")

const val PARSE_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.ParseCommandLineArgumentsKt"
const val COMMON_TOOL_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments"
const val JS_DCE_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JSDceArguments"
const val COMMON_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments"
const val JS_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments"
const val METADATA_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments"
const val JVM_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments"

internal enum class ArgumentPrefix {
    VALUE, SHORT_NAME, DEPRECATED_NAME
}

internal data class SuitResult(val argumentAnnotationInfo: ArgumentAnnotationInfo, val likeAdvanced: Boolean, val prefix: ArgumentPrefix)

data class ArgumentAnnotationInfo(
    val value: String,
    val shortName: String,
    val deprecatedName: String,
    val delimiter: String,
    val isAdvanced: Boolean
) {
    internal operator fun get(argumentPrefix: ArgumentPrefix): String = when (argumentPrefix) {
        ArgumentPrefix.VALUE -> value
        ArgumentPrefix.SHORT_NAME -> shortName
        ArgumentPrefix.DEPRECATED_NAME -> deprecatedName
    }
}

fun ArgumentAnnotationInfo.processArgumentWithInfo(
    from: RawCompilerArgumentsBucket,
    processedArguments: MutableList<String>
): Map<String, String> = from.mapIndexedNotNull { index, s -> suitArgument(s)?.let { index to it } }
    .map { (id, res) ->
        processedArguments += from[id]
        val first = res.argumentAnnotationInfo[res.prefix]
        val second =
            if (res.likeAdvanced) from[id].substringAfter("=") else from.getOrNull(id + 1)?.also { processedArguments += it }
                ?: error("Value for argument $first was not found!")
        first to second
    }.toMap()


fun ArgumentAnnotationInfo.isSuitableValue(arg: String): Boolean = suitArgument(arg) != null

internal fun ArgumentAnnotationInfo.suitArgument(arg: String): SuitResult? {
    val prefix = arg.substringBefore("=")
    val likeAdvanced = prefix != arg
    val prefixKind = when (prefix) {
        value -> ArgumentPrefix.VALUE
        shortName -> ArgumentPrefix.SHORT_NAME
        deprecatedName -> ArgumentPrefix.DEPRECATED_NAME
        else -> null
    }
    return prefixKind?.let { SuitResult(this, likeAdvanced, it) }
}

data class DividedPropertiesWithArgumentAnnotationInfo(
    val flagPropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonCompilerArguments, Boolean>, ArgumentAnnotationInfo>,
    val singlePropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonCompilerArguments, String?>, ArgumentAnnotationInfo>,
    val multiplePropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonCompilerArguments, Array<String>?>, ArgumentAnnotationInfo>,
    val classpathPropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonCompilerArguments, String?>, ArgumentAnnotationInfo>
)

private fun getClassSafely(className: String, classLoader: ClassLoader?): Class<*>? = try {
    Class.forName(className, true, classLoader)
} catch (e: NoClassDefFoundError) {
    null
} catch (e: ClassNotFoundException) {
    null
}

val ClassLoader?.argumentAnnotationClazz: Class<*>?
    get() = ARGUMENT_ANNOTATION_CLASSES.mapNotNull { getClassSafely(it, this) }.firstOrNull()

val ClassLoader?.commonToolArgumentsClazz: Class<*>?
    get() = getClassSafely(COMMON_TOOL_ARGUMENTS_CLASS, this)
val ClassLoader?.jsDceArgumentsClazz: Class<*>?
    get() = getClassSafely(JS_DCE_ARGUMENTS_CLASS, this)
val ClassLoader?.commonArgumentsClazz: Class<*>?
    get() = getClassSafely(COMMON_ARGUMENTS_CLASS, this)
val ClassLoader?.jsArgumentsClazz: Class<*>?
    get() = getClassSafely(JS_ARGUMENTS_CLASS, this)
val ClassLoader?.metadataArgumentsClazz: Class<*>?
    get() = getClassSafely(METADATA_ARGUMENTS_CLASS, this)
val ClassLoader?.jvmArgumentsClazz: Class<*>?
    get() = getClassSafely(JVM_ARGUMENTS_CLASS, this)

val ClassLoader?.isAdvancedMethod: Method?
    get() = argumentAnnotationClazz?.let { getClassSafely(PARSE_ARGUMENTS_CLASS, this)?.getMethodOrNull("isAdvanced", it) }

class DividedPropertiesWithArgumentAnnotationInfoManager(val classLoader: ClassLoader) {
    val dividedPropertiesWithArgumentAnnotationInfo: DividedPropertiesWithArgumentAnnotationInfo by lazy {
        val allPropertiesToArgumentAnnotation = with(classLoader) {
            listOfNotNull(
                commonToolArgumentsClazz,
                jsDceArgumentsClazz,
                commonArgumentsClazz,
                jsArgumentsClazz,
                metadataArgumentsClazz,
                jvmArgumentsClazz
            ).flatMap { it.kotlin.memberProperties }
                .mapNotNull { argClazzProp ->
                    argClazzProp.annotations.firstOrNull { annotation ->
                        with(annotation::class.java) {
                            Proxy.isProxyClass(this)
                                    && (getMethodOrNull("annotationType")?.invoke(annotation) as Class<*>).name in ARGUMENT_ANNOTATION_CLASSES
                                    || kotlin == argumentAnnotationClazz?.kotlin
                        }
                    }?.let {
                        val value = it.javaClass.getMethodOrNull("value")?.invoke(it) as? String ?: return@mapNotNull null
                        val shortName = it.javaClass.getMethodOrNull("shortName")?.invoke(it) as? String ?: return@mapNotNull null
                        val deprecatedName = it.javaClass.getMethodOrNull("deprecatedName")?.invoke(it) as? String ?: return@mapNotNull null
                        val delimiter = it.javaClass.getMethodOrNull("delimiter")?.invoke(it) as? String ?: return@mapNotNull null
                        val isAdvanced = isAdvancedMethod?.invoke(null, it) as? Boolean ?: value.startsWith("-X")
                        argClazzProp to ArgumentAnnotationInfo(value, shortName, deprecatedName, delimiter, isAdvanced)
                    } ?: return@mapNotNull null

                }.toMap()
        }

        val classpathPropsToArgumentAnnotation = allPropertiesToArgumentAnnotation.filter { it.key.name == "classpath" }
            .map { it.key.cast<KMutableProperty1<CommonCompilerArguments, String?>>() to it.value }.toMap()
        val flagPropsToArgumentAnnotation = allPropertiesToArgumentAnnotation.filter { it.key.returnType.classifier == Boolean::class }
            .map { it.key.cast<KMutableProperty1<CommonCompilerArguments, Boolean>>() to it.value }.toMap()
        val singlePropsToArgumentAnnotation =
            allPropertiesToArgumentAnnotation.filter { it.key.returnType.classifier == String::class && it.key.name != "classpath" }
                .map { it.key.cast<KMutableProperty1<CommonCompilerArguments, String?>>() to it.value }.toMap()
        val multiplePropsToArgumentAnnotation =
            allPropertiesToArgumentAnnotation.filter { it.key.returnType.classifier == Array<String>::class }
                .map { it.key.cast<KMutableProperty1<CommonCompilerArguments, Array<String>?>>() to it.value }.toMap()

        DividedPropertiesWithArgumentAnnotationInfo(
            flagPropsToArgumentAnnotation,
            singlePropsToArgumentAnnotation,
            multiplePropsToArgumentAnnotation,
            classpathPropsToArgumentAnnotation
        )
    }
}
