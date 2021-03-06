package com.azavea.rf.tile

import com.azavea.rf.tile.tool.TileSources
import com.azavea.rf.datamodel.{Tool, ToolRun, User}
import com.azavea.rf.tool.eval._
import com.azavea.rf.tool.ast._
import com.azavea.rf.tool.ast.MapAlgebraAST
import com.azavea.rf.common.cache._
import com.azavea.rf.common.cache.kryo.KryoMemcachedClient
import com.azavea.rf.database.Database
import com.azavea.rf.database.tables._
import com.azavea.rf.common.{Config => CommonConfig}

import io.circe.syntax._
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.spark.io._
import geotrellis.spark._
import geotrellis.spark.io.s3.{S3CollectionLayerReader, S3ValueReader}
import geotrellis.spark.io.postgres.PostgresAttributeStore
import geotrellis.vector.Extent
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import cats.data._
import cats.data.Validated._
import cats.implicits._
import java.util.UUID

import scala.concurrent._
import scala.util._

/**
  * ValueReaders need to read layer metadata in order to know how to decode (x/y) queries into resource reads.
  * In this case it requires reading JSON files from S3, which are cached in the reader.
  * Naturally we want to cache this access to prevent every tile request from re-fetching layer metadata.
  * Same logic applies to other layer attributes like layer Histogram.
  *
  * Things that are cheap to construct but contain internal state we want to re-use use LoadingCache.
  * things that require time to generate, usually a network fetch, use AsyncLoadingCache
  */
object LayerCache extends Config with LazyLogging with KamonTrace {
  implicit val database = Database.DEFAULT
  val system = AkkaSystem.system
  implicit val blockingDispatcher = system.dispatchers.lookup("blocking-dispatcher")

  lazy val memcachedClient = KryoMemcachedClient.DEFAULT

  val rfCache = new CacheClient(memcachedClient)
  val store = PostgresAttributeStore()

  val cacheConfig = CommonConfig.memcached

  def maxZoomForLayer(layerId: UUID)(implicit ec: ExecutionContext, projectLayerIds: Set[UUID]): OptionT[Future, Map[String, Int]] = {
    val cacheKey = s"max-zoom-for-layer-${layerId}"
    rfCache.cachingOptionT(cacheKey, doCache = cacheConfig.layerAttributes.enabled)(
      OptionT(
        timedFuture("layer-max-zoom-store")(store.maxZoomsForLayers(projectLayerIds.map(_.toString)))
      )
    )
  }

  def layerHistogram(layerId: UUID, zoom: Int): OptionT[Future, Array[Histogram[Double]]] = {
    val key = s"layer-histogram-${layerId}-${zoom}"
    rfCache.cachingOptionT(key, doCache = cacheConfig.layerAttributes.enabled)(
      OptionT(
        timedFuture("layer-histogram-source")(store.getHistogram[Array[Histogram[Double]]](LayerId(layerId.toString, 0)))
      )
    )
  }

  def layerTile(layerId: UUID, zoom: Int, key: SpatialKey)(implicit projectLayerIds: Set[UUID]): OptionT[Future, MultibandTile] = {
    val cacheKey = s"tile-$layerId-$zoom-${key.col}-${key.row}"
    OptionT(rfCache.caching(cacheKey, doCache = cacheConfig.layerTile.enabled)(
      timedFuture("s3-tile-request")({
        val reader = new S3ValueReader(store).reader[SpatialKey, MultibandTile](LayerId(layerId.toString, zoom))
        Future(reader.read(key)).map({
          case tile => Some(tile)
        }).recover({
          case e: ValueNotFoundError => None
          case e: Throwable =>
            logger.debug(s"Unable to retrieve layer $layerId at zoom $zoom for key $key; ${e.getMessage}")
            None
        })
      })
    ))
  }


  def layerTileForExtent(layerId: UUID, zoom: Int, extent: Extent)(implicit projectLayerIds: Set[UUID]): OptionT[Future, MultibandTile] = {
    val cacheKey = s"extent-tile-$layerId-$zoom-${extent.xmin}-${extent.ymin}-${extent.xmax}-${extent.ymax}"
    OptionT(
      timedFuture("layer-for-tile-extent-cache")(
        rfCache.caching(cacheKey, doCache = cacheConfig.layerTile.enabled)( timedFuture("layer-for-tile-extent-s3")(
          Future {
            Try {
              S3CollectionLayerReader(store)
                .query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](LayerId(layerId.toString, zoom))
                .where(Intersects(extent))
                .result
                .stitch
                .crop(extent)
                .tile
                .resample(256, 256)
            } match {
              case Success(tile) => Option(tile)
              case Failure(e) =>
                logger.error(s"Query layer $layerId at zoom $zoom for $extent: ${e.getMessage}")
                None
            }
          }
        ))
      )
    )
  }



  /** Calculate the histogram for the least resolute zoom level to automatically render tiles */
  def modelLayerGlobalHistogram(
    toolRunId: UUID,
    subNode: Option[UUID],
    user: User,
    voidCache: Boolean = false
  ): OptionT[Future, Histogram[Double]] = {
    val cacheKey = s"histogram-${toolRunId}-${subNode}-${user.id}"

    if (voidCache) rfCache.delete(cacheKey)
    rfCache.cachingOptionT(cacheKey, doCache = cacheConfig.tool.enabled) {
      for {
        toolRun <- LayerCache.toolRun(toolRunId, user, voidCache)
        ast <- LayerCache.toolEvalRequirements(toolRunId, subNode, user, voidCache)
        (extent, zoom) <- TileSources.fullDataWindow(ast.tileSources)
        literalAst <- OptionT(
                        GlobalInterpreter.literalize(ast, extent, { r: RFMLRaster => TileSources.globalSource(extent, zoom, r) })
                          .map({ validatedAst => validatedAst.toOption })
                      )
        tile <- OptionT.fromOption[Future](GlobalInterpreter.interpret(literalAst, extent) match {
                  case Valid(lztile) => lztile.evaluateDouble
                  case Invalid(e) => None
                })
      } yield {
        val hist = StreamingHistogram.fromTile(tile)
        val currentMetadata = ast.metadata.getOrElse(NodeMetadata())
        val updatedMetadata = currentMetadata.copy(histogram = Some(hist))
        val updatedAst = ast.withMetadata(updatedMetadata)
        val updatedToolRun = toolRun.copy(executionParameters = updatedAst.asJson)
        try {
          database.db.run {
            ToolRuns.updateToolRun(updatedToolRun, toolRun.id, user)
          }
        } catch {
          case e: Exception =>
            logger.error(s"Unable to update ToolRun (${toolRun.id}): ${e.getMessage}")
        }
        hist
      }
    }
  }

  def toolAndToolRun(
    toolRunId: UUID,
    user: User,
    voidCache: Boolean = false
  ): OptionT[Future, (Tool.WithRelated, ToolRun)] = {
    rfCache.cachingOptionT(s"tool+run-$toolRunId-${user.id}", doCache = cacheConfig.tool.enabled) {
      for {
        toolRun <- OptionT(database.db.run(ToolRuns.getToolRun(toolRunId, user)))
        tool <- OptionT(Tools.getTool(toolRun.tool, user))
      } yield (tool, toolRun)
    }
  }

  def toolRun(
    toolRunId: UUID,
    user: User,
    voidCache: Boolean = false
  ): OptionT[Future, ToolRun] = {
    rfCache.cachingOptionT(s"tool+run-$toolRunId-${user.id}", doCache = cacheConfig.tool.enabled) {
      for {
        toolRun <- OptionT(database.db.run(ToolRuns.getToolRun(toolRunId, user)))
      } yield toolRun
    }
  }

  /** Calculate all of the prerequisites to evaluation of an AST over a set of tile sources */
  def toolEvalRequirements(
    toolRunId: UUID,
    subNode: Option[UUID],
    user: User,
    voidCache: Boolean = false
  ): OptionT[Future, MapAlgebraAST] =
    traceName("LayerCache.toolEvalRequirements") {
      val cacheKey = s"ast+params-$toolRunId-${subNode}-${user.id}"
      if (voidCache) rfCache.delete(cacheKey)
      rfCache.cachingOptionT(cacheKey) {
        traceName("LayerCache.toolEvalRequirements (no cache)") {
          for {
            toolRun <- LayerCache.toolRun(toolRunId, user)
            ast     <- OptionT.fromOption[Future](toolRun.executionParameters.as[MapAlgebraAST].toOption)
            subAst <- OptionT.fromOption[Future](subNode match {
                         case Some(id) => ast.find(id)
                         case None => Some(ast)
                       })
          } yield subAst
        }
      }
    }

  /** Calculate all of the prerequisites to evaluation of an AST over a set of tile sources */
  def toolRunColorMap(
    toolRunId: UUID,
    subNode: Option[UUID],
    user: User,
    colorRamp: ColorRamp,
    colorRampName: String
  ): OptionT[Future, ColorMap] = traceName("LayerCache.toolRunColorMap") {
    val cacheKey = s"colormap-$toolRunId-${subNode}-${user.id}-${colorRampName}"
    rfCache.cachingOptionT(cacheKey, doCache = cacheConfig.tool.enabled) {
      traceName("LayerCache.toolRunColorMap (no cache)") {
        for {
          ast    <- LayerCache.toolEvalRequirements(toolRunId, subNode, user)
          cmap   <- {
                      val metadata: Option[NodeMetadata] = ast.metadata
                      OptionT.fromOption[Future](metadata.flatMap(_.classMap).map(_.toColorMap))
                        .orElse({
                          for {
                            md <- OptionT.fromOption[Future](metadata)
                            breaks <- OptionT.fromOption[Future](md.breaks)
                          } yield colorRamp.toColorMap(breaks)
                        }).orElse({
                          for {
                            md <- OptionT.fromOption[Future](metadata)
                            hist <- OptionT.fromOption[Future](md.histogram)
                          } yield colorRamp.toColorMap(hist)
                        }).orElse({
                          for {
                            hist <- LayerCache.modelLayerGlobalHistogram(toolRunId, subNode, user)
                          } yield colorRamp.toColorMap(hist)
                        })
                    }
        } yield cmap
      }
    }
  }
}
