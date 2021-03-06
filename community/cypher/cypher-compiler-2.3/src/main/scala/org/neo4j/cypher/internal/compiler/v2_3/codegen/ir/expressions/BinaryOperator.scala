/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.codegen.ir.expressions

import org.neo4j.cypher.internal.compiler.v2_3.codegen.{CodeGenContext, MethodStructure}
import org.neo4j.cypher.internal.compiler.v2_3.symbols._

trait BinaryOperator {
  self: CodeGenExpression =>

  def lhs: CodeGenExpression
  def rhs: CodeGenExpression

  override final def init[E](generator: MethodStructure[E])(implicit context: CodeGenContext) = {
    lhs.init(generator)
    rhs.init(generator)
  }

  override final def generateExpression[E](structure: MethodStructure[E])(implicit context: CodeGenContext) =
    generator(structure)(context)(lhs.generateExpression(structure), rhs.generateExpression(structure))

  protected def generator[E](structure: MethodStructure[E])(implicit context: CodeGenContext): (E, E) => E
}

// Trait that resolves type based on inputs.
trait NumericalOpType {
  self : CodeGenExpression =>

  def lhs: CodeGenExpression
  def rhs: CodeGenExpression

  override def cypherType(implicit context: CodeGenContext) =
    (lhs.cypherType, rhs.cypherType) match {
      case (CTInteger, CTInteger) => CTInteger
      case (_: NumberType, _: NumberType) => CTFloat
      // Runtime we'll figure it out - can't store it in a primitive field unless we are 100% of the type
      case _ => CTAny
    }
}
