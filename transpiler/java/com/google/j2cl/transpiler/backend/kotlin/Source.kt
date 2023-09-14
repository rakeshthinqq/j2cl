/*
 * Copyright 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.commaAndNewLineSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.commaSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.inNewLine
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.inParentheses
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.inParenthesesIfNotEmpty
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.indented
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.infix
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.join
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.source
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.spaceSeparated

fun literalSource(it: Boolean): Source = source("$it")

fun literalSource(it: Char): Source = source(it.literalString)

fun literalSource(it: String): Source = source(it.literalString)

fun literalSource(it: Int): Source = source("$it")

fun literalSource(it: Long): Source =
  when (it) {
    // Long.MIN_VALUE can not be represented as a literal in Kotlin.
    Long.MIN_VALUE -> inParentheses(infix(literalSource(Long.MAX_VALUE), "+", literalSource(1L)))
    else -> source("${it}L")
  }

fun literalSource(it: Float): Source =
  if (it.isNaN()) inParentheses(infix(literalSource(0f), "/", literalSource(0f)))
  else
    when (it) {
      Float.NEGATIVE_INFINITY -> inParentheses(infix(literalSource(-1f), "/", literalSource(0f)))
      Float.POSITIVE_INFINITY -> inParentheses(infix(literalSource(1f), "/", literalSource(0f)))
      else -> source("${it}f")
    }

fun literalSource(it: Double): Source =
  if (it.isNaN()) inParentheses(infix(literalSource(0.0), "/", literalSource(0.0)))
  else
    when (it) {
      Double.NEGATIVE_INFINITY -> inParentheses(infix(literalSource(-1.0), "/", literalSource(0.0)))
      Double.POSITIVE_INFINITY -> inParentheses(infix(literalSource(1.0), "/", literalSource(0.0)))
      else -> source("$it")
    }

fun assignment(lhs: Source, rhs: Source): Source = infix(lhs, "=", rhs)

fun initializer(expr: Source): Source = expr.ifNotEmpty { spaceSeparated(source("="), expr) }

fun at(source: Source) = join(source("@"), source)

fun labelReference(name: String) = at(identifierSource(name))

fun classLiteral(type: Source) = join(type, source("::class"))

fun nonNull(type: Source) = join(type, source("!!"))

fun asExpression(lhs: Source, rhs: Source) = infix(lhs, "as", rhs)

fun isExpression(lhs: Source, rhs: Source) = infix(lhs, "is", rhs)

fun itSource() = source("it")

fun todo(source: Source) = join(source("TODO"), inParentheses(source))

fun annotation(name: Source) = join(at(name))

fun annotation(name: Source, parameter: Source, vararg parameters: Source) =
  join(at(name), inParenthesesIfNotEmpty(commaSeparated(parameter, *parameters)))

fun annotation(name: Source, parameters: List<Source>) =
  join(
    at(name),
    inParentheses(
      if (parameters.size <= 2) commaSeparated(parameters)
      else indented(inNewLine(commaAndNewLineSeparated(parameters)))
    )
  )

fun fileAnnotation(name: Source, parameters: List<Source>) =
  annotation(join(source("file:"), name), parameters)
