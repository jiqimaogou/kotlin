/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import java.io.File

interface CompilerArgumentsBucketConverter<From, To> {
    fun convert(from: From): To
}

class RawToFlatCompilerArgumentsBucketConverter(val classLoader: ClassLoader) :
    CompilerArgumentsBucketConverter<RawCompilerArgumentsBucket, FlatCompilerArgumentsBucket> {
    private val dividedPropertiesWithArgumentAnnotationInfo by lazy {
        DividedPropertiesWithArgumentAnnotationInfoManager(classLoader).dividedPropertiesWithArgumentAnnotationInfo
    }

    override fun convert(from: RawCompilerArgumentsBucket): FlatCompilerArgumentsBucket {

        val singlePropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.singlePropertiesToArgumentAnnotation
        val multiplePropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.multiplePropertiesToArgumentAnnotation
        val flagPropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.flagPropertiesToArgumentAnnotation
        val classpathPropertiesToArgumentAnnotation = dividedPropertiesWithArgumentAnnotationInfo.classpathPropertiesToArgumentAnnotation

        val processedArguments = mutableListOf<String>()

        val classpathArgumentAnnotation = classpathPropertiesToArgumentAnnotation.values.first()
        val flattenClasspathParts = classpathArgumentAnnotation.processArgumentWithInfo(from, processedArguments).entries.lastOrNull()
            ?.let { it.key to it.value.split(File.pathSeparator) }

        val flattenSingleArguments = mutableMapOf<String, String>()
        singlePropertiesToArgumentAnnotation.values.forEach {
            flattenSingleArguments += it.processArgumentWithInfo(from, processedArguments)
        }
        val flattenMultipleArguments = mutableMapOf<String, List<String>>()
        multiplePropertiesToArgumentAnnotation.values.forEach { info ->
            flattenMultipleArguments += info.processArgumentWithInfo(from, processedArguments)
                .map { it.key to it.value.split(info.delimiter) }
        }
        val flatFlagArguments =
            flagPropertiesToArgumentAnnotation.values.mapNotNull { info ->
                from.find { info.suitArgument(it) != null }?.also { processedArguments.add(it) }
            }.distinct()

        //TODO replace with InternalArgumentParser.INTERNAL_ARGUMENT_PREFIX via reflection
        val flatInternalArguments = from.filter { it.startsWith("-XX") }.also { processedArguments.addAll(it) }

        val flatFreeArs = from - processedArguments

        return FlatCompilerArgumentsBucket(
            flattenClasspathParts,
            flattenSingleArguments,
            flattenMultipleArguments,
            flatFlagArguments.toMutableList(),
            flatInternalArguments.toMutableList(),
            flatFreeArs.toMutableList()
        )
    }
}
