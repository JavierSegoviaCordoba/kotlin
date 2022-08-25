/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jvm.internal

/**
 * Suppresses Animal Sniffer verification errors for certain scope.
 * Such scopes include references to Java 8 API that are not available in Android API,
 * but can be desugared by R8 or their execution is prevented on Android platform.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
internal annotation class SuppressAnimalSniffer
