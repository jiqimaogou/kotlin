/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.caching.*
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

@Suppress("UNCHECKED_CAST")
fun CommonToolArguments.toFlatCompilerArguments(): FlatCompilerArgumentsBucket {

    val allPropertiesToArgumentAnnotationMap = this::class.java.kotlin.memberProperties
        .mapNotNull { prop -> (prop.annotations.firstOrNull { it is Argument } as? Argument)?.let { prop to it } }
        .toMap()

    val propertiesToArgumentAnnotationMap = allPropertiesToArgumentAnnotationMap
        .filterKeys { it != K2JVMCompilerArguments::classpath && it != K2MetadataCompilerArguments::classpath }


    val flagProperties = propertiesToArgumentAnnotationMap.filterKeys { it.returnType.classifier == Boolean::class }
    val flagPropsToValues = flagProperties.keys.mapNotNull { it.safeAs<KProperty1<CommonToolArguments, Boolean>>() }
        .associateWith { it.get(this) }
    val flatFlags = flagPropsToValues.filterValues { it }.mapNotNull { flagProperties[it.key]?.value }

    val singleProperties = propertiesToArgumentAnnotationMap
        .filterKeys { it.returnType.classifier == String::class && !it.returnType.isMarkedNullable }
    val singlePropsToValues = singleProperties.keys.mapNotNull { it.safeAs<KProperty1<CommonToolArguments, String>>() }
        .associateWith { it.get(this) }
    val singleArguments = singlePropsToValues.map { singleProperties[it.key] to it.value }
        .filter { it.first != null }.associate { it.first!!.value to it.second }

    val singleNullableProperties = propertiesToArgumentAnnotationMap
        .filterKeys { it.returnType.classifier == String::class && it.returnType.isMarkedNullable }
    val singleNullablePropsToValues = singleNullableProperties.keys
        .mapNotNull { it.safeAs<KProperty1<CommonToolArguments, String?>>() }
        .associateWith { it.get(this) }
    val singleNullableArguments = singleNullablePropsToValues.map { singleNullableProperties[it.key] to it.value }
        .filter { it.first != null && it.second != null }.associate { it.first!!.value to it.second!! }

    val multipleProperties = propertiesToArgumentAnnotationMap.filterKeys { it.returnType.classifier?.javaClass?.isArray == true }
    val multiplePropsToValues = multipleProperties.keys.mapNotNull { it.safeAs<KProperty1<CommonToolArguments, Array<String>?>>() }
        .associateWith { it.get(this) }
    val multipleArguments = multiplePropsToValues.map { multipleProperties[it.key] to it.value }
        .filter { it.first != null && it.second != null }.associate { it.first!!.value to it.second!!.toList() }

    val classpathProperties = allPropertiesToArgumentAnnotationMap.keys.subtract(propertiesToArgumentAnnotationMap.keys)
        .associateWith { allPropertiesToArgumentAnnotationMap[it] }
    val classpathPropsToValues = classpathProperties.keys.mapNotNull { it.safeAs<KProperty1<CommonToolArguments, String?>>() }
        .associateWith { it.get(this) }
    val classpathArguments = classpathPropsToValues.map { classpathProperties[it.key] to it.value }
        .singleOrNull { it.second != null }
        ?.let { it.first!!.value to it.second!!.split(File.pathSeparator) }

    val freeArgs = CommonToolArguments::freeArgs.get(this)
    val internalArguments = CommonToolArguments::internalArguments.get(this).map { it.stringRepresentation }

    return FlatCompilerArgumentsBucket(
        classpathArguments,
        (singleArguments + singleNullableArguments).toMutableMap(),
        multipleArguments.toMutableMap(),
        flatFlags.toMutableList(),
        internalArguments.toMutableList(),
        freeArgs.toMutableList()
    )
}

fun <R> KProperty1<*, R>.calculateArgumentAnnotation(): ArgumentAnnotationInfo? {
    val argumentAnnotation = annotations.firstOrNull { it is Argument } as? Argument ?: return null
    return with(argumentAnnotation) { ArgumentAnnotationInfoImpl(value, shortName, deprecatedName, delimiter) }
}

fun FlatCompilerArgumentsBucket.extractSingleNullableArgument(
    property: KMutableProperty1<*, String?>
): Pair<String, String>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return singleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }?.toPair()
}

fun FlatCompilerArgumentsBucket.extractSingleNullableArgumentValue(
    property: KMutableProperty1<*, String?>
): String? = extractSingleNullableArgument(property)?.second

fun FlatCompilerArgumentsBucket.extractSingleArgument(
    property: KMutableProperty1<*, String>
): Pair<String, String>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return singleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }?.toPair()
}

fun FlatCompilerArgumentsBucket.extractSingleArgumentValue(
    property: KMutableProperty1<*, String>
): String? = extractSingleArgument(property)?.second

fun FlatCompilerArgumentsBucket.extractMultipleArgument(
    property: KMutableProperty1<*, Array<String>?>
): Pair<String, Array<String>>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return multipleArguments.entries.firstOrNull { argumentAnnotationInfo.isSuitableValue(it.key) }
        ?.let { it.key to it.value.toTypedArray() }
}

fun FlatCompilerArgumentsBucket.extractMultipleArgumentValue(
    property: KMutableProperty1<*, Array<String>?>
): Array<String>? = extractMultipleArgument(property)?.second

fun FlatCompilerArgumentsBucket.extractFlagArgument(
    property: KMutableProperty1<*, Boolean>
): Pair<String, Boolean>? {
    val argumentAnnotationInfo = property.calculateArgumentAnnotation() ?: return null
    return flagArguments.firstOrNull { argumentAnnotationInfo.isSuitableValue(it) }?.let { it to true }
        ?: argumentAnnotationInfo.value to false
}

fun FlatCompilerArgumentsBucket.extractFlagArgumentValue(
    property: KMutableProperty1<*, Boolean>
): Boolean? = extractFlagArgument(property)?.second

fun FlatCompilerArgumentsBucket.extractClasspaths(): List<String>? = classpathParts?.second

fun FlatCompilerArgumentsBucket.extractClasspathJoined(): String? = classpathParts?.second?.joinToString(File.pathSeparator)