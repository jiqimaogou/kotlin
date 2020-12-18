/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.caching

import java.io.Serializable

typealias ClasspathArgumentsType = List<String>
typealias RawCompilerArgumentsBucket = List<String>

interface CompilerArgumentsBucket<T> : Serializable {
    var classpathParts: Pair<T, List<T>>?
    val singleArguments: MutableMap<T, T>
    val multipleArguments: MutableMap<T, List<T>>
    val flagArguments: MutableList<T>
    val internalArguments: MutableList<T>
    val freeArgs: MutableList<T>
}

class FlatCompilerArgumentsBucket(
    override var classpathParts: Pair<String, List<String>>? = null,
    override val singleArguments: MutableMap<String, String> = mutableMapOf(),
    override val multipleArguments: MutableMap<String, List<String>> = mutableMapOf(),
    override val flagArguments: MutableList<String> = mutableListOf(),
    override val internalArguments: MutableList<String> = mutableListOf(),
    override val freeArgs: MutableList<String> = mutableListOf()
) : CompilerArgumentsBucket<String> {
    constructor(other: FlatCompilerArgumentsBucket) : this(
        other.classpathParts,
        other.singleArguments.toMutableMap(),
        other.multipleArguments.toMutableMap(),
        other.flagArguments.toMutableList(),
        other.internalArguments.toMutableList(),
        other.freeArgs.toMutableList()
    )
}

val FlatCompilerArgumentsBucket.isEmpty: Boolean
    get() = classpathParts == null && singleArguments.isEmpty() && multipleArguments.isEmpty()
            && flagArguments.isEmpty() && freeArgs.isEmpty() && internalArguments.isEmpty()