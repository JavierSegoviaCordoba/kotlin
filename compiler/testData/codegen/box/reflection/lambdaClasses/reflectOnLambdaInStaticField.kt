// !OPT_IN: kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
// TARGET_BACKEND: JVM
// WITH_REFLECT
// LAMBDAS: CLASS

import kotlin.reflect.jvm.reflect

val x = { OK: String -> }

fun box(): String {
    return x.reflect()?.parameters?.singleOrNull()?.name ?: "null"
}
