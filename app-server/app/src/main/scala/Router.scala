package com.azavea.rf

import scala.concurrent.ExecutionContext

import com.azavea.rf.healthcheck._
import com.azavea.rf.user._
import com.azavea.rf.utils.Database

import akka.http.scaladsl.server.Directives._

/**
  * Contains all routes for Raster Foundry API/Healthcheck endpoints.
  * 
  * Actual routes should be written in the relevant feature as much as is feasible
  * 
  */
class Router()(implicit db:Database, ec:ExecutionContext) extends HealthCheckRoutes with UserRoutes {

  val routes = healthCheckRoutes() ~
    userRoutes()

}
