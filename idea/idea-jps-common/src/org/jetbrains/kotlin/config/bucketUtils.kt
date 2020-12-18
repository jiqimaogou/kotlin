/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.caching.ArgumentAnnotationInfo
import org.jetbrains.kotlin.caching.DividedPropertiesWithArgumentAnnotationInfoManager
import org.jetbrains.kotlin.caching.FlatCompilerArgumentsBucket
import org.jetbrains.kotlin.caching.isSuitableValue
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

@Suppress("UNCHECKED_CAST")
fun CommonCompilerArguments.toFlatCompilerArguments(): FlatCompilerArgumentsBucket {
    val (flagPropertiesToArgumentAnnotation,
        singlePropertiesToArgumentAnnotation,
        multiplePropertiesToArgumentAnnotation,
        classpathPropertiesToArgumentAnnotation) =
        DividedPropertiesWithArgumentAnnotationInfoManager(this::class.java.classLoader).dividedPropertiesWithArgumentAnnotationInfo

    val properties = this::class.java.kotlin.memberProperties

    val flagProperties = flagPropertiesToArgumentAnnotation.filterKeys { it in properties }
    val flagPropsToValues = flagProperties.mapNotNull { it.safeAs<KProperty1<CommonCompilerArguments, Boolean>>() }
        .associateWith { it.get(this) }
    val flatFlags = flagPropsToValues.filterValues { it }.mapNotNull { flagPropertiesToArgumentAnnotation[it.key]?.value }

    val singleProperties = properties.intersect(singlePropertiesToArgumentAnnotation.keys)
    val singlePropsToValues = singleProperties.mapNotNull { it.safeAs<KProperty1<CommonCompilerArguments, String?>>() }
        .associateWith { it.get(this) }
    val singleArguments = singlePropsToValues.map { singlePropertiesToArgumentAnnotation[it.key] to it.value }
        .filter { it.first != null && it.second != null }.associate { it.first!!.value to it.second!! }

    val multipleProperties = properties.intersect(multiplePropertiesToArgumentAnnotation.keys)
    val multiplePropsToValues = multipleProperties.mapNotNull { it.safeAs<KProperty1<CommonCompilerArguments, Array<String>?>>() }
        .associateWith { it.get(this) }
    val multipleArguments = multiplePropsToValues.map { multiplePropertiesToArgumentAnnotation[it.key] to it.value }
        .filter { it.first != null && it.second != null }.associate { it.first!!.value to it.second!!.toList() }

    val classpathProperties = properties.intersect(classpathPropertiesToArgumentAnnotation.keys)
    val classpathPropsToValues = classpathProperties.mapNotNull { it.safeAs<KProperty1<CommonCompilerArguments, String?>>() }
        .associateWith { it.get(this) }
    val classpathArguments = classpathPropsToValues.map { classpathPropertiesToArgumentAnnotation[it.key] to it.value }
        .singleOrNull { it.first != null && it.second != null }
        ?.let { it.first!!.value to it.second!!.split(File.pathSeparator) }

    val freeArgs = CommonCompilerArguments::freeArgs.invoke(this)
    val internalArguments = CommonCompilerArguments::internalArguments.invoke(this).map { it.stringRepresentation }

    return FlatCompilerArgumentsBucket(
        classpathArguments,
        singleArguments.toMutableMap(),
        multipleArguments.toMutableMap(),
        flatFlags.toMutableList(),
        internalArguments.toMutableList(),
        freeArgs.toMutableList()
    )
}

fun <R> KProperty1<*, R>.calculateArgumentAnnotation(): ArgumentAnnotationInfo? {
    val argumentAnnotation = annotations.firstOrNull { it is Argument } as? Argument ?: return null
    return with(argumentAnnotation) { ArgumentAnnotationInfo(value, shortName, deprecatedName, delimiter, isAdvanced) }
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