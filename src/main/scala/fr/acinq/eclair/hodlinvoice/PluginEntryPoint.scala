package fr.acinq.eclair.hodlinvoice

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{Kit, Plugin, Setup}
import grizzled.slf4j.Logging

import scala.concurrent.Future
import scala.concurrent.duration._

class PluginEntryPoint extends Plugin with Logging {

  logger.info("loading HodlPlugin")

  var conf: Config = null
  var kit: Kit = null

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
  }

  override def onKit(kit: Kit): Unit = {
    this.kit = kit
    implicit val system = kit.system
    implicit val ec = kit.system.dispatcher
    implicit val materializer = ActorMaterializer()

    val apiHost = conf.getString("api.binding-ip")
    val apiPort = conf.getInt("api.port") + 1 //FIXME: +1 because we must bind to a different port than the eclair API
    val password = conf.getString("api.password")

    def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
      case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
      case _ => akka.pattern.after(1 second, using = kit.system.scheduler)(Future.successful(None))(kit.system.dispatcher) // force a 1 sec pause to deter brute force
    }

    val hodlHandler = new HodlPaymentHandler(kit.nodeParams, kit.commandBuffer, kit.nodeParams.db.payments)
    kit.paymentHandler ! hodlHandler

    // start mini hodl api
    val route: Route = {
      new {} with Directives {
        val r =
          authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
            post {
              formField('payment_hash) { ph =>
                path("hodlaccept") {
                  complete(hodlHandler.acceptPayment(ByteVector32.fromValidHex(ph)))
                } ~ path("hodlreject") {
                  complete(hodlHandler.rejectPayment(ByteVector32.fromValidHex(ph)))
                }
              }
            }
          }
      }
    }.r

    Http().bindAndHandle(route, apiHost, apiPort)
    logger.info(s"ready")
  }

}