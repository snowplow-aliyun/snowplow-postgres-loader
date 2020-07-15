/*
 * Copyright (c) 2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.postgres.storage

import cats.data.EitherT
import cats.implicits._

import cats.effect.concurrent.Ref
import cats.effect.{Sync, Clock}

import doobie.implicits._
import doobie.util.log.LogHandler
import doobie.util.transactor.Transactor

import com.snowplowanalytics.iglu.core.SchemaKey

import com.snowplowanalytics.iglu.client.resolver.Resolver

import com.snowplowanalytics.iglu.schemaddl.ModelGroup
import com.snowplowanalytics.iglu.schemaddl.migrations.SchemaList

import com.snowplowanalytics.snowplow.badrows.FailureDetails.LoaderIgluError
import com.snowplowanalytics.snowplow.postgres.loader._
import com.snowplowanalytics.snowplow.postgres.storage.PgState.TableState
import com.snowplowanalytics.snowplow.postgres.shredding.transform.Atomic
import com.snowplowanalytics.snowplow.postgres.shredding.schema

/**
 * State of the DB schema, where every `ModelGroup` (read "table")
 * is associated with list of schemas. Each of these schemas is reflected
 * in the structure of the table. If `SchemaKey` matches the `ModelGroup`,
 * but not associated with it - the table is outdated. After table has been
 * migrated to reflect the newest schema - state need to be updated up to
 * that schema
 */
case class PgState(tables: Map[ModelGroup, SchemaList]) {
  /**
   * Check if `SchemaKey` is known to the state
   * @param entity `SchemaKey` taken from table comment
   * @return one of three possible tables states
   */
  private[postgres] def check(entity: SchemaKey): TableState = {
    val group = (entity.vendor, entity.name, entity.version.model)

    group match {
      case (Atomic.vendor, Atomic.name, Atomic.version.model) =>
        TableState.Match
      case _ => tables.get(group) match {
        case Some(SchemaList.Full(schemas)) =>
          if (schemas.toList.map(_.self.schemaKey).contains(entity)) TableState.Match else TableState.Outdated
        case Some(SchemaList.Single(schema)) =>
          if (schema.self.schemaKey === entity) TableState.Match else TableState.Outdated
        case None =>
          TableState.Missing
      }
    }
  }

  /** Add a whole `SchemaList` to the state (replace if it exists) */
  def put(list: SchemaList): PgState = {
    val entity = list.latest.schemaKey
    val modelGroup = (entity.vendor, entity.name, entity.version.model)
    PgState(tables ++ Map(modelGroup -> list))
  }
}

object PgState {
  /**
   * Initialize internal mutable state by traversing all table comments to get their latest version
   * For every schema URI, the whole list will be fetched to keep ordering consistent
   * All newer versions (present on registry, but not reflected on table) will be dropped
   *
   * @param xa DB transactor
   * @param logger doobie logger
   * @param resolver Iglu Resolver attached to Iglu Server
   * @param pgSchema database schema
   * @return a list of potential schema issues (not fatal errors, to be logged) and
   *         an actual mutable reference with the state
   */
  def init[F[_]: Sync: Clock](xa: Transactor[F], logger: LogHandler, resolver: Resolver[F], pgSchema: String) =
    EitherT.liftF(query.getComments(pgSchema, logger).transact(xa)).flatMap { comments =>
      val initial = PgState(Map.empty)
      val (issues, keys) = comments.separate
      val availableSchemas = keys.traverse { key =>
        EitherT(resolver.listSchemas(key.vendor, key.name, key.version.model))
          .leftMap { resolutionError => LoaderIgluError.IgluError(key, resolutionError)  }
          .flatMap { schemaKeyList => SchemaList.fromSchemaList(schemaKeyList, schema.fetch(resolver)) }
          .map { list => list.until(key) match {
            case Some(updatedList) => updatedList
            case None => throw new IllegalStateException(s"SchemaList $list doesn't match vendor of ${key.toSchemaUri}")
          } }
      }
      availableSchemas
        .map { list => list.foldLeft(initial) { (acc, cur) => acc.put(cur) }  }
        .flatMap { state => EitherT.liftF[F, LoaderIgluError, Ref[F, PgState]](Ref.of[F, PgState](state)) }
        .map { state => (issues.filterNot { issue => issue === CommentIssue.Missing("events") }, state) }
    }

  private[postgres] sealed trait TableState extends Product with Serializable
  private[postgres] object TableState {
    case object Match extends TableState
    case object Outdated extends TableState
    case object Missing extends TableState
  }
}
