/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.hodlplugin

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.{Kit, Plugin, PluginParams, RouteProvider, Setup}
import grizzled.slf4j.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class HodlInvoicePlugin extends Plugin with Logging with RouteProvider {
  var pluginConfig: HodlInvoiceConfig = _
  var paymentActor: ActorRef = _
  var hodlHandler: HodlPaymentHandler = _


  override def params: PluginParams = new PluginParams {
    override def name: String = "HodlInvoicePlugin"
  }

  override def onSetup(setup: Setup): Unit = {
    logger.info("setting up HodlPlugin")
    pluginConfig = new HodlInvoiceConfig(datadir = setup.datadir)
  }

  override def onKit(kit: Kit): Unit = {
    implicit val coreActorSystem: ActorSystem = kit.system
    paymentActor = kit.paymentHandler
    val hodlHandler = new HodlPaymentHandler(kit.nodeParams, kit.paymentHandler)
    kit.paymentHandler ! hodlHandler
    logger.info("payment handler set up")
  }

  override def route(eclairDirectives: EclairDirectives): Route = {
    import eclairDirectives._
    import fr.acinq.eclair.api.serde.FormParamExtractors._
    import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

    val hodlAccept: Route = postRequest("hodlaccept") { implicit t =>
      formFields("paymentHash".as[ByteVector32]) { case (paymentHash) =>
        val futureResponse = (paymentActor ? HODL_ACCEPT(paymentHash)).mapTo[CommandResponse]
        complete(futureResponse)
      }
    }

    val hodlReject: Route = postRequest("hodlreject") { implicit t =>
      formFields("paymentHash".as[ByteVector32]) { case (paymentHash) =>
        val futureResponse = (paymentActor ? HODL_REJECT(paymentHash)).mapTo[CommandResponse]
        complete(futureResponse)
      }
    }
    hodlAccept ~ hodlReject

  }
}