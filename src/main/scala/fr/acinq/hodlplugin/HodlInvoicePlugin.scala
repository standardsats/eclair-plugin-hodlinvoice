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

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import fr.acinq.eclair.{Kit, Plugin, PluginParams, Setup}
import fr.acinq.hodlplugin.api.Service
import fr.acinq.hodlplugin.handler.HodlPaymentHandler
import grizzled.slf4j.Logging

class HodlInvoicePlugin extends Plugin with Logging {
  var pluginConfig: HodlInvoiceConfig = _
  private var setupRef: Setup = _

  override def params: PluginParams = new PluginParams {
    override def name: String = "HodlInvoicePlugin"
  }

  override def onSetup(setup: Setup): Unit = {
    pluginConfig = new HodlInvoiceConfig(datadir = setup.datadir)
    logger.info("Setting up HodlPlugin")
    setupRef = setup
  }

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
  }

  override def onKit(kit: Kit): Unit = {
    kit.system actorOf Props(classOf[WatchdogSync], kit, setupRef, pluginConfig)
    kit.system actorOf Props(classOf[ExternalHedgeClient], kit, setupRef, pluginConfig)
  }

  override def onKit(kit: Kit): Unit = {
    this.kit = kit
    implicit val system = kit.system
    implicit val ec = kit.system.dispatcher
    implicit val materializer = ActorMaterializer()

    val hodlHandler = new HodlPaymentHandler(kit.nodeParams, kit.paymentHandler)
    kit.paymentHandler ! hodlHandler

    val apiService = new Service(conf, kit.system, hodlHandler)
    Http().bindAndHandle(apiService.route, apiService.apiHost, apiService.apiPort)

    logger.info(s"ready")
  }

}