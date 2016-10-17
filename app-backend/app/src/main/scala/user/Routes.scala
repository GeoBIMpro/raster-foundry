package com.azavea.rf.user

import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes

import com.lonelyplanet.akka.http.extensions.PaginationDirectives

import com.azavea.rf.auth.Authentication
import com.azavea.rf.database.Database
import com.azavea.rf.database.tables.Users
import com.azavea.rf.utils.UserErrorHandler
import com.azavea.rf.datamodel._

/**
  * Routes for users
  */
trait UserRoutes extends Authentication with PaginationDirectives with UserErrorHandler {

  implicit def database: Database

  def userRoutes:Route = {
    handleExceptions(userExceptionHandler) {
      authenticate { user =>
        pathPrefix("api" / "users") {
          pathEndOrSingleSlash {
            withPagination { page =>
              get {
                onSuccess(Users.getPaginatedUsers(page)) { resp =>
                  complete(resp)
                }
              }
            } ~
            post {
              //TODO: This should only be accessible by users with the correct permission
              //      (IE admin in the "Public" org)
              entity(as[User.Create]) { newUser =>
                onComplete(Users.createUser(newUser)) {
                  case Success(user) => onSuccess(Users.getUserWithOrgsById(user.id)) {
                    case Some(user) => complete(StatusCodes.Created, user)
                    case None => complete(StatusCodes.InternalServerError)
                  }
                  case Failure(_) => complete(StatusCodes.InternalServerError)
                }
              }
            }
          } ~
          pathPrefix(Segment) { authIdEncoded =>
            val authId = java.net.URLDecoder.decode(authIdEncoded, "US_ASCII")
            pathEndOrSingleSlash {
              get {
                onSuccess(
                  Users.getUserWithOrgsById(authId)
                ) {
                  case Some(user) => complete(user)
                  case _ => complete((StatusCodes.NotFound))
                }
              }
            }
          }
        }
      }
    }
  }
}