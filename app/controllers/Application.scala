package controllers

import play.api.mvc._
import play.modules.reactivemongo.MongoController

import reactivemongo.bson._
import models._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import models.Location
import scala.Some
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.Distinct
import play.modules.reactivemongo.json.BSONFormats._
import JsonFormats._
import scala.concurrent.Future
import play.api.Play.current
import com.google.common.cache.{CacheLoader, CacheBuilder}
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.RateLimiter
import play.api.Logger
import play.Play
import play.api.cache.Cached

object Application extends Controller with MongoController {
  val partyCollection = db.collection[BSONCollection]("parties")

  implicit val locationWites = Json.writes[Location]
  implicit val partyWrites = Json.writes[Party]

  val config = play.api.Play.configuration
  val ratelimit = config.getInt("ratelimit").getOrElse(3)

  val rateLimiters = CacheBuilder.newBuilder().maximumSize(100).expireAfterAccess(10, TimeUnit.MINUTES).build(
    new CacheLoader[String, RateLimiter] {
      def load(key: String) = {
        RateLimiter.create(ratelimit)
      }
    })

  def rateLimited[A](action: Action[A]) = Action.async(action.parser) {
    request =>
      if (rateLimiters.get(request.remoteAddress).tryAcquire())
        action(request)
      else {
        Logger.warn(s"Rate limit of $ratelimit requests/second exceeded by ${request.remoteAddress}, responding with status '429 Too Many Requests'")
        Future.successful(TooManyRequest(s"Rate limit of $ratelimit requests/second exceeded"))
      }
  }

  def ping = Action {
    Ok("pong")
  }

  def index = Cached("index", 3600) {
    Action.async {
      db.command(Distinct("parties", "grps")).map {
        groups =>
          Ok(views.html.index(groups.sorted, Location(-2, 53)))
      }
    }
  }

  def party(partyId: String) = Cached("party" + partyId, 3600) {
    rateLimited {
      Action.async {
        partyCollection.find(BSONDocument("cid" -> partyId)).one[Party].map {
          case Some(party) => Ok(Json.toJson(party))
          case None => NotFound("not found")
        }
      }
    }
  }

  def search = rateLimited {
    Action.async(parse.json) {
      request =>
        val parties = (request.body \ "geometry" \ "type").as[String].toLowerCase match {
          case "circle" =>
            val coordinates = (request.body \ "geometry" \ "coordinates").as[Array[Double]]

            partyCollection.find(BSONDocument("loc" ->
              BSONDocument("$geoWithin" -> BSONDocument("$centerSphere" -> BSONArray(coordinates, (request.body \ "geometry" \ "radius").as[Double] / 1609.34 / 3959)))))
              .cursor[Party]

          case "polygon" =>
            val coordinates = (request.body \ "geometry" \ "coordinates").as[Array[Array[Array[Double]]]].flatten.map(coordinate => BSONArray(coordinate.head, coordinate.tail.head))

            partyCollection.find(BSONDocument("loc" ->
              BSONDocument("$geoWithin" -> BSONDocument("$geometry" -> BSONDocument("type" -> "Polygon", "coordinates" -> BSONArray(BSONArray(coordinates)))))))
              .cursor[Party]

          case _ => ???
        }

        parties.collect[List]().map {
          parties =>
            Ok(Json.toJson(parties))
        }
    }
  }
}