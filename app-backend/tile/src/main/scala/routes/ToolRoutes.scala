package com.azavea.rf.tile.routes

import com.azavea.rf.common._
import com.azavea.rf.common.ast._
import com.azavea.rf.database.Database
import com.azavea.rf.datamodel.User
import com.azavea.rf.tile._
import com.azavea.rf.tile.image._
import com.azavea.rf.tile.tool._
import com.azavea.rf.tool.ast._
import com.azavea.rf.tool.eval._
import com.azavea.rf.tool.maml._

import com.azavea.maml.ast._
import com.azavea.maml.eval._
import com.azavea.maml.eval.directive._
import com.azavea.maml.util._
import com.azavea.maml.serve._
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import geotrellis.raster._
import geotrellis.raster.render._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.server._
import cats.data.Validated._
import cats.data.{NonEmptyList => NEL, _}
import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import java.util.UUID


class ToolRoutes(implicit val database: Database) extends Authentication
  with LazyLogging
  with InterpreterExceptionHandling
  with CommonHandlers
  with KamonTraceDirectives {

  val userId: String = "rf_airflow-user"

  val providedRamps = Map(
    "viridis" -> geotrellis.raster.render.ColorRamps.Viridis,
    "inferno" -> geotrellis.raster.render.ColorRamps.Inferno,
    "magma" -> geotrellis.raster.render.ColorRamps.Magma,
    "lightYellowToOrange" -> geotrellis.raster.render.ColorRamps.LightYellowToOrange,
    "classificationBoldLandUse" -> geotrellis.raster.render.ColorRamps.ClassificationBoldLandUse
  )

  implicit val pngMarshaller: ToEntityMarshaller[Png] = {
    val contentType = ContentType(MediaTypes.`image/png`)
    Marshaller.withFixedContentType(contentType) { png ⇒ HttpEntity(contentType, png.bytes) }
  }

  def parseBreakMap(str: String): Map[Double,Double] = {
    str.split(';').map { c: String =>
      val Array(a, b) = c.trim.split(':').map(_.toDouble)
      (a, b)
    }.toMap
  }

  /** Endpoint to be used for kicking the histogram cache and ensuring tiles are quickly loaded */
  def preflight(toolRunId: UUID, user: User) = {
    traceName("toolrun-preflight") {
      parameter(
        'node.?,
        'voidCache.as[Boolean].?(false)
      ) { (node, void) =>
        val nodeId = node.map(UUID.fromString(_))
        onSuccess(LayerCache.toolEvalRequirements(toolRunId, nodeId, user, void).value) { _ =>
          complete {
            StatusCodes.NoContent
          }
        }
      }
    }
  }

  /** Endpoint used to verify that a [[com.azavea.rf.datamodel.ToolRun]] is sufficient to
    *  evaluate the [[com.azavea.rf.datamodel.Tool]] to which it refers
    */
  def validate(toolRunId: UUID, user: User) = {
    traceName("toolrun-validate") {
      pathPrefix("validate") {
        complete {
          for {
            ast <- LayerCache.toolEvalRequirements(toolRunId, None, user)
          } yield validateTreeWithSources[Unit](ast)
          StatusCodes.NoContent
        }
      }
    }
  }

  /** Endpoint used to get a [[com.azavea.rf.datamodel.ToolRun]] histogram */
  def histogram(toolRunId: UUID, user: User) = {
    traceName("toolrun-histogram") {
      pathPrefix("histogram") {
        parameter(
          'node.?,
          'voidCache.as[Boolean].?(false)
        ) { (node, void) =>
          complete {
            val nodeId = node.map(UUID.fromString(_))
            LayerCache.modelLayerGlobalHistogram(toolRunId, nodeId, user, void).value
          }
        }
      }
    }
  }

  /** Endpoint used to get a [[ToolRun]] statistics */
  def statistics(toolRunId: UUID, user: User) = {
    traceName("toolrun-statistics") {
      pathPrefix("statistics") {
        parameter(
          'node.?,
          'voidCache.as[Boolean].?(false)
        ) { (node, void) =>
          complete {
            val nodeId = node.map(UUID.fromString(_))
            LayerCache.modelLayerGlobalHistogram(toolRunId, nodeId, user, void).mapFilter(_.statistics).value
          }
        }
      }
    }
  }

  val tileResolver = new RfmlTileResolver(implicitly[Database], implicitly[ExecutionContext])
  val tmsInterpreter = Interpreter.buffering(
    ScopedDirective.pure[TileLiteral](SourceDirectives.tileLiteral),
    ScopedDirective.pure[IntLiteral](SourceDirectives.intLiteral),
    ScopedDirective.pure[FocalMax](FocalDirectives.focalMax),
    ScopedDirective.pure[Addition](OpDirectives.additionTile orElse OpDirectives.additionInt orElse OpDirectives.additionDouble),
    ScopedDirective.pure[Equal](OpDirectives.equalTo)
  )



  /** The central endpoint for ModelLab; serves TMS tiles given a [[ToolRun]] specification */
  def tms(
    toolRunId: UUID, user: User
  ): Route =
    (handleExceptions(interpreterExceptionHandler) & handleExceptions(circeDecodingError)) {
      traceName("toolrun-tms") {
        pathPrefix(IntNumber / IntNumber / IntNumber) { (z, x, y) =>
          parameter(
            'node.?,
            'cramp.?("viridis")
          ) { (node, colorRampName) =>
            val nodeId = node.map(UUID.fromString(_))
            val colorRamp = providedRamps.get(colorRampName).getOrElse(providedRamps("viridis"))
            val components = for {
              (expression, md) <- LayerCache.toolEvalRequirements(toolRunId, nodeId, user).map(_.asMaml)
              cMap  <- LayerCache.toolRunColorMap(toolRunId, nodeId, user, colorRamp, colorRampName)
            } yield (expression, md, cMap)

            complete {
              components.value.flatMap({ data =>
                val result: Future[Option[Png]] = data match {
                  case Some((expression, md, cMap)) =>
                    val litTree = tileResolver.resolveBuffered(expression)(z, x, y)
                    litTree.map({ resolvedAst =>
                      resolvedAst
                        .andThen({ tmsInterpreter(_) })
                        .andThen({ _.as[Tile] }) match {
                          case Valid(tile) =>
                            logger.debug(s"Tile successfully produced at $z/$x/$y")
                            md.flatMap({ meta =>
                              meta.renderDef.map({ renderDef => tile.renderPng(renderDef) })
                            }).orElse({
                              Some(tile.renderPng(cMap))
                            })
                          case Invalid(nel) =>
                            throw new InterpreterException(nel)
                        }
                    })
                  case _ => Future.successful(None)
                }
                result
              })
            }
          }
        }
      }
    }
}
