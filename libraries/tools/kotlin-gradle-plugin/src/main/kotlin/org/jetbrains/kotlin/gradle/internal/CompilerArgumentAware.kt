/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.compilerRunner.ArgumentUtils
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

interface CompilerArgumentAware<T : CommonToolArguments> {
    val serializedCompilerArguments: List<String>
        get() = ArgumentUtils.convertArgumentsToStringList(prepareCompilerArguments())

    val serializedCompilerArgumentsIgnoreClasspathIssues: List<String>
        get() = ArgumentUtils.convertArgumentsToStringList(prepareCompilerArguments(ignoreClasspathResolutionErrors = true))

    val flatCompilerArgumentsBucket: List<Any?>
        get() = calculateFlatArgsBucket()

    val flatCompilerArgumentsBucketIgnoreClasspathIssues: List<Any?>
        get() = calculateFlatArgsBucket(ignoreClasspathResolutionErrors = true)

    val defaultSerializedCompilerArguments: List<String>
        get() = createCompilerArgs()
            .also { setupCompilerArgs(it, defaultsOnly = true) }
            .let(ArgumentUtils::convertArgumentsToStringList)

    val filteredArgumentsMap: Map<String, String>
        get() = CompilerArgumentsGradleInput.createInputsMap(prepareCompilerArguments())

    fun createCompilerArgs(): T
    fun setupCompilerArgs(args: T, defaultsOnly: Boolean = false, ignoreClasspathResolutionErrors: Boolean = false)
}

internal fun <T : CommonToolArguments> CompilerArgumentAware<T>.prepareCompilerArguments(ignoreClasspathResolutionErrors: Boolean = false) =
    createCompilerArgs().also { setupCompilerArgs(it, ignoreClasspathResolutionErrors = ignoreClasspathResolutionErrors) }

internal fun <T : CommonToolArguments> CompilerArgumentAware<T>.calculateFlatArgsBucket(ignoreClasspathResolutionErrors: Boolean = false): List<Any?> {
    val compilerArguments = prepareCompilerArguments(ignoreClasspathResolutionErrors = ignoreClasspathResolutionErrors)
    val propertiesToArgumentAnnotation = compilerArguments::class.java.kotlin.memberProperties
        .filter { it.annotations.any { anno -> anno is Argument } && it != K2JVMCompilerArguments::classpath && it != K2MetadataCompilerArguments::classpath }
        .associateWith { it.annotations.first { anno -> anno is Argument } as Argument }

    val flagPropertiesMap = propertiesToArgumentAnnotation.filterKeys { it.returnType.classifier == Boolean::class }.mapNotNull {
        it.key.safeAs<KProperty1<CommonToolArguments, Boolean>>()?.let { cs -> cs to it.value }
    }
    val flatFlags = flagPropertiesMap.filter { it.first.get(compilerArguments) }.map { it.second.value }

    val singleNullablePropertiesMap =
        propertiesToArgumentAnnotation.filterKeys { it.returnType.classifier == String::class && it.returnType.isMarkedNullable }
            .mapNotNull {
                it.key.safeAs<KProperty1<CommonToolArguments, String?>>()?.let { cs -> cs to it.value }
            }
    val singleNullableArguments = singleNullablePropertiesMap.mapNotNull {
        it.first.get(compilerArguments)?.let { arg ->
            it.second.value to arg
        }
    }.toMap()

    val singlePropertiesMap =
        propertiesToArgumentAnnotation.filterKeys { it.returnType.classifier == String::class && !it.returnType.isMarkedNullable }
            .mapNotNull {
                it.key.safeAs<KProperty1<CommonToolArguments, String>>()?.let { cs -> cs to it.value }
            }
    val singleArguments = singlePropertiesMap.mapNotNull { it.second.value to it.first.get(compilerArguments) }.toMap()


    val multiplePropsToValues = propertiesToArgumentAnnotation.filterKeys { it.returnType.classifier?.javaClass?.isArray == true }
        .mapNotNull {
            it.safeAs<KProperty1<CommonToolArguments, Array<String>?>>()?.let { cs -> cs to it.value }
        }
    val multipleArguments = multiplePropsToValues.mapNotNull {
        it.first.get(compilerArguments)?.let { arg ->
            it.second.value to arg.toList()
        }
    }.toMap()

    val classpathArguments = when (compilerArguments) {
        is K2MetadataCompilerArguments -> compilerArguments.classpath?.let { "-classpath" to it.split(File.pathSeparator) }
        is K2JVMCompilerArguments -> compilerArguments.classpath?.let { "-classpath" to it.split(File.pathSeparator) }
        else -> null
    }

    return listOf(
        classpathArguments,
        (singleNullableArguments + singleArguments).toMutableMap(),
        multipleArguments.toMutableMap(),
        flatFlags.toMutableList(),
        compilerArguments.internalArguments.map { it.stringRepresentation }.toMutableList(),
        compilerArguments.freeArgs.toMutableList()
    )
}


interface CompilerArgumentAwareWithInput<T : CommonToolArguments> : CompilerArgumentAware<T> {
    @get:Internal
    override val serializedCompilerArguments: List<String>
        get() = super.serializedCompilerArguments

    @get:Internal
    override val flatCompilerArgumentsBucket: List<Any?>
        get() = super.flatCompilerArgumentsBucket

    @get:Internal
    override val flatCompilerArgumentsBucketIgnoreClasspathIssues: List<Any?>
        get() = super.flatCompilerArgumentsBucketIgnoreClasspathIssues

    @get:Internal
    override val defaultSerializedCompilerArguments: List<String>
        get() = super.defaultSerializedCompilerArguments

    @get:Internal
    override val serializedCompilerArgumentsIgnoreClasspathIssues: List<String>
        get() = super.serializedCompilerArgumentsIgnoreClasspathIssues

    @get:Input
    override val filteredArgumentsMap: Map<String, String>
        get() = super.filteredArgumentsMap
}