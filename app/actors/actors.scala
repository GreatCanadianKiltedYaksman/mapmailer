package actors

import akka.actor.{ActorLogging, Actor}
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import com.google.common.base.CharMatcher
import models.csv.CodePointOpenCsvEntry
import models.{Location, PostcodeUnit}
import org.geotools.geometry.GeneralDirectPosition
import play.modules.reactivemongo.ReactiveMongoPlugin
import play.api.Play.current
import reactivemongo.api.collections.default.BSONCollection
import scala.util.{Failure, Success}

trait MongoActor extends Actor {
  def driver = ReactiveMongoPlugin.driver
  def connection = ReactiveMongoPlugin.connection
  def db = ReactiveMongoPlugin.db
}

class ProcessCPOCsvEntry extends MongoActor with ActorLogging {
  val pcuCollection = db.collection[BSONCollection]("pcu")

  import context.dispatcher

  val osgbToWgs84Transform = CRS.findMathTransform(CRS.decode("EPSG:27700"), DefaultGeographicCRS.WGS84)

  def receive = {
    case entry: CodePointOpenCsvEntry =>
      val eastNorth = new GeneralDirectPosition(entry.eastings.toInt, entry.northings.toInt)
      val latLng = osgbToWgs84Transform.transform(eastNorth, eastNorth)

      pcuCollection.insert(PostcodeUnit(CharMatcher.WHITESPACE.removeFrom(entry.postcode).toUpperCase,
        Location(truncateAt(latLng.getOrdinate(0), 8), truncateAt(latLng.getOrdinate(1), 8))
      )).onComplete{
        case Failure(e) => throw e
        case Success(_) =>
      }
  }

  def truncateAt(n: Double, p: Int): Double = { val s = math pow (10, p); (math floor n * s) / s }
}