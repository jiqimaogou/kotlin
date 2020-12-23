/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
import org.jetbrains.kotlin.gradle.getMethodOrNull
import org.jetbrains.kotlin.utils.addToStdlib.cast
import java.lang.reflect.Method
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties

typealias FlatCompilerArgumentBySourceSet = Map<String, FlatArgsInfo>

const val PARSE_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.ParseCommandLineArgumentsKt"

const val COMMON_TOOL_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments"
const val JS_DCE_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JSDceArguments"
const val COMMON_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments"
const val JS_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments"
const val METADATA_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments"
const val JVM_ARGUMENTS_CLASS = "org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments"
const val ARGUMENT_ANNOTATION_CLASS = "org.jetbrains.kotlin.cli.common.arguments.Argument"
const val LEGACY_ARGUMENT_ANNOTATION_CLASS = "org.jetbrains.kotlin.com.sampullara.cli.Argument"

private val ARGUMENT_ANNOTATION_CLASSES = setOf(LEGACY_ARGUMENT_ANNOTATION_CLASS, ARGUMENT_ANNOTATION_CLASS)

internal enum class ArgumentPrefix {
    VALUE, ALIAS, OTHER
}

internal data class SuitResult(val argumentAnnotationInfo: ArgumentAnnotationInfo, val likeAdvanced: Boolean, val prefix: ArgumentPrefix)

interface ArgumentAnnotationInfo {
    val value: String
    val alias: String
    val delimiter: String
    val otherName: String
    fun processArgumentWithInfo(from: RawCompilerArgumentsBucket, processedArguments: MutableList<String>): Map<String, String>
}

internal operator fun ArgumentAnnotationInfo.get(argumentPrefix: ArgumentPrefix): String = when (argumentPrefix) {
    ArgumentPrefix.VALUE -> value
    ArgumentPrefix.ALIAS -> alias
    ArgumentPrefix.OTHER -> otherName
}

data class LegacyArgumentAnnotationInfo(
    val valueSuffix: String,
    val prefix: String,
    val aliasSuffix: String,
    override val delimiter: String
) : ArgumentAnnotationInfo {
    override val value: String
        get() = prefix + valueSuffix
    override val alias: String
        get() = prefix + aliasSuffix
    override val otherName: String
        get() = value

    override fun processArgumentWithInfo(from: RawCompilerArgumentsBucket, processedArguments: MutableList<String>): Map<String, String> =
        from.mapIndexedNotNull { index, s -> suitArgument(s)?.let { index to it } }
            .map { (id, res) ->
                processedArguments += from[id]
                val first = res.argumentAnnotationInfo[res.prefix]
                val second = from.getOrNull(id + 1)?.also { processedArguments += it } ?: error("Value for argument $first was not found!")
                first to second
            }.toMap()
}

data class ArgumentAnnotationInfoImpl(
    override val value: String,
    val shortName: String,
    val deprecatedName: String,
    override val delimiter: String,
) : ArgumentAnnotationInfo {
    override val alias: String
        get() = shortName
    override val otherName: String
        get() = deprecatedName

    override fun processArgumentWithInfo(from: RawCompilerArgumentsBucket, processedArguments: MutableList<String>): Map<String, String> =
        from.mapIndexedNotNull { index, s -> suitArgument(s)?.let { index to it } }
            .map { (id, res) ->
                processedArguments += from[id]
                val first = res.argumentAnnotationInfo[res.prefix]
                val second =
                    if (res.likeAdvanced) from[id].substringAfter("=") else from.getOrNull(id + 1)?.also { processedArguments += it }
                        ?: error("Value for argument $first was not found!")
                first to second
            }.toMap()
}

fun ArgumentAnnotationInfo.isSuitableValue(arg: String): Boolean = suitArgument(arg) != null

internal fun ArgumentAnnotationInfo.suitArgument(arg: String): SuitResult? {
    val prefix = arg.substringBefore("=")
    val likeAdvanced = prefix != arg
    val prefixKind = when (prefix) {
        value -> ArgumentPrefix.VALUE
        alias -> ArgumentPrefix.ALIAS
        otherName -> ArgumentPrefix.OTHER
        else -> null
    }
    return prefixKind?.let { SuitResult(this, likeAdvanced, it) }
}

data class DividedPropertiesWithArgumentAnnotationInfo(
    val flagPropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonToolArguments, Boolean>, ArgumentAnnotationInfo>,
    val singleNullablePropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonToolArguments, String?>, ArgumentAnnotationInfo>,
    val singlePropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonToolArguments, String>, ArgumentAnnotationInfo>,
    val multiplePropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonToolArguments, Array<String>?>, ArgumentAnnotationInfo>,
    val classpathPropertiesToArgumentAnnotation: Map<KMutableProperty1<CommonToolArguments, String?>, ArgumentAnnotationInfo>
)

private fun getClassSafely(className: String, classLoader: ClassLoader?): Class<*>? = try {
    Class.forName(className, true, classLoader)
} catch (e: NoClassDefFoundError) {
    null
} catch (e: ClassNotFoundException) {
    null
}

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

class DividedPropertiesWithArgumentAnnotationInfoManager(val classLoader: ClassLoader) {
    val dividedPropertiesWithArgumentAnnotationInfo: DividedPropertiesWithArgumentAnnotationInfo by lazy {
        val allDistinctProperties = listOfNotNull(
            classLoader.commonToolArgumentsClazz,
            classLoader.jsDceArgumentsClazz,
            classLoader.commonArgumentsClazz,
            classLoader.jsArgumentsClazz,
            classLoader.metadataArgumentsClazz,
            classLoader.jvmArgumentsClazz
        ).flatMap { it.kotlin.declaredMemberProperties }

        val propertiesToArgumentAnnotationMap = allDistinctProperties.associateWith { prop ->
            prop.annotations.firstOrNull {
                (it.javaClass.getMethodOrNull("annotationType")?.invoke(it) as? Class<*>)?.name in ARGUMENT_ANNOTATION_CLASSES
            }
        }

        val propertiesToArgumentAnnotationInfoMap = propertiesToArgumentAnnotationMap.mapValues { (k, v) ->
            val argAnnoClass = v?.javaClass ?: return@mapValues null

            val value = argAnnoClass.getMethodOrNull("value")?.invoke(v) as? String ?: return@mapValues null
            val delimiter = argAnnoClass.getMethodOrNull("delimiter")?.invoke(v) as? String ?: return@mapValues null
            val annotationType = argAnnoClass.getMethodOrNull("annotationType")?.invoke(v) as? Class<*> ?: return@mapValues null
            if (annotationType.name == LEGACY_ARGUMENT_ANNOTATION_CLASS) {
                val alias = argAnnoClass.getMethod("alias").invoke(v) as String
                val prefix = argAnnoClass.getMethod("prefix").invoke(v) as String
                LegacyArgumentAnnotationInfo(value, prefix, alias, delimiter)
            } else {
                val shortName = v.javaClass.getMethodOrNull("shortName")?.invoke(v) as? String ?: return@mapValues null
                val deprecatedName = v.javaClass.getMethodOrNull("deprecatedName")?.invoke(v) as? String ?: return@mapValues null
                ArgumentAnnotationInfoImpl(value, shortName, deprecatedName, delimiter)
            }
        }


        val classpathPropsToArgumentAnnotation = propertiesToArgumentAnnotationInfoMap.filter { it.key.name == "classpath" }
            .mapNotNull { it.value?.let { v -> it.key.cast<KMutableProperty1<CommonToolArguments, String?>>() to v } }.toMap()
        val flagPropsToArgumentAnnotation = propertiesToArgumentAnnotationInfoMap.filter { it.key.returnType.classifier == Boolean::class }
            .mapNotNull { it.value?.let { v -> it.key.cast<KMutableProperty1<CommonToolArguments, Boolean>>() to v } }.toMap()
        val singleNullablePropsToArgumentAnnotation = propertiesToArgumentAnnotationInfoMap
            .filter { it.key.returnType.classifier == String::class && it.key.returnType.isMarkedNullable && it.key.name != "classpath" }
            .mapNotNull { it.value?.let { v -> it.key.cast<KMutableProperty1<CommonToolArguments, String?>>() to v } }.toMap()
        val singlePropsToArgumentAnnotation = propertiesToArgumentAnnotationInfoMap
            .filter { it.key.returnType.classifier == String::class && !it.key.returnType.isMarkedNullable && it.key.name != "classpath" }
            .mapNotNull { it.value?.let { v -> it.key.cast<KMutableProperty1<CommonToolArguments, String>>() to v } }.toMap()
        val multiplePropsToArgumentAnnotation =
            propertiesToArgumentAnnotationInfoMap.filter { it.key.returnType.classifier == Array<String>::class }
                .mapNotNull { it.value?.let { v -> it.key.cast<KMutableProperty1<CommonToolArguments, Array<String>?>>() to v } }
                .toMap()

        DividedPropertiesWithArgumentAnnotationInfo(
            flagPropsToArgumentAnnotation,
            singleNullablePropsToArgumentAnnotation,
            singlePropsToArgumentAnnotation,
            multiplePropsToArgumentAnnotation,
            classpathPropsToArgumentAnnotation
        )
    }
}
