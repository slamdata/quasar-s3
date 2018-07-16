/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.s3

import eu.timepit.refined.auto._
import fs2.Stream
import org.http4s.Request
import org.http4s.client.Client
import quasar.Data
import quasar.api.ResourceError.{CommonError, ReadError}
import quasar.api.ResourceError
import quasar.api.ResourcePath.{Leaf, Root}
import quasar.api.{DataSourceType, ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.datasource.LightweightDataSource
import quasar.contrib.pathy.APath
import quasar.contrib.cats.effect._
import pathy.Path
import Path.{DirName, FileName}
import slamdata.Predef.{Stream => _, _}

import cats.effect.{Effect, Async, Timer}
import cats.arrow.FunctionK
import cats.syntax.flatMap._
import scalaz.{\/, \/-, -\/}
import scalaz.syntax.applicative._
import scalaz.syntax.either._

import shims._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.SECONDS
import java.time.{ZoneOffset, LocalDateTime}

final class S3DataSource[F[_]: Effect: Timer, G[_]: Async] (
  client: Client[F],
  config: S3Config)
    extends LightweightDataSource[F, Stream[G, ?], Stream[G, Data]] {

  def kind: DataSourceType = DataSourceType("s3", 1L)

  val shutdown: F[Unit] = client.shutdown

  def evaluate(path: ResourcePath): F[ReadError \/ Stream[G, Data]] =
    path match {
      case Root =>
        ResourceError.notAResource(path).left.point[F]
      case Leaf(file) =>
        impl.evaluate[F](config.parsing, client, config.bucket, file, S3DataSource.signRequest(config)) map {
        case None => (ResourceError.pathNotFound(Leaf(file)): ReadError).left
        /* In http4s, the type of streaming results is the same as
         every other effectful operation. However,
         LightweightDataSourceModule forces us to separate the types,
         so we need to translate */
        case Some(s) => s.translate[G](FToG).right[ReadError]
      }
    }

  def children(path: ResourcePath): F[CommonError \/ Stream[G, (ResourceName, ResourcePathType)]] = {
    impl.children(client, config.bucket, dropEmpty(path.toPath), S3DataSource.signRequest(config)) map {
      case None =>
        ResourceError.pathNotFound(path).left[Stream[G, (ResourceName, ResourcePathType)]]
      case Some(paths) =>
        Stream.emits(paths.toList.map {
          case -\/(Path.DirName(dn)) => (ResourceName(dn), ResourcePathType.ResourcePrefix)
          case \/-(Path.FileName(fn)) => (ResourceName(fn), ResourcePathType.Resource)
        }).covary[G].right[CommonError]
    }
  }

  def isResource(path: ResourcePath): F[Boolean] = path match {
    case Root => false.pure[F]
    case Leaf(file) => Path.refineType(dropEmpty(file)) match {
      case -\/(_) => false.pure[F]
      case \/-(f) => impl.isResource(client, config.bucket, f, S3DataSource.signRequest(config))
    }
  }

  private def dropEmpty(path: APath): APath =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) if fn.isEmpty => d
      case Some((d, -\/(DirName(dn)))) if dn.isEmpty => d
      case Some(_) => path
      case None => path
    }

  private val FToG: FunctionK[F, G] = new FunctionK[F, G] {
    def apply[A](fa: F[A]): G[A] = fa.to[G]
  }
}

object S3DataSource {
  def signRequest[F[_]: Effect: Timer](c: S3Config): Request[F] => F[Request[F]] = {
    c.credentials match {
      case Some(creds) => {
        val requestSigning = for {
          seconds <- Timer[F].clockRealTime(SECONDS)
          datetime <- Effect[F].catchNonFatal(LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC))
          signing = RequestSigning(
            Credentials(creds.accessKey, creds.secretKey, None),
            Region(creds.region),
            ServiceName.S3,
            PayloadSigning.Signed,
            datetime)
        } yield signing

        req => requestSigning >>= (s => s.signedHeaders[F](req).map(h => req.withHeaders(h)))
      }
      case None => req => req.pure[F]
    }
  }
}
