/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import java.io.File

interface CompilerArgumentsBucketConverter<From, To> {
    fun convert(from: From): To
}

class CachedToFlatCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<CachedCompilerArgumentsBucket, FlatCompilerArgumentsBucket> {

    override fun convert(from: CachedCompilerArgumentsBucket): FlatCompilerArgumentsBucket {
        val flattenSingleArguments = from.singleArguments.entries.associate { (k, v) ->
            mapper.getArgument(k) to mapper.getArgument(v)
        }
        val flattenMultipleArguments = from.multipleArguments.entries.associate { (k, v) ->
            mapper.getArgument(k) to v.map { mapper.getArgument(it) }
        }
        val flattenFlagArguments = from.flagArguments.map { mapper.getArgument(it) }
        val flattenClasspathParts = from.classpathParts?.let { (k, v) ->
            mapper.getArgument(k) to v.map { mapper.getArgument(it) }
        }
        val flattenInternalArguments = from.internalArguments.map { mapper.getArgument(it) }
        val flattenFreeArgs = from.freeArgs.map { mapper.getArgument(it) }
        return FlatCompilerArgumentsBucket().apply {
            classpathParts = flattenClasspathParts
            singleArguments.putAll(flattenSingleArguments)
            multipleArguments.putAll(flattenMultipleArguments)
            flagArguments.addAll(flattenFlagArguments)
            internalArguments.addAll(flattenInternalArguments)
            freeArgs.addAll(flattenFreeArgs)
        }
    }
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

        return FlatCompilerArgumentsBucket().apply {
            classpathParts = flattenClasspathParts
            singleArguments += flattenSingleArguments
            multipleArguments += flattenMultipleArguments
            flagArguments += flatFlagArguments
            internalArguments += flatInternalArguments
            freeArgs += flatFreeArs
        }
    }
}

class FlatToCachedCompilerArgumentsBucketConverter(val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<FlatCompilerArgumentsBucket, CachedCompilerArgumentsBucket> {
    override fun convert(from: FlatCompilerArgumentsBucket): CachedCompilerArgumentsBucket {
        val cachedSingleArguments = from.singleArguments.entries.associate {
            mapper.cacheArgument(it.key) to mapper.cacheArgument(it.value)
        }
        val cachedMultipleArguments = from.multipleArguments.entries.associate {
            mapper.cacheArgument(it.key) to it.value.map { v -> mapper.cacheArgument(v) }
        }
        val cachedFlagArguments = from.flagArguments.map { mapper.cacheArgument(it) }
        val cachedClasspathParts =
            from.classpathParts?.let { mapper.cacheArgument(it.first) to it.second.map { v -> mapper.cacheArgument(v) } }

        val cachedInternalArguments = from.internalArguments.map { mapper.cacheArgument(it) }
        val cachedFreeArgs = from.freeArgs.map { mapper.cacheArgument(it) }
        return CachedCompilerArgumentsBucket().apply {
            classpathParts = cachedClasspathParts
            singleArguments += cachedSingleArguments
            multipleArguments += cachedMultipleArguments
            flagArguments += cachedFlagArguments
            internalArguments += cachedInternalArguments
            freeArgs += cachedFreeArgs
        }
    }
}

class RawToCachedCompilerArgumentsBucketConverter(val classLoader: ClassLoader, val mapper: ICompilerArgumentsMapper) :
    CompilerArgumentsBucketConverter<RawCompilerArgumentsBucket, CachedCompilerArgumentsBucket> {
    private val rawToFlatCompilerArgumentsBucketConverter by lazy { RawToFlatCompilerArgumentsBucketConverter(classLoader) }
    private val flatToCachedCompilerArgumentsBucketConverter by lazy { FlatToCachedCompilerArgumentsBucketConverter(mapper) }

    override fun convert(from: RawCompilerArgumentsBucket): CachedCompilerArgumentsBucket =
        rawToFlatCompilerArgumentsBucketConverter.convert(from).let {
            flatToCachedCompilerArgumentsBucketConverter.convert(it)
        }
}
