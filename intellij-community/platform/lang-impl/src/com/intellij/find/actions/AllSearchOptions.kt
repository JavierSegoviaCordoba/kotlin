// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.find.actions

import com.intellij.find.usages.UsageOptions

internal data class AllSearchOptions<O>(
  val options: UsageOptions,
  val textSearch: Boolean?,
  val customOptions: O
)
