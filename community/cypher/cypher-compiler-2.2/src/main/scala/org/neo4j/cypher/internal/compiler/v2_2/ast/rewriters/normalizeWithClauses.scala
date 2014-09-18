/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.cypher.internal.compiler.v2_2.ast.rewriters

import org.neo4j.cypher.internal.compiler.v2_2.ast._
import org.neo4j.cypher.internal.compiler.v2_2.helpers.FreshIdNameGenerator
import org.neo4j.cypher.internal.compiler.v2_2.{Rewriter, bottomUp, topDown}

/**
 * This rewriter normalizes the scoping structure of a query, ensuring it is able to
 * be correctly processed for semantic checking. It makes sure that all return items
 * in a WITH clauses are aliased, and ensures all ORDER BY and WHERE expressions are
 * shifted into the clause, leaving only an identifier. That identifier must also
 * appear as an alias in the associated WITH.
 *
 * This rewriter depends on normalizeReturnClauses having first been run.
 *
 * Example:
 *
 * MATCH n
 * WITH n.prop AS prop ORDER BY n.foo DESC
 * RETURN prop
 *
 * This rewrite will change the query to:
 *
 * MATCH n
 * WITH n AS n, n.prop AS prop
 * WITH prop AS prop, n.foo AS `  FRESHID39` ORDER BY `  FRESHID39` DESC
 * WITH prop AS prop
 * RETURN prop
 *
 * It uses multiple WITH clauses to ensure that cardinality and grouping are not altered, even in the presence
 * of aggregation.
 */
case object normalizeWithClauses extends Rewriter {

  def apply(that: AnyRef): Option[AnyRef] = bottomUp(instance).apply(that)

  private val clauseRewriter: (Clause => Seq[Clause]) = {
    case clause @ With(_, returnItemList: ListedReturnItems, None, _, _, None) =>
      val (unaliasedReturnItems, aliasedReturnItems) = partitionReturnItems(returnItemList.items)
      val initialReturnItems = unaliasedReturnItems ++ aliasedReturnItems
      Seq(clause.copy(returnItems = ListedReturnItems(initialReturnItems)(returnItemList.position))(clause.position))

    case clause @ With(distinct, returnItemList: ListedReturnItems, orderBy, skip, limit, where) =>
      val (unaliasedReturnItems, aliasedReturnItems) = partitionReturnItems(returnItemList.items)
      val initialReturnItems = unaliasedReturnItems ++ aliasedReturnItems
      val (additionalReturnItems, updatedOrderBy, updatedWhere) = aliasOrderByAndWhere(aliasedReturnItems.map(i => i.expression -> i.alias.get).toMap, orderBy, where)

      if (orderBy == updatedOrderBy && where == updatedWhere) {
        Seq(clause.copy(returnItems = ListedReturnItems(initialReturnItems)(returnItemList.position))(clause.position))
      } else if (additionalReturnItems.isEmpty) {
        Seq(clause.copy(returnItems = ListedReturnItems(initialReturnItems)(returnItemList.position), orderBy = updatedOrderBy, where = updatedWhere)(clause.position))
      } else {
        val finalProjection = initialReturnItems.map(item =>
          item.alias.fold(item)(alias => AliasedReturnItem(alias, alias)(item.position))
        )
        val secondProjection = finalProjection ++ additionalReturnItems
        val firstProjection = if (distinct || returnItemList.containsAggregate) {
          initialReturnItems
        } else {
          val requiredIdentifiers = additionalReturnItems.map(_.expression.dependencies).flatten.toSet diff initialReturnItems.flatMap(_.alias).toSet
          requiredIdentifiers.toVector.map(i => AliasedReturnItem(i, i)(i.position)) ++ initialReturnItems
        }

        Seq(
          With(distinct = distinct, returnItems = ListedReturnItems(firstProjection)(returnItemList.position), orderBy = None, skip = None, limit = None, where = None)(clause.position),
          With(distinct = false, returnItems = ListedReturnItems(secondProjection)(returnItemList.position), orderBy = updatedOrderBy, skip = skip, limit = limit, where = updatedWhere)(clause.position),
          With(distinct = false, returnItems = ListedReturnItems(finalProjection)(returnItemList.position), orderBy = None, skip = None, limit = None, where = None)(clause.position)
        )
      }

    case clause =>
      Seq(clause)
  }

  private def partitionReturnItems(returnItems: Seq[ReturnItem]): (Seq[ReturnItem], Seq[AliasedReturnItem]) =
    returnItems.foldLeft((Vector.empty[ReturnItem], Vector.empty[AliasedReturnItem])) {
      case ((unaliasedItems, aliasedItems), item) => item match {
        case i: AliasedReturnItem =>
          (unaliasedItems, aliasedItems :+ i)

        case i if i.alias.isDefined =>
          (unaliasedItems, aliasedItems :+ AliasedReturnItem(item.expression, item.alias.get)(item.position))

        case _ =>
          // Unaliased return items in WITH will be preserved so that semantic check can report them as an error
          (unaliasedItems :+ item, aliasedItems)
      }
    }

  private def aliasOrderByAndWhere(existingAliases: Map[Expression, Identifier], orderBy: Option[OrderBy], where: Option[Where]): (Seq[ReturnItem], Option[OrderBy], Option[Where]) = {
    val (additionalReturnItemsForOrderBy, updatedOrderBy) = orderBy match {
      case Some(o) =>
        val (returnItems, updatedOrderBy) = aliasOrderBy(existingAliases, o)
        (returnItems, Some(updatedOrderBy))

      case None =>
        (Seq.empty, None)
    }

    val (maybeReturnItemForWhere, updatedWhere) = where match {
      case Some(w) =>
        val (maybeReturnItem, updatedWhere) = aliasWhere(existingAliases, w)
        (maybeReturnItem, Some(updatedWhere))

      case None =>
        (None, None)
    }

    (additionalReturnItemsForOrderBy ++ maybeReturnItemForWhere, updatedOrderBy, updatedWhere)
  }

  private def aliasOrderBy(existingAliases: Map[Expression, Identifier], originalOrderBy: OrderBy): (Seq[AliasedReturnItem], OrderBy) = {
    val (additionalReturnItems, updatedSortItems) = originalOrderBy.sortItems.foldLeft((Vector.empty[AliasedReturnItem], Vector.empty[SortItem])) {
      case ((returnItems, sortItems), item) => item.expression match {
        case _: Identifier =>
          (returnItems, sortItems :+ item)

        case e: Expression =>
          val (maybeReturnItem, sortItem) = aliasSortItem(existingAliases, item)
          maybeReturnItem match {
            case Some(i) if !i.expression.containsAggregate =>
              (returnItems :+ i, sortItems :+ sortItem)
            case Some(i) =>
              (returnItems, sortItems :+ item)
            case None =>
              (returnItems, sortItems :+ sortItem)
          }
      }
    }
    (additionalReturnItems, OrderBy(updatedSortItems)(originalOrderBy.position))
  }

  private def aliasSortItem(existingAliases: Map[Expression, Identifier], sortItem: SortItem): (Option[AliasedReturnItem], SortItem) = {
    val expression = sortItem.expression
    val (maybeReturnItem, replacementIdentifier) = aliasExpression(existingAliases, expression)

    val newSortItem = sortItem.endoRewrite(topDown(Rewriter.lift {
      case e: Expression if e == expression =>
        replacementIdentifier
    }))
    (maybeReturnItem, newSortItem)
  }

  private def aliasWhere(existingAliases: Map[Expression, Identifier], originalWhere: Where): (Option[ReturnItem], Where) = {
    originalWhere.expression match {
      case _: Identifier =>
        (None, originalWhere)

      case e: Expression if !e.containsAggregate =>
        val (maybeReturnItem, replacementIdentifier) = aliasExpression(existingAliases, e)
        (maybeReturnItem, Where(replacementIdentifier)(originalWhere.position))

      case e =>
        (None, originalWhere)
    }
  }

  private def aliasExpression(existingAliases: Map[Expression, Identifier], expression: Expression): (Option[AliasedReturnItem], Identifier) = {
    existingAliases.get(expression) match {
      case Some(alias) =>
        (None, alias)

      case None =>
        val newIdentifier = Identifier(FreshIdNameGenerator.name(expression.position))(expression.position)
        val newExpression = expression.endoRewrite(topDown(Rewriter.lift {
          case e: Expression =>
            existingAliases.getOrElse(e, e)
        }))
        val newReturnItem = AliasedReturnItem(newExpression, newIdentifier)(expression.position)
        (Some(newReturnItem), newIdentifier)
    }
  }

  private val instance: Rewriter = Rewriter.lift {
    case query @ SingleQuery(clauses) =>
      query.copy(clauses = clauses.flatMap(clauseRewriter))(query.position)
  }
}